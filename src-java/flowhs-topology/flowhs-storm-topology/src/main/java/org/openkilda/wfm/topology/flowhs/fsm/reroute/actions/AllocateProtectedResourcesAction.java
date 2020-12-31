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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseResourceAllocationAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class AllocateProtectedResourcesAction extends
        BaseResourceAllocationAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public AllocateProtectedResourcesAction(PersistenceManager persistenceManager,
                                            int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                            PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                            FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager, pathAllocationRetriesLimit, pathAllocationRetryDelay,
                pathComputer, resourcesManager, dashboardLogger);
    }

    @Override
    protected boolean isAllocationRequired(FlowRerouteFsm stateMachine) {
        return stateMachine.isRerouteProtected();
    }

    @TimedExecution("fsm.resource_allocation_protected")
    @Override
    protected void allocate(FlowRerouteFsm stateMachine)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        String flowId = stateMachine.getFlowId();

        Flow tmpFlowCopy = getFlow(flowId);
        // Detach the entity to avoid propagation to the database.
        flowRepository.detach(tmpFlowCopy);
        if (stateMachine.getNewEncapsulationType() != null) {
            // This is for PCE to use proper (updated) encapsulation type.
            tmpFlowCopy.setEncapsulationType(stateMachine.getNewEncapsulationType());
        }

        log.debug("Finding a new protected path for flow {}", flowId);
        GetPathsResult potentialPath = pathComputer.getPath(tmpFlowCopy,
                Stream.of(tmpFlowCopy.getProtectedForwardPathId(), tmpFlowCopy.getProtectedReversePathId())
                        .filter(Objects::nonNull).collect(Collectors.toList()));
        stateMachine.setBackUpProtectedPathComputationWayUsed(potentialPath.isBackUpPathComputationWayUsed());

        FlowPath primaryForwardPath = tmpFlowCopy.getPath(stateMachine.getNewPrimaryForwardPath())
                .orElse(tmpFlowCopy.getForwardPath());
        FlowPath primaryReversePath = tmpFlowCopy.getPath(stateMachine.getNewPrimaryReversePath())
                .orElse(tmpFlowCopy.getReversePath());
        boolean overlappingProtectedPathFound = primaryForwardPath != null
                && flowPathBuilder.arePathsOverlapped(potentialPath.getForward(), primaryForwardPath)
                || primaryReversePath != null
                && flowPathBuilder.arePathsOverlapped(potentialPath.getReverse(), primaryReversePath);
        if (overlappingProtectedPathFound) {
            // Update the status here as no reroute is going to be performed for the protected.
            FlowPath protectedForwardPath = tmpFlowCopy.getProtectedForwardPath();
            if (protectedForwardPath != null) {
                protectedForwardPath.setStatus(FlowPathStatus.INACTIVE);
            }

            FlowPath protectedReversePath = tmpFlowCopy.getProtectedReversePath();
            if (protectedReversePath != null) {
                protectedReversePath.setStatus(FlowPathStatus.INACTIVE);
            }

            FlowStatus flowStatus = tmpFlowCopy.computeFlowStatus();
            if (flowStatus != tmpFlowCopy.getStatus()) {
                dashboardLogger.onFlowStatusUpdate(flowId, flowStatus);
                flowRepository.updateStatus(flowId, flowStatus);
            }
            stateMachine.setNewFlowStatus(flowStatus);
            stateMachine.setOriginalFlowStatus(null);

            stateMachine.saveActionToHistory("Couldn't find non overlapping protected path. Skipped creating it");
        } else {
            FlowPathPair oldPaths = FlowPathPair.builder()
                    .forward(tmpFlowCopy.getProtectedForwardPath())
                    .reverse(tmpFlowCopy.getProtectedReversePath())
                    .build();

            boolean newPathFound = isNotSamePath(potentialPath, oldPaths);
            if (newPathFound || stateMachine.isRecreateIfSamePath()) {
                if (!newPathFound) {
                    log.debug("Found the same protected path for flow {}. Proceed with recreating it", flowId);
                }

                FlowPathPair createdPaths = transactionManager.doInTransaction(() -> {
                    log.debug("Allocating resources for a new protected path of flow {}", flowId);
                    Flow flow = getFlow(flowId);
                    FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
                    log.debug("Resources have been allocated: {}", flowResources);
                    stateMachine.setNewProtectedResources(flowResources);

                    List<FlowPath> pathsToReuse
                            = Lists.newArrayList(flow.getProtectedForwardPath(), flow.getProtectedReversePath());
                    pathsToReuse.addAll(stateMachine.getRejectedPaths().stream()
                            .map(flow::getPath)
                            .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
                            .collect(Collectors.toList()));
                    FlowPathPair newPaths = createFlowPathPair(flow, potentialPath, flowResources, false);
                    updateIslsForFlowPath(newPaths.getForwardPathId(), pathsToReuse, false);
                    updateIslsForFlowPath(newPaths.getReversePathId(), pathsToReuse, false);
                    log.debug("New protected path has been created: {}", newPaths);
                    stateMachine.setNewProtectedForwardPath(newPaths.getForward().getPathId());
                    stateMachine.setNewProtectedReversePath(newPaths.getReverse().getPathId());
                    return newPaths;
                });

                saveAllocationActionWithDumpsToHistory(stateMachine, tmpFlowCopy, "protected", createdPaths);
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
