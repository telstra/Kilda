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

package org.openkilda.wfm.topology.flowhs.fsm.update.actions;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseResourceAllocationAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;

import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class AllocateProtectedResourcesAction extends
        BaseResourceAllocationAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    public AllocateProtectedResourcesAction(PersistenceManager persistenceManager,
                                            int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                            PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                            FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager, pathAllocationRetriesLimit, pathAllocationRetryDelay,
                pathComputer, resourcesManager, dashboardLogger);
    }

    @Override
    protected boolean isAllocationRequired(FlowUpdateFsm stateMachine) {
        return stateMachine.getTargetFlow().isAllocateProtectedPath();
    }

    @Override
    protected void allocate(FlowUpdateFsm stateMachine)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        String flowId = stateMachine.getFlowId();

        Set<String> flowIds = Sets.newHashSet(flowId);
        if (stateMachine.getBulkUpdateFlowIds() != null) {
            flowIds.addAll(stateMachine.getBulkUpdateFlowIds());
        }

        log.debug("Finding protected path ids for flows {}", flowIds);
        List<FlowPath> pathsToReuse = new ArrayList<>(flowPathRepository.findActualByFlowIds(flowIds));

        Flow tmpFlow = getFlow(flowId);
        log.debug("Finding a new protected path for flow {}", flowId);
        List<PathId> pathIdsToReuse = pathsToReuse.stream().map(FlowPath::getPathId).collect(Collectors.toList());
        GetPathsResult potentialPath = pathComputer.getPath(tmpFlow, pathIdsToReuse);
        stateMachine.setBackUpProtectedPathComputationWayUsed(potentialPath.isBackUpPathComputationWayUsed());

        PathId newPrimaryForwardPathId = stateMachine.getNewPrimaryForwardPath();
        FlowPath primaryForwardPath = getFlowPath(tmpFlow, newPrimaryForwardPathId);
        PathId newPrimaryReversePathId = stateMachine.getNewPrimaryReversePath();
        FlowPath primaryReversePath = getFlowPath(tmpFlow, newPrimaryReversePathId);
        boolean overlappingProtectedPathFound = primaryForwardPath != null
                && flowPathBuilder.arePathsOverlapped(potentialPath.getForward(), primaryForwardPath)
                || primaryReversePath != null
                && flowPathBuilder.arePathsOverlapped(potentialPath.getReverse(), primaryReversePath);
        if (overlappingProtectedPathFound) {
            stateMachine.saveActionToHistory("Couldn't find non overlapping protected path");
        } else {
            FlowPathPair createdPaths = transactionManager.doInTransaction(() -> {
                log.debug("Allocating resources for a new protected path of flow {}", flowId);
                Flow flow = getFlow(flowId);
                FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
                log.debug("Resources have been allocated: {}", flowResources);
                stateMachine.setNewProtectedResources(flowResources);

                pathsToReuse.add(flow.getProtectedForwardPath());
                pathsToReuse.add(flow.getProtectedReversePath());
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

            saveAllocationActionWithDumpsToHistory(stateMachine, tmpFlow, "protected", createdPaths);
        }
    }

    @Override
    protected void onFailure(FlowUpdateFsm stateMachine) {
        stateMachine.setNewProtectedResources(null);
        stateMachine.setNewProtectedForwardPath(null);
        stateMachine.setNewProtectedReversePath(null);
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not update flow";
    }
}
