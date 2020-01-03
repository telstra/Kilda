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

import static java.util.Arrays.asList;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseResourceAllocationAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AllocateProtectedResourcesAction
        extends BaseResourceAllocationAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public AllocateProtectedResourcesAction(PersistenceManager persistenceManager, int transactionRetriesLimit,
                                            int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                            PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                            FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager, transactionRetriesLimit, pathAllocationRetriesLimit, pathAllocationRetryDelay,
                pathComputer, resourcesManager, dashboardLogger);
    }

    @Override
    protected boolean isAllocationRequired(FlowRerouteFsm stateMachine) {
        return stateMachine.isRerouteProtected();
    }

    @Override
    protected void allocate(FlowRerouteFsm stateMachine)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        String flowId = stateMachine.getFlowId();
        Flow flow = getFlow(flowId);

        FlowPath primaryForwardPath = extractPath(flow, stateMachine.getNewPrimaryForwardPath(), flow.getForwardPath());
        FlowPath primaryReversePath = extractPath(flow, stateMachine.getNewPrimaryReversePath(), flow.getReversePath());

        if (stateMachine.getNewEncapsulationType() != null) {
            // This is for PCE to use proper (updated) encapsulation type.
            flow.setEncapsulationType(stateMachine.getNewEncapsulationType());
        }

        log.debug("Finding a new protected path for flow {}", flowId);
        PathPair potentialPath = pathComputer.getPath(flow,
                asList(flow.getProtectedForwardPathId(), flow.getProtectedReversePathId()));

        boolean overlappingProtectedPathFound =
                flowPathBuilder.arePathsOverlapped(potentialPath.getForward(), primaryForwardPath)
                        || flowPathBuilder.arePathsOverlapped(potentialPath.getReverse(), primaryReversePath);
        if (overlappingProtectedPathFound) {
            // Update the status here as no reroute is going to be performed for the protected.
            FlowPath protectedForwardPath = flow.getProtectedForwardPath();
            flowPathRepository.updateStatus(protectedForwardPath.getPathId(), FlowPathStatus.INACTIVE);

            FlowPath protectedReversePath = flow.getProtectedReversePath();
            flowPathRepository.updateStatus(protectedReversePath.getPathId(), FlowPathStatus.INACTIVE);

            FlowStatus flowStatus = flow.computeFlowStatus();
            if (flowStatus != flow.getStatus()) {
                dashboardLogger.onFlowStatusUpdate(flowId, flowStatus);
                flowRepository.updateStatus(flowId, flowStatus);
            }
            stateMachine.setNewFlowStatus(flowStatus);
            stateMachine.setOriginalFlowStatus(null);

            stateMachine.saveActionToHistory("Couldn't find non overlapping protected path. Skipped creating it");
        } else {
            FlowPathPair oldPaths = FlowPathPair.builder()
                    .forward(flow.getProtectedForwardPath())
                    .reverse(flow.getProtectedReversePath())
                    .build();

            boolean newPathFound = isNotSamePath(potentialPath, oldPaths);
            if (newPathFound || stateMachine.isRecreateIfSamePath()) {
                if (!newPathFound) {
                    log.debug("Found the same protected path for flow {}. Proceed with recreating it", flowId);
                }

                log.debug("Allocating resources for a new protected path of flow {}", flowId);
                FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
                log.debug("Resources have been allocated: {}", flowResources);
                stateMachine.setNewProtectedResources(flowResources);

                FlowPathPair newPaths = createFlowPathPair(flow, oldPaths, potentialPath, flowResources);
                log.debug("New protected path has been created: {}", newPaths);

                saveProtectedPaths(stateMachine, flow, newPaths, flowResources);
                flushPathChanges(newPaths);

                saveAllocationActionWithDumpsToHistory(stateMachine, flow, "protected", newPaths);
            } else {
                stateMachine.saveActionToHistory("Found the same protected path. Skipped creating of it");
            }
        }
    }

    @Override
    protected void onFailure(FlowRerouteFsm stateMachine) {
        stateMachine.setNewProtectedResources(null);
        stateMachine.setNewProtectedForwardPath(null);
        stateMachine.setNewProtectedReversePath(null);
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not reroute flow";
    }
}
