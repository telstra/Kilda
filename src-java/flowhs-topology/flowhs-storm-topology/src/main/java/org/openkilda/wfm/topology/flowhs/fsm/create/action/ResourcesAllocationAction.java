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
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSegmentCookie.FlowSegmentCookieBuilder;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
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

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ResourcesAllocationAction extends NbTrackableAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    private final PathComputer pathComputer;
    protected final int pathAllocationRetriesLimit;
    protected final int pathAllocationRetryDelay;
    private final FlowResourcesManager resourcesManager;
    private final SwitchRepository switchRepository;
    private final IslRepository islRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;

    private final FlowPathBuilder flowPathBuilder;
    private final FlowCommandBuilderFactory commandBuilderFactory;

    public ResourcesAllocationAction(PathComputer pathComputer, PersistenceManager persistenceManager,
                                     int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                     FlowResourcesManager resourcesManager) {
        super(persistenceManager);

        this.pathComputer = pathComputer;
        this.pathAllocationRetriesLimit = pathAllocationRetriesLimit;
        this.pathAllocationRetryDelay = pathAllocationRetryDelay;
        this.resourcesManager = resourcesManager;
        this.switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        this.switchPropertiesRepository = persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository();
        this.islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
        KildaConfigurationRepository kildaConfigurationRepository = persistenceManager.getRepositoryFactory()
                .createKildaConfigurationRepository();

        this.flowPathBuilder = new FlowPathBuilder(switchRepository, switchPropertiesRepository,
                kildaConfigurationRepository);
        this.commandBuilderFactory = new FlowCommandBuilderFactory(resourcesManager);
    }

    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowCreateContext context,
                                                    FlowCreateFsm stateMachine) throws FlowProcessingException {
        try {
            String flowId = stateMachine.getFlowId();
            log.debug("Allocation resources has been started");
            stateMachine.setPathsBeenAllocated(false);

            if (context != null && context.getTargetFlow() != null) {
                createFlow(context.getTargetFlow());
            } else if (!flowRepository.exists(flowId)) {
                log.warn("Flow {} has been deleted while creation was in progress", flowId);
                return Optional.empty();
            }

            createPaths(stateMachine);

            log.debug("Resources allocated successfully for the flow {}", flowId);
            stateMachine.setPathsBeenAllocated(true);

            Flow resultFlow = getFlow(flowId);
            createSpeakerRequestFactories(stateMachine, resultFlow);
            saveHistory(stateMachine, resultFlow);
            if (resultFlow.isOneSwitchFlow()) {
                stateMachine.fire(Event.SKIP_NON_INGRESS_RULES_INSTALL);
            } else {
                stateMachine.fireNext(context);
            }
            return Optional.of(buildResponseMessage(resultFlow, stateMachine.getCommandContext()));
        } catch (UnroutableFlowException | RecoverableException e) {
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
    }

    private void createFlow(RequestedFlow targetFlow) throws FlowNotFoundException, FlowAlreadyExistException {
        try {
            transactionManager.doInTransaction(() -> {
                Flow flow = RequestedFlowMapper.INSTANCE.toFlow(targetFlow);
                flow.setStatus(FlowStatus.IN_PROGRESS);
                getFlowGroupFromContext(targetFlow.getDiverseFlowId())
                        .ifPresent(flow::setGroupId);
                flowRepository.add(flow);
            });
        } catch (ConstraintViolationException e) {
            throw new FlowAlreadyExistException(format("Failed to save flow with id %s", targetFlow.getFlowId()), e);
        }
    }

    private Optional<String> getFlowGroupFromContext(String diverseFlowId) throws FlowNotFoundException {
        if (StringUtils.isNotBlank(diverseFlowId)) {
            return flowRepository.getOrCreateFlowGroupId(diverseFlowId)
                    .map(Optional::of)
                    .orElseThrow(() -> new FlowNotFoundException(diverseFlowId));
        }
        return Optional.empty();
    }

    @SneakyThrows
    private void createPaths(FlowCreateFsm stateMachine) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException, FlowNotFoundException, FlowAlreadyExistException {
        RetryPolicy pathAllocationRetryPolicy = new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceAllocationException.class)
                .retryOn(UnroutableFlowException.class)
                .retryOn(PersistenceException.class)
                .withMaxRetries(pathAllocationRetriesLimit);
        if (pathAllocationRetryDelay > 0) {
            pathAllocationRetryPolicy.withDelay(pathAllocationRetryDelay, TimeUnit.MILLISECONDS);
        }
        SyncFailsafe failsafe = Failsafe.with(pathAllocationRetryPolicy)
                .onRetry(e -> log.warn("Failure in resource allocation. Retrying...", e))
                .onRetriesExceeded(e -> log.warn("Failure in resource allocation. No more retries", e));
        try {
            failsafe.run(() -> allocateMainPath(stateMachine));
            failsafe.run(() -> allocateProtectedPath(stateMachine));
        } catch (FailsafeException ex) {
            throw ex.getCause();
        }
    }

    private void createSpeakerRequestFactories(FlowCreateFsm stateMachine, Flow flow) {
        final FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(flow.getEncapsulationType());
        final CommandContext commandContext = stateMachine.getCommandContext();

        List<FlowSegmentRequestFactory> requestFactories;

        // ingress
        requestFactories = stateMachine.getIngressCommands();
        SpeakerRequestBuildContext buildContext = buildBaseSpeakerContextForInstall(
                flow.getSrcSwitchId(), flow.getDestSwitchId());
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

    private void allocateMainPath(FlowCreateFsm stateMachine) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException {
        GetPathsResult paths = pathComputer.getPath(getFlow(stateMachine.getFlowId()));

        log.debug("Creating the primary path {} for flow {}", paths, stateMachine.getFlowId());

        transactionManager.doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId());
            FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
            final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                    .flowEffectiveId(flowResources.getUnmaskedCookie());

            FlowPath forward = flowPathBuilder.buildFlowPath(
                    flow, flowResources.getForward(), paths.getForward(),
                    cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
            forward.setStatus(FlowPathStatus.IN_PROGRESS);
            flowPathRepository.add(forward);
            flow.setForwardPath(forward);

            FlowPath reverse = flowPathBuilder.buildFlowPath(
                    flow, flowResources.getReverse(), paths.getReverse(),
                    cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
            reverse.setStatus(FlowPathStatus.IN_PROGRESS);
            flowPathRepository.add(reverse);
            flow.setReversePath(reverse);

            updateIslsForFlowPath(forward);
            updateIslsForFlowPath(reverse);

            stateMachine.setForwardPathId(forward.getPathId());
            stateMachine.setReversePathId(reverse.getPathId());
            log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);
            stateMachine.getFlowResources().add(flowResources);
        });
    }

    private void allocateProtectedPath(FlowCreateFsm stateMachine) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException, FlowNotFoundException {
        String flowId = stateMachine.getFlowId();
        Flow tmpFlow = getFlow(flowId);
        if (!tmpFlow.isAllocateProtectedPath()) {
            return;
        }
        tmpFlow.setGroupId(flowRepository.getOrCreateFlowGroupId(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId)));
        GetPathsResult protectedPath = pathComputer.getPath(tmpFlow);

        boolean overlappingProtectedPathFound =
                flowPathBuilder.arePathsOverlapped(protectedPath.getForward(), tmpFlow.getForwardPath())
                        || flowPathBuilder.arePathsOverlapped(protectedPath.getReverse(), tmpFlow.getReversePath());
        if (overlappingProtectedPathFound) {
            log.info("Couldn't find non overlapping protected path. Result flow state: {}", tmpFlow);
            throw new UnroutableFlowException("Couldn't find non overlapping protected path", tmpFlow.getFlowId());
        }

        log.debug("Creating the protected path {} for flow {}", protectedPath, tmpFlow);

        transactionManager.doInTransaction(() -> {
            Flow flow = getFlow(flowId);

            FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
            final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                    .flowEffectiveId(flowResources.getUnmaskedCookie());

            FlowPath forward = flowPathBuilder.buildFlowPath(
                    flow, flowResources.getForward(), protectedPath.getForward(),
                    cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
            forward.setStatus(FlowPathStatus.IN_PROGRESS);
            flowPathRepository.add(forward);
            flow.setProtectedForwardPath(forward);

            FlowPath reverse = flowPathBuilder.buildFlowPath(
                    flow, flowResources.getReverse(), protectedPath.getReverse(),
                    cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
            reverse.setStatus(FlowPathStatus.IN_PROGRESS);
            flowPathRepository.add(reverse);
            flow.setProtectedReversePath(reverse);

            updateIslsForFlowPath(forward);
            updateIslsForFlowPath(reverse);

            stateMachine.setProtectedForwardPathId(forward.getPathId());
            stateMachine.setProtectedReversePathId(reverse.getPathId());
            log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);
            stateMachine.getFlowResources().add(flowResources);
        });
    }

    private void updateIslsForFlowPath(FlowPath flowPath) throws ResourceAllocationException {
        for (PathSegment pathSegment : flowPath.getSegments()) {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            updateAvailableBandwidth(pathSegment.getSrcSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitchId(), pathSegment.getDestPort());
        }
    }

    private void updateAvailableBandwidth(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort)
            throws ResourceAllocationException {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);
        log.debug("Updating ISL {}_{}-{}_{} with used bandwidth {}", srcSwitch, srcPort, dstSwitch, dstPort,
                usedBandwidth);
        long islAvailableBandwidth =
                islRepository.updateAvailableBandwidth(srcSwitch, srcPort, dstSwitch, dstPort, usedBandwidth);
        if (islAvailableBandwidth < 0) {
            throw new ResourceAllocationException(format("ISL %s_%d-%s_%d was overprovisioned",
                    srcSwitch, srcPort, dstSwitch, dstPort));
        }
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
