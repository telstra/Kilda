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
public class AllocatePrimaryResourcesAction extends
        BaseResourceAllocationAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    public AllocatePrimaryResourcesAction(PersistenceManager persistenceManager,
                                          int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                          PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                          FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager, pathAllocationRetriesLimit, pathAllocationRetryDelay,
                pathComputer, resourcesManager, dashboardLogger);
    }

    @Override
    protected boolean isAllocationRequired(FlowUpdateFsm stateMachine) {
        // The primary path is always required to be updated.
        return true;
    }

    @Override
    protected void allocate(FlowUpdateFsm stateMachine)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        String flowId = stateMachine.getFlowId();

        Set<String> flowIds = Sets.newHashSet(flowId);
        if (stateMachine.getBulkUpdateFlowIds() != null) {
            flowIds.addAll(stateMachine.getBulkUpdateFlowIds());
        }

        log.debug("Finding paths for flows {}", flowIds);
        List<FlowPath> pathsToReuse = new ArrayList<>(flowPathRepository.findActualByFlowIds(flowIds));

        Flow tmpFlow = getFlow(flowId);
        log.debug("Finding a new primary path for flow {}", flowId);
        List<PathId> pathIdsToReuse = pathsToReuse.stream().map(FlowPath::getPathId).collect(Collectors.toList());
        final GetPathsResult potentialPath = pathComputer.getPath(tmpFlow, pathIdsToReuse);
        stateMachine.setBackUpPrimaryPathComputationWayUsed(potentialPath.isBackUpPathComputationWayUsed());

        FlowPathPair createdPaths = transactionManager.doInTransaction(() -> {
            log.debug("Allocating resources for a new primary path of flow {}", flowId);
            Flow flow = getFlow(flowId);
            FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
            log.debug("Resources have been allocated: {}", flowResources);
            stateMachine.setNewPrimaryResources(flowResources);

            pathsToReuse.add(flow.getForwardPath());
            pathsToReuse.add(flow.getReversePath());
            pathsToReuse.addAll(stateMachine.getRejectedPaths().stream()
                    .map(flow::getPath)
                    .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
                    .collect(Collectors.toList()));
            FlowPathPair newPaths = createFlowPathPair(flow, potentialPath, flowResources, false);
            updateIslsForFlowPath(newPaths.getForwardPathId(), pathsToReuse, false);
            updateIslsForFlowPath(newPaths.getReversePathId(), pathsToReuse, false);
            log.debug("New primary path has been created: {}", newPaths);
            stateMachine.setNewPrimaryForwardPath(newPaths.getForward().getPathId());
            stateMachine.setNewPrimaryReversePath(newPaths.getReverse().getPathId());
            return newPaths;
        });

        saveAllocationActionWithDumpsToHistory(stateMachine, tmpFlow, "primary", createdPaths);
    }

    @Override
    protected void onFailure(FlowUpdateFsm stateMachine) {
        stateMachine.setNewPrimaryResources(null);
        stateMachine.setNewPrimaryForwardPath(null);
        stateMachine.setNewPrimaryReversePath(null);
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not update flow";
    }
}
