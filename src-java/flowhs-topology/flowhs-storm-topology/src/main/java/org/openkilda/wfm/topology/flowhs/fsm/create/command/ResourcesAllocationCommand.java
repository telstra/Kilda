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
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSegmentCookie.FlowSegmentCookieBuilder;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
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
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

        try {
            ResourcesAllocationResponseBuilder responseBuilder = ResourcesAllocationResponse.builder()
                    .commandId(commandId)
                    .messageContext(messageContext);

            if (context != null && context.getTargetFlow() != null) {
                createFlow(service, context.getTargetFlow());
            } else if (!service.getFlowRepository().exists(flowId)) {
                log.warn("Flow {} has been deleted while creation was in progress", flowId);
                service.sendResponse(responseBuilder.build());
                return;
            }

            createPaths(service, responseBuilder);

            log.debug("Resources allocated successfully for the flow {}", flowId);

            Flow resultFlow = service.getFlow(flowId);
            createSpeakerRequestFactories(service, resultFlow, responseBuilder);

            responseBuilder.flow(resultFlow);
            service.sendResponse(responseBuilder.build());

        } catch (UnroutableFlowException | RecoverableException e) {
            String message = "Not enough bandwidth or no path found. " + e.getMessage();
            log.error(message, e);
            sendError(service, NOT_FOUND, FLOW_PROCESSING, message);
        } catch (ResourceAllocationException e) {
            String message = "Failed to allocate flow resources. " + e.getMessage();
            log.error(message, e);
            sendError(service, INTERNAL_ERROR, FLOW_PROCESSING, message);
        } catch (FlowNotFoundException e) {
            String message = "Couldn't find the diverse flow. " + e.getMessage();
            log.error(message, e);
            sendError(service, NOT_FOUND, FLOW_PROCESSING, message);
        } catch (FlowAlreadyExistException e) {
            String message = "Flow already exist. " + e.getMessage();
            log.error(message, e);
            sendError(service, INTERNAL_ERROR, FLOW_ALREADY_EXIST, message);
        }
    }

    private void createFlow(SpeakerWorkerService service, RequestedFlow targetFlow)
            throws FlowNotFoundException, FlowAlreadyExistException {
        try {
            service.getTransactionManager().doInTransaction(() -> {
                Flow flow = RequestedFlowMapper.INSTANCE.toFlow(targetFlow);
                flow.setStatus(FlowStatus.IN_PROGRESS);
                getFlowGroupFromContext(service, targetFlow.getDiverseFlowId())
                        .ifPresent(flow::setGroupId);
                service.getFlowRepository().add(flow);
            });
        } catch (ConstraintViolationException e) {
            throw new FlowAlreadyExistException(format("Failed to save flow with id %s", targetFlow.getFlowId()), e);
        }
    }

    private Optional<String> getFlowGroupFromContext(SpeakerWorkerService service, String diverseFlowId)
            throws FlowNotFoundException {
        if (StringUtils.isNotBlank(diverseFlowId)) {
            return service.getFlowRepository().getOrCreateFlowGroupId(diverseFlowId)
                    .map(Optional::of)
                    .orElseThrow(() -> new FlowNotFoundException(diverseFlowId));
        }
        return Optional.empty();
    }

    @SneakyThrows
    private void createPaths(SpeakerWorkerService service, ResourcesAllocationResponseBuilder responseBuilder)
            throws UnroutableFlowException, RecoverableException, ResourceAllocationException, FlowNotFoundException,
            FlowAlreadyExistException {
        RetryPolicy pathAllocationRetryPolicy = new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceAllocationException.class)
                .retryOn(UnroutableFlowException.class)
                .retryOn(PersistenceException.class)
                .withMaxRetries(service.getPathAllocationRetriesLimit());
        if (service.getPathAllocationRetryDelay() > 0) {
            pathAllocationRetryPolicy.withDelay(
                    service.getPathAllocationRetryDelay(), TimeUnit.MILLISECONDS);
        }
        SyncFailsafe failsafe = Failsafe.with(pathAllocationRetryPolicy)
                .onRetry(e -> log.warn("Failure in resource allocation. Retrying...", e))
                .onRetriesExceeded(e -> log.warn("Failure in resource allocation. No more retries", e));
        try {
            failsafe.run(() -> allocateMainPath(service, responseBuilder));
            failsafe.run(() -> allocateProtectedPath(service, responseBuilder));
        } catch (FailsafeException ex) {
            throw ex.getCause();
        }
    }

    private void createSpeakerRequestFactories(SpeakerWorkerService service, Flow flow,
                                               ResourcesAllocationResponseBuilder responseBuilder) {
        final FlowCommandBuilder commandBuilder = (new FlowCommandBuilderFactory(service.getResourcesManager()))
                .getBuilder(flow.getEncapsulationType());

        // ingress
        SpeakerRequestBuildContext buildContext = service.buildBaseSpeakerContextForInstall(
                flow.getSrcSwitchId(), flow.getDestSwitchId());
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

    private void allocateMainPath(SpeakerWorkerService service, ResourcesAllocationResponseBuilder responseBuilder)
            throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException {
        GetPathsResult paths = service.getPathComputer().getPath(service.getFlow(flowId));

        log.debug("Creating the primary path {} for flow {}", paths, flowId);

        service.getTransactionManager().doInTransaction(() -> {
            Flow flow = service.getFlow(flowId);
            FlowResources flowResources = service.getResourcesManager().allocateFlowResources(flow);
            final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                    .flowEffectiveId(flowResources.getUnmaskedCookie());

            FlowPath forward = service.getFlowPathBuilder().buildFlowPath(
                    flow, flowResources.getForward(), paths.getForward(),
                    cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
            forward.setStatus(FlowPathStatus.IN_PROGRESS);
            service.getFlowPathRepository().add(forward);
            flow.setForwardPath(forward);

            FlowPath reverse = service.getFlowPathBuilder().buildFlowPath(
                    flow, flowResources.getReverse(), paths.getReverse(),
                    cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
            reverse.setStatus(FlowPathStatus.IN_PROGRESS);
            service.getFlowPathRepository().add(reverse);
            flow.setReversePath(reverse);

            updateIslsForFlowPath(service, forward);
            updateIslsForFlowPath(service, reverse);

            responseBuilder.forwardPathId(forward.getPathId());
            responseBuilder.reversePathId(reverse.getPathId());
            log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);
            responseBuilder.flowResource(flowResources);
        });
    }

    private void allocateProtectedPath(SpeakerWorkerService service, ResourcesAllocationResponseBuilder responseBuilder)
            throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException, FlowNotFoundException {
        Flow tmpFlow = service.getFlow(flowId);
        if (!tmpFlow.isAllocateProtectedPath()) {
            return;
        }
        tmpFlow.setGroupId(service.getFlowRepository().getOrCreateFlowGroupId(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId)));
        GetPathsResult protectedPath = service.getPathComputer().getPath(tmpFlow);

        boolean overlappingProtectedPathFound =
                arePathsOverlapped(protectedPath.getForward(), tmpFlow.getForwardPath())
                        || arePathsOverlapped(protectedPath.getReverse(), tmpFlow.getReversePath());
        if (overlappingProtectedPathFound) {
            log.info("Couldn't find non overlapping protected path. Result flow state: {}", tmpFlow);
            throw new UnroutableFlowException("Couldn't find non overlapping protected path", tmpFlow.getFlowId());
        }

        log.debug("Creating the protected path {} for flow {}", protectedPath, tmpFlow);

        service.getTransactionManager().doInTransaction(() -> {
            Flow flow = service.getFlow(flowId);

            FlowResources flowResources = service.getResourcesManager().allocateFlowResources(flow);
            final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                    .flowEffectiveId(flowResources.getUnmaskedCookie());

            FlowPath forward = service.getFlowPathBuilder().buildFlowPath(
                    flow, flowResources.getForward(), protectedPath.getForward(),
                    cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
            forward.setStatus(FlowPathStatus.IN_PROGRESS);
            service.getFlowPathRepository().add(forward);
            flow.setProtectedForwardPath(forward);

            FlowPath reverse = service.getFlowPathBuilder().buildFlowPath(
                    flow, flowResources.getReverse(), protectedPath.getReverse(),
                    cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
            reverse.setStatus(FlowPathStatus.IN_PROGRESS);
            service.getFlowPathRepository().add(reverse);
            flow.setProtectedReversePath(reverse);

            updateIslsForFlowPath(service, forward);
            updateIslsForFlowPath(service, reverse);

            responseBuilder.protectedForwardPathId(forward.getPathId());
            responseBuilder.protectedReversePathId(reverse.getPathId());
            log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);
            responseBuilder.flowResource(flowResources);
        });
    }

    private void updateIslsForFlowPath(SpeakerWorkerService service, FlowPath flowPath)
            throws ResourceAllocationException {
        for (PathSegment pathSegment : flowPath.getSegments()) {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            updateAvailableBandwidth(service, pathSegment.getSrcSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitchId(), pathSegment.getDestPort());
        }
    }

    private void updateAvailableBandwidth(
            SpeakerWorkerService service, SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort)
            throws ResourceAllocationException {
        long usedBandwidth = service.getFlowPathRepository().getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);
        log.debug("Updating ISL {}_{}-{}_{} with used bandwidth {}", srcSwitch, srcPort, dstSwitch, dstPort,
                usedBandwidth);
        long islAvailableBandwidth =
                service.getIslRepository().updateAvailableBandwidth(
                        srcSwitch, srcPort, dstSwitch, dstPort, usedBandwidth);
        if (islAvailableBandwidth < 0) {
            throw new ResourceAllocationException(format("ISL %s_%d-%s_%d was overprovisioned",
                    srcSwitch, srcPort, dstSwitch, dstPort));
        }
    }

    private void sendError(SpeakerWorkerService service, ErrorType errorType, DbOperationErrorType operationErrorType,
                           String message) {
        DbErrorResponse dbErrorResponse = new DbErrorResponse(
                messageContext, commandId, errorType, operationErrorType, message);
        service.sendResponse(dbErrorResponse);
    }
}
