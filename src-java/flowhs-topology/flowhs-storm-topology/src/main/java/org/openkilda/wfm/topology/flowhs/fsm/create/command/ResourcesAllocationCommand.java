/* Copyright 2020 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.wfm.topology.flowhs.fsm.create.command;

import static java.lang.String.format;
import static org.openkilda.messaging.error.ErrorType.INTERNAL_ERROR;
import static org.openkilda.messaging.error.ErrorType.NOT_FOUND;
import static org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType.FLOW_ALREADY_EXIST;
import static org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType.FLOW_PROCESSING;
import static org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder.arePathsOverlapped;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSegmentCookie.FlowSegmentCookieBuilder;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.response.ResourcesAllocationResponse;
import org.openkilda.wfm.topology.flowhs.fsm.create.response.ResourcesAllocationResponse.ResourcesAllocationResponseBuilder;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;
import org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilderFactory;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.v1.exceptions.TransientException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public class ResourcesAllocationCommand extends DbCommand implements ICommand<SpeakerWorkerService> {
    CommandContext commandContext;
    FlowCreateContext context;
    String flowId;

    public ResourcesAllocationCommand(@NonNull MessageContext messageContext, @NonNull UUID commandId,
                                      CommandContext commandContext, FlowCreateContext context, String flowId) {
        super(messageContext, commandId);
        this.commandContext = commandContext;
        this.context = context;
        this.flowId = flowId;
    }

    @Override
    public void apply(SpeakerWorkerService service) {
        log.info("Resources allocation for flow {} has been started", flowId);

        ResourcesAllocationResponseBuilder responseBuilder = ResourcesAllocationResponse.builder()
                .commandId(commandId)
                .messageContext(messageContext);
        Optional<Flow> optionalFlow = getFlow(service, context, flowId);
        if (!optionalFlow.isPresent()) {
            log.warn("Flow {} has been deleted while creation was in progress", flowId);
            service.sendResponse(responseBuilder.build());
            return;
        }

        Flow flow = optionalFlow.get();

        try {
            getFlowGroupFromContext(service, context).ifPresent(flow::setGroupId);
            createFlowWithPaths(service, flow, responseBuilder);
            createSpeakerRequestFactories(service, flow, responseBuilder);
        } catch (UnroutableFlowException e) {
            String message = "Not enough bandwidth or no path found. " + e.getMessage();
            log.error(message, e);
            sendError(service, NOT_FOUND, FLOW_PROCESSING, message);
            return;
        } catch (ResourceAllocationException e) {
            String message = "Failed to allocate flow resources. " + e.getMessage();
            log.error(message, e);
            sendError(service, INTERNAL_ERROR, FLOW_PROCESSING, message);
            return;
        } catch (FlowNotFoundException e) {
            String message = "Couldn't find the diverse flow. " + e.getMessage();
            log.error(message, e);
            sendError(service, NOT_FOUND, FLOW_PROCESSING, message);
            return;
        } catch (FlowAlreadyExistException e) {
            String message = "Flow already exist. " + e.getMessage();
            log.error(message, e);
            sendError(service, INTERNAL_ERROR, FLOW_ALREADY_EXIST, message);
            return;
        }

        responseBuilder.flow(flow);
        service.sendResponse(responseBuilder.build());
    }

    private Optional<Flow> getFlow(SpeakerWorkerService service, FlowCreateContext context, String flowId) {
        Optional<RequestedFlow> targetFlow = Optional.ofNullable(context)
                .map(FlowCreateContext::getTargetFlow);
        if (targetFlow.isPresent()) {
            Flow flow = RequestedFlowMapper.INSTANCE.toFlow(targetFlow.get());
            flow.setStatus(FlowStatus.IN_PROGRESS);
            flow.setSrcSwitch(service.getSwitchRepository().reload(flow.getSrcSwitch()));
            flow.setDestSwitch(service.getSwitchRepository().reload(flow.getDestSwitch()));

            return Optional.of(flow);
        } else {
            // if flow is not in the context - it means that we are in progress of the retry, so flow should exist in DB
            return service.getFlowRepository().findById(flowId);
        }
    }

    private Optional<String> getFlowGroupFromContext(SpeakerWorkerService service, FlowCreateContext context)
            throws FlowNotFoundException {
        if (context != null) {
            String diverseFlowId = context.getTargetFlow().getDiverseFlowId();
            if (StringUtils.isNotBlank(diverseFlowId)) {
                return service.getFlowRepository().getOrCreateFlowGroupId(diverseFlowId)
                        .map(Optional::of)
                        .orElseThrow(() -> new FlowNotFoundException(diverseFlowId));
            }
        }
        return Optional.empty();
    }

    private void createFlowWithPaths(SpeakerWorkerService service, Flow flow,
                                     ResourcesAllocationResponseBuilder responseBuilder) throws UnroutableFlowException,
            ResourceAllocationException, FlowNotFoundException, FlowAlreadyExistException {
        try {
            Failsafe.with(new RetryPolicy()
                    .retryOn(RecoverableException.class)
                    .retryOn(ResourceAllocationException.class)
                    .retryOn(TransientException.class)
                    .withMaxRetries(service.getTransactionRetries()))
                    .onRetry(e -> log.warn("Retrying transaction for resource allocation finished with exception", e))
                    .onRetriesExceeded(e -> log.warn("TX retry attempts exceed with error", e))
                    .run(() -> service.getTransactionManager().doInTransaction(() -> {
                        allocateMainPath(service, flow, responseBuilder);
                        if (flow.isAllocateProtectedPath()) {
                            allocateProtectedPath(service, flow, responseBuilder);
                        }
                    }));
        } catch (FailsafeException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof UnroutableFlowException) {
                throw (UnroutableFlowException) cause;
            } else if (cause instanceof ResourceAllocationException) {
                throw (ResourceAllocationException) cause;
            } else if (cause instanceof FlowNotFoundException) {
                throw (FlowNotFoundException) cause;
            } else {
                throw ex;
            }
        } catch (ConstraintViolationException e) {
            throw new FlowAlreadyExistException(format("Failed to save flow with id %s", flow.getFlowId()), e);
        }
        log.info("Resources allocated successfully for the flow {}", flow.getFlowId());
    }

    private void createSpeakerRequestFactories(SpeakerWorkerService service, Flow flow,
                                               ResourcesAllocationResponseBuilder responseBuilder) {
        final FlowCommandBuilder commandBuilder = (new FlowCommandBuilderFactory(service.getResourcesManager()))
                .getBuilder(flow.getEncapsulationType());


        // ingress
        SpeakerRequestBuildContext buildContext = service.buildBaseSpeakerContextForInstall(
                flow.getSrcSwitch().getSwitchId(), flow.getDestSwitch().getSwitchId());
        List<FlowSegmentRequestFactory> ingress = commandBuilder.buildIngressOnly(commandContext, flow, buildContext);

        // non ingress
        List<FlowSegmentRequestFactory> nonIngress = commandBuilder.buildAllExceptIngress(commandContext, flow);
        if (flow.isAllocateProtectedPath()) {
            nonIngress.addAll(commandBuilder.buildAllExceptIngress(
                    commandContext, flow,
                    flow.getProtectedForwardPath(), flow.getProtectedReversePath()));
        }

        responseBuilder.ingressCommands(ingress);
        responseBuilder.nonIngressCommands(nonIngress);
    }

    private void allocateMainPath(SpeakerWorkerService service, Flow flow,
                                  ResourcesAllocationResponseBuilder responseBuilder) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException {
        GetPathsResult paths = service.getPathComputer().getPath(flow);
        FlowResources flowResources = service.getResourcesManager().allocateFlowResources(flow);
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath forward = service.getFlowPathBuilder().buildFlowPath(
                flow, flowResources.getForward(), paths.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
        forward.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setForwardPath(forward);

        FlowPath reverse = service.getFlowPathBuilder().buildFlowPath(
                flow, flowResources.getReverse(), paths.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
        reverse.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setReversePath(reverse);

        service.getFlowPathRepository().lockInvolvedSwitches(forward, reverse);
        service.getFlowRepository().createOrUpdate(flow);

        updateIslsForFlowPath(service, forward);
        updateIslsForFlowPath(service, reverse);

        responseBuilder.forwardPathId(forward.getPathId());
        responseBuilder.reversePathId(reverse.getPathId());
        log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);

        responseBuilder.flowResource(flowResources);
    }

    private void allocateProtectedPath(SpeakerWorkerService service, Flow flow,
                                       ResourcesAllocationResponseBuilder responseBuilder)
            throws UnroutableFlowException, RecoverableException, ResourceAllocationException, FlowNotFoundException {
        flow.setGroupId(getGroupId(service, flow.getFlowId()));
        GetPathsResult protectedPath = service.getPathComputer().getPath(flow);

        boolean overlappingProtectedPathFound =
                arePathsOverlapped(protectedPath.getForward(), flow.getForwardPath())
                        || arePathsOverlapped(protectedPath.getReverse(), flow.getReversePath());
        if (overlappingProtectedPathFound) {
            log.info("Couldn't find non overlapping protected path. Result flow state: {}", flow);
            throw new UnroutableFlowException("Couldn't find non overlapping protected path", flow.getFlowId());
        }

        log.debug("Creating the protected path {} for flow {}", protectedPath, flow);

        FlowResources flowResources = service.getResourcesManager().allocateFlowResources(flow);
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath forward = service.getFlowPathBuilder().buildFlowPath(
                flow, flowResources.getForward(), protectedPath.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
        forward.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setProtectedForwardPath(forward);
        responseBuilder.protectedForwardPathId(forward.getPathId());

        FlowPath reverse = service.getFlowPathBuilder().buildFlowPath(
                flow, flowResources.getReverse(), protectedPath.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
        reverse.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setProtectedReversePath(reverse);
        responseBuilder.protectedReversePathId(reverse.getPathId());

        service.getFlowPathRepository().lockInvolvedSwitches(forward, reverse);
        service.getFlowRepository().createOrUpdate(flow);

        updateIslsForFlowPath(service, forward);
        updateIslsForFlowPath(service, reverse);
        responseBuilder.flowResource(flowResources);
    }

    private void updateIslsForFlowPath(SpeakerWorkerService service, FlowPath path) {
        path.getSegments().forEach(pathSegment -> {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            updateAvailableBandwidth(service, pathSegment.getSrcSwitch().getSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitch().getSwitchId(), pathSegment.getDestPort());
        });
    }

    private void updateAvailableBandwidth(SpeakerWorkerService service, SwitchId srcSwitch, int srcPort,
                                          SwitchId dstSwitch, int dstPort) {
        long usedBandwidth = service.getFlowPathRepository().getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);

        Optional<Isl> matchedIsl = service.getIslRepository().findByEndpoints(srcSwitch, srcPort, dstSwitch, dstPort);
        matchedIsl.ifPresent(isl -> {
            isl.setAvailableBandwidth(isl.getMaxBandwidth() - usedBandwidth);
            service.getIslRepository().createOrUpdate(isl);
        });
    }

    private String getGroupId(SpeakerWorkerService service, String flowId) throws FlowNotFoundException {
        return service.getFlowRepository().getOrCreateFlowGroupId(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId));
    }

    private void saveHistory(FlowCreateFsm stateMachine, Flow flow) {
        FlowDumpData primaryPathsDumpData =
                HistoryMapper.INSTANCE.map(flow, flow.getForwardPath(), flow.getReversePath(), DumpType.STATE_AFTER);
        stateMachine.saveActionWithDumpToHistory("New primary paths were created",
                format("The flow paths were created (with allocated resources): %s / %s",
                        flow.getForwardPathId(), flow.getReversePathId()),
                primaryPathsDumpData);

        if (flow.isAllocateProtectedPath()) {
            FlowDumpData protectedPathsDumpData = HistoryMapper.INSTANCE.map(flow, flow.getProtectedForwardPath(),
                    flow.getProtectedReversePath(), DumpType.STATE_AFTER);
            stateMachine.saveActionWithDumpToHistory("New protected paths were created",
                    format("The flow paths were created (with allocated resources): %s / %s",
                            flow.getProtectedForwardPathId(), flow.getProtectedReversePathId()),
                    protectedPathsDumpData);
        }
    }

    private void sendError(SpeakerWorkerService service, ErrorType errorType, DbOperationErrorType operationErrorType,
                           String message) {
        DbErrorResponse dbErrorResponse = new DbErrorResponse(
                messageContext, commandId, errorType, operationErrorType, message);
        service.sendResponse(dbErrorResponse);
    }
}
