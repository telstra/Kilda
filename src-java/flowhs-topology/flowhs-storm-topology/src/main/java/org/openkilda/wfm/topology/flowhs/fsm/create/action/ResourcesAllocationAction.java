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

package org.openkilda.wfm.topology.flowhs.fsm.create.action;

import static java.lang.String.format;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.messaging.Message;
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
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.NbTrackableAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilderFactory;
import org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.v1.exceptions.TransientException;

import java.util.List;
import java.util.Optional;

@Slf4j
public class ResourcesAllocationAction extends NbTrackableAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    private final PathComputer pathComputer;
    private final int transactionRetriesLimit;
    private final FlowResourcesManager resourcesManager;
    private final SwitchRepository switchRepository;
    private final IslRepository islRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;
    private final MeterRegistry meterRegistry;

    private final FlowPathBuilder flowPathBuilder;
    private final FlowCommandBuilderFactory commandBuilderFactory;

    public ResourcesAllocationAction(PathComputer pathComputer, PersistenceManager persistenceManager,
                                     int transactionRetriesLimit, FlowResourcesManager resourcesManager,
                                     MeterRegistry meterRegistry) {
        super(persistenceManager);

        this.pathComputer = pathComputer;
        this.transactionRetriesLimit = transactionRetriesLimit;
        this.resourcesManager = resourcesManager;
        this.switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        this.switchPropertiesRepository = persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository();
        this.islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
        this.meterRegistry = meterRegistry;

        this.flowPathBuilder = new FlowPathBuilder(switchRepository, switchPropertiesRepository);
        this.commandBuilderFactory = new FlowCommandBuilderFactory(resourcesManager);
    }

    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowCreateContext context,
                                                    FlowCreateFsm stateMachine) throws FlowProcessingException {
        log.debug("Allocation resources has been started");
        Sample sample = Timer.start();
        try {
            Optional<Flow> optionalFlow = getFlow(context, stateMachine.getFlowId());
            if (!optionalFlow.isPresent()) {
                log.warn("Flow {} has been deleted while creation was in progress", stateMachine.getFlowId());
                return Optional.empty();
            }

            Flow flow = optionalFlow.get();
            try {
                getFlowGroupFromContext(context).ifPresent(flow::setGroupId);
                createFlowWithPaths(stateMachine, flow);
                createSpeakerRequestFactories(stateMachine, flow);
            } catch (UnroutableFlowException e) {
                throw new FlowProcessingException(ErrorType.NOT_FOUND,
                        "Not enough bandwidth or no path found. " + e.getMessage(), e);
            } catch (ResourceAllocationException e) {
                throw new FlowProcessingException(ErrorType.INTERNAL_ERROR,
                        "Failed to allocate flow resources. " + e.getMessage(), e);
            } catch (FlowNotFoundException e) {
                throw new FlowProcessingException(ErrorType.NOT_FOUND,
                        "Couldn't find the diverse flow. " + e.getMessage(), e);
            } catch (FlowAlreadyExistException e) {
                if (!stateMachine.retryIfAllowed()) {
                    throw new FlowProcessingException(ErrorType.INTERNAL_ERROR, e.getMessage(), e);
                } else {
                    // we have retried the operation, no need to respond.
                    log.debug(e.getMessage(), e);
                    return Optional.empty();
                }
            }

            saveHistory(stateMachine, flow);
            if (flow.isOneSwitchFlow()) {
                stateMachine.fire(Event.SKIP_NON_INGRESS_RULES_INSTALL);
            } else {
                stateMachine.fireNext(context);
            }

            return Optional.of(buildResponseMessage(flow, stateMachine.getCommandContext()));
        } finally {
            sample.stop(meterRegistry.timer("fsm.resource_allocation", "flow_id",
                    stateMachine.getFlowId()));
        }
    }

    private Optional<Flow> getFlow(FlowCreateContext context, String flowId) {
        Optional<RequestedFlow> targetFlow = Optional.ofNullable(context)
                .map(FlowCreateContext::getTargetFlow);
        if (targetFlow.isPresent()) {
            Flow flow = RequestedFlowMapper.INSTANCE.toFlow(targetFlow.get());
            flow.setStatus(FlowStatus.IN_PROGRESS);
            flow.setSrcSwitch(switchRepository.reload(flow.getSrcSwitch()));
            flow.setDestSwitch(switchRepository.reload(flow.getDestSwitch()));

            return Optional.of(flow);
        } else {
            // if flow is not in the context - it means that we are in progress of the retry, so flow should exist in DB
            return flowRepository.findById(flowId);
        }
    }

    private Optional<String> getFlowGroupFromContext(FlowCreateContext context) throws FlowNotFoundException {
        if (context != null) {
            String diverseFlowId = context.getTargetFlow().getDiverseFlowId();
            if (StringUtils.isNotBlank(diverseFlowId)) {
                return flowRepository.getOrCreateFlowGroupId(diverseFlowId)
                        .map(Optional::of)
                        .orElseThrow(() -> new FlowNotFoundException(diverseFlowId));
            }
        }
        return Optional.empty();
    }

    private void createFlowWithPaths(FlowCreateFsm fsm, Flow flow) throws UnroutableFlowException,
            ResourceAllocationException, FlowNotFoundException, FlowAlreadyExistException {
        try {
            Failsafe.with(new RetryPolicy()
                    .retryOn(RecoverableException.class)
                    .retryOn(ResourceAllocationException.class)
                    .retryOn(TransientException.class)
                    .withMaxRetries(transactionRetriesLimit))
                    .onRetry(e -> log.warn("Retrying transaction for resource allocation finished with exception", e))
                    .onRetriesExceeded(e -> log.warn("TX retry attempts exceed with error", e))
                    .run(() -> persistenceManager.getTransactionManager().doInTransaction(() -> {
                        allocateMainPath(fsm, flow);
                        if (flow.isAllocateProtectedPath()) {
                            allocateProtectedPath(fsm, flow);
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
        log.debug("Resources allocated successfully for the flow {}", flow.getFlowId());
    }

    private void createSpeakerRequestFactories(FlowCreateFsm stateMachine, Flow flow) {
        final FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(flow.getEncapsulationType());
        final CommandContext commandContext = stateMachine.getCommandContext();

        List<FlowSegmentRequestFactory> requestFactories;

        // ingress
        requestFactories = stateMachine.getIngressCommands();
        SpeakerRequestBuildContext buildContext = buildBaseSpeakerContextForInstall(
                flow.getSrcSwitch().getSwitchId(), flow.getDestSwitch().getSwitchId());
        requestFactories.addAll(commandBuilder.buildIngressOnly(stateMachine.getCommandContext(), flow, buildContext));

        // non ingress
        requestFactories = stateMachine.getNonIngressCommands();
        requestFactories.addAll(commandBuilder.buildAllExceptIngress(commandContext, flow));
        if (flow.isAllocateProtectedPath()) {
            requestFactories.addAll(commandBuilder.buildAllExceptIngress(
                    commandContext, flow,
                    flow.getProtectedForwardPath(), flow.getProtectedReversePath()));
        }
    }

    private void allocateMainPath(FlowCreateFsm fsm, Flow flow) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException {
        PathPair pathPair = pathComputer.getPath(flow);
        FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath forward = flowPathBuilder.buildFlowPath(
                flow, flowResources.getForward(), pathPair.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
        forward.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setForwardPath(forward);

        FlowPath reverse = flowPathBuilder.buildFlowPath(
                flow, flowResources.getReverse(), pathPair.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
        reverse.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setReversePath(reverse);

        flowPathRepository.lockInvolvedSwitches(forward, reverse);
        flowRepository.createOrUpdate(flow);

        updateIslsForFlowPath(forward);
        updateIslsForFlowPath(reverse);

        fsm.setForwardPathId(forward.getPathId());
        fsm.setReversePathId(reverse.getPathId());
        log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);

        fsm.getFlowResources().add(flowResources);
    }

    private void allocateProtectedPath(FlowCreateFsm fsm, Flow flow) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException, FlowNotFoundException {
        flow.setGroupId(getGroupId(flow.getFlowId()));
        PathPair protectedPath = pathComputer.getPath(flow);

        boolean overlappingProtectedPathFound =
                flowPathBuilder.arePathsOverlapped(protectedPath.getForward(), flow.getForwardPath())
                        || flowPathBuilder.arePathsOverlapped(protectedPath.getReverse(), flow.getReversePath());
        if (overlappingProtectedPathFound) {
            log.info("Couldn't find non overlapping protected path. Result flow state: {}", flow);
            throw new UnroutableFlowException("Couldn't find non overlapping protected path", flow.getFlowId());
        }

        log.debug("Creating the protected path {} for flow {}", protectedPath, flow);

        FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath forward = flowPathBuilder.buildFlowPath(
                flow, flowResources.getForward(), protectedPath.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
        forward.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setProtectedForwardPath(forward);
        fsm.setProtectedForwardPathId(forward.getPathId());

        FlowPath reverse = flowPathBuilder.buildFlowPath(
                flow, flowResources.getReverse(), protectedPath.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
        reverse.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setProtectedReversePath(reverse);
        fsm.setProtectedReversePathId(reverse.getPathId());

        flowPathRepository.lockInvolvedSwitches(forward, reverse);
        flowRepository.createOrUpdate(flow);

        updateIslsForFlowPath(forward);
        updateIslsForFlowPath(reverse);
        fsm.getFlowResources().add(flowResources);
    }

    private void updateIslsForFlowPath(FlowPath path) {
        path.getSegments().forEach(pathSegment -> {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            updateAvailableBandwidth(pathSegment.getSrcSwitch().getSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitch().getSwitchId(), pathSegment.getDestPort());
        });
    }

    private void updateAvailableBandwidth(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort) {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);

        Optional<Isl> matchedIsl = islRepository.findByEndpoints(srcSwitch, srcPort, dstSwitch, dstPort);
        matchedIsl.ifPresent(isl -> {
            isl.setAvailableBandwidth(isl.getMaxBandwidth() - usedBandwidth);
            islRepository.createOrUpdate(isl);
        });
    }

    private String getGroupId(String flowId) throws FlowNotFoundException {
        return flowRepository.getOrCreateFlowGroupId(flowId)
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

    @Override
    protected String getGenericErrorMessage() {
        return "Could not create flow";
    }
}
