/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import static java.lang.String.format;
import static java.util.Collections.emptySet;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.reroute.error.RerouteError;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.share.history.model.FlowEventData;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.NbTrackableAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;

import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class ValidateFlowAction extends NbTrackableAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final KildaConfigurationRepository kildaConfigurationRepository;
    private final FeatureTogglesRepository featureTogglesRepository;
    private final FlowOperationsDashboardLogger dashboardLogger;

    public ValidateFlowAction(PersistenceManager persistenceManager, FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager);
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        kildaConfigurationRepository = repositoryFactory.createKildaConfigurationRepository();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        this.dashboardLogger = dashboardLogger;
    }

    @TimedExecution("fsm.validate_flow")
    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowRerouteContext context,
                                                    FlowRerouteFsm stateMachine) {
        String flowId = stateMachine.getFlowId();
        Set<IslEndpoint> affectedIsl =
                new HashSet<>(Optional.ofNullable(context.getAffectedIsl()).orElse(emptySet()));
        dashboardLogger.onFlowPathReroute(flowId, affectedIsl, context.isForceReroute());

        String rerouteReason = context.getRerouteReason();
        stateMachine.saveNewEventToHistory("Started flow validation", FlowEventData.Event.REROUTE,
                rerouteReason == null ? FlowEventData.Initiator.NB : FlowEventData.Initiator.AUTO,
                rerouteReason == null ? null : "Reason: " + rerouteReason);
        stateMachine.setRerouteReason(rerouteReason);

        Flow flow = transactionManager.doInTransaction(() -> {
            Flow foundFlow = getFlow(flowId);
            if (foundFlow.getStatus() == FlowStatus.IN_PROGRESS) {
                String message = format("Flow %s is in progress now", flowId);
                stateMachine.setRerouteError(new RerouteError(message));
                throw new FlowProcessingException(ErrorType.REQUEST_INVALID, message);
            }

            if (!foundFlow.getSrcSwitch().isActive()) {
                String message = format("Flow's %s src switch is not active", flowId);
                stateMachine.setRerouteError(new RerouteError(message));
                throw new FlowProcessingException(ErrorType.UNPROCESSABLE_REQUEST, message);
            }
            if (!foundFlow.getDestSwitch().isActive()) {
                String message = format("Flow's %s dest switch is not active", flowId);
                stateMachine.setRerouteError(new RerouteError(message));
                throw new FlowProcessingException(ErrorType.UNPROCESSABLE_REQUEST, message);
            }

            stateMachine.setOriginalFlowStatus(foundFlow.getStatus());
            stateMachine.setOriginalFlowStatusInfo(foundFlow.getStatusInfo());
            stateMachine.setOriginalEncapsulationType(foundFlow.getEncapsulationType());
            stateMachine.setOriginalPathComputationStrategy(foundFlow.getPathComputationStrategy());
            stateMachine.setRecreateIfSamePath(!foundFlow.isActive() || context.isForceReroute());
            stateMachine.setOriginalFlow(RequestedFlowMapper.INSTANCE.toRequestedFlow(foundFlow));
            stateMachine.setPeriodicPingsEnabled(foundFlow.isPeriodicPings());

            if (foundFlow.getTargetPathComputationStrategy() != null) {
                foundFlow.setPathComputationStrategy(foundFlow.getTargetPathComputationStrategy());
                foundFlow.setTargetPathComputationStrategy(null);
            }

            foundFlow.setStatus(FlowStatus.IN_PROGRESS);
            return foundFlow;
        });

        featureTogglesRepository.find().ifPresent(featureToggles ->
                Optional.ofNullable(featureToggles.getFlowsRerouteUsingDefaultEncapType()).ifPresent(toggle -> {
                    if (toggle) {
                        stateMachine.setNewEncapsulationType(
                                kildaConfigurationRepository.getOrDefault().getFlowEncapsulationType());
                    }
                }));

        boolean reroutePrimary;
        boolean rerouteProtected;
        if (affectedIsl.isEmpty()) {
            // no know affected ISLs
            reroutePrimary = true;
            rerouteProtected = true;
        } else {
            reroutePrimary = checkIsPathAffected(flow.getForwardPathId(), affectedIsl)
                    || checkIsPathAffected(flow.getReversePathId(), affectedIsl);
            rerouteProtected = checkIsPathAffected(flow.getProtectedForwardPathId(), affectedIsl)
                    || checkIsPathAffected(flow.getProtectedReversePathId(), affectedIsl);
        }
        // check protected path presence
        rerouteProtected &= flow.isAllocateProtectedPath();

        if (! reroutePrimary && ! rerouteProtected) {
            throw new FlowProcessingException(ErrorType.NOT_FOUND, format(
                    "No paths of the flow %s are affected by failure on %s", flowId,
                    affectedIsl.stream()
                            .map(IslEndpoint::toString)
                            .collect(Collectors.joining(","))));
        }

        if (reroutePrimary) {
            log.info("Reroute for the flow {} will affect primary paths: {} / {}",
                    flowId, flow.getForwardPathId(), flow.getReversePathId());
        }
        if (rerouteProtected) {
            log.info("Reroute for the flow {} will affect protected paths: {} / {}",
                    flowId, flow.getProtectedForwardPathId(), flow.getProtectedReversePathId());
        }

        stateMachine.setReroutePrimary(reroutePrimary);
        stateMachine.setRerouteProtected(rerouteProtected);

        stateMachine.setEffectivelyDown(context.isEffectivelyDown());

        if (stateMachine.isRerouteProtected() && flow.isPinned()) {
            throw new FlowProcessingException(ErrorType.REQUEST_INVALID,
                    format("Flow %s is pinned, fail to reroute its protected paths", flowId));
        }

        stateMachine.setAffectedIsls(context.getAffectedIsl());
        stateMachine.setForceReroute(context.isForceReroute());
        stateMachine.setIgnoreBandwidth(context.isIgnoreBandwidth());

        stateMachine.saveActionToHistory("Flow was validated successfully");

        return Optional.empty();
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not reroute flow";
    }

    private boolean checkIsPathAffected(PathId pathId, Set<IslEndpoint> affectedIsl) {
        if (pathId == null) {
            return false;
        }

        FlowPath path = getFlowPath(pathId);
        boolean isAffected = false;
        for (PathSegment segment : path.getSegments()) {
            isAffected = affectedIsl.contains(getSegmentSourceEndpoint(segment));
            if (! isAffected) {
                isAffected = affectedIsl.contains(getSegmentDestEndpoint(segment));
            }

            if (isAffected) {
                break;
            }
        }

        return isAffected;
    }

    private IslEndpoint getSegmentSourceEndpoint(PathSegment segment) {
        return new IslEndpoint(segment.getSrcSwitchId(), segment.getSrcPort());
    }

    private IslEndpoint getSegmentDestEndpoint(PathSegment segment) {
        return new IslEndpoint(segment.getDestSwitchId(), segment.getDestPort());
    }
}
