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

import org.openkilda.messaging.info.reroute.error.NoPathFoundError;
import org.openkilda.model.Flow;
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

@Slf4j
public class AllocatePrimaryResourcesAction extends
        BaseResourceAllocationAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public AllocatePrimaryResourcesAction(PersistenceManager persistenceManager,
                                          int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                          int resourceAllocationRetriesLimit,
                                          PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                          FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager, pathAllocationRetriesLimit, pathAllocationRetryDelay, resourceAllocationRetriesLimit,
                pathComputer, resourcesManager, dashboardLogger);
    }

    @Override
    protected boolean isAllocationRequired(FlowRerouteFsm stateMachine) {
        return stateMachine.isReroutePrimary();
    }

    @TimedExecution("fsm.resource_allocation_primary")
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

        FlowPathPair oldPaths = new FlowPathPair(tmpFlowCopy.getForwardPath(), tmpFlowCopy.getReversePath());
        PathId newForwardPathId = resourcesManager.generatePathId(flowId);
        PathId newReversePathId = resourcesManager.generatePathId(flowId);
        List<PathId> pathsToReuse = Lists.newArrayList(tmpFlowCopy.getForwardPathId(), tmpFlowCopy.getReversePathId());
        pathsToReuse.addAll(stateMachine.getRejectedPaths());

        log.debug("Finding a new primary path for flow {}", flowId);
        GetPathsResult allocatedPaths = allocatePathPair(tmpFlowCopy, newForwardPathId, newReversePathId,
                stateMachine.isIgnoreBandwidth(), pathsToReuse, oldPaths, stateMachine.isRecreateIfSamePath(),
                path -> true);
        if (allocatedPaths != null) {
            log.debug("New primary paths have been allocated: {}", allocatedPaths);
            stateMachine.setBackUpPrimaryPathComputationWayUsed(allocatedPaths.isBackUpPathComputationWayUsed());
            stateMachine.setNewPrimaryForwardPath(newForwardPathId);
            stateMachine.setNewPrimaryReversePath(newReversePathId);

            log.debug("Allocating resources for a new primary path of flow {}", flowId);
            FlowResources flowResources = allocateFlowResources(tmpFlowCopy, newForwardPathId, newReversePathId);
            stateMachine.setNewPrimaryResources(flowResources);

            FlowPathPair createdPaths = createFlowPathPair(flowId, flowResources, allocatedPaths,
                    stateMachine.isIgnoreBandwidth());
            log.debug("New primary paths have been created: {}", createdPaths);

            saveAllocationActionWithDumpsToHistory(stateMachine, tmpFlowCopy, "primary", createdPaths);
        } else {
            stateMachine.saveActionToHistory("Found the same primary path. Skipped creating of it");
        }
    }

    @Override
    protected void onFailure(FlowRerouteFsm stateMachine) {
        stateMachine.setNewPrimaryResources(null);
        stateMachine.setNewPrimaryForwardPath(null);
        stateMachine.setNewPrimaryReversePath(null);
        if (!stateMachine.isIgnoreBandwidth()) {
            stateMachine.setRerouteError(new NoPathFoundError());
        }
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not reroute flow";
    }
}
