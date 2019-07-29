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

package org.openkilda.wfm.topology.flowhs.fsm.update.actions;

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowPathRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RevertResourceAllocationAction extends
        BaseFlowPathRemovalAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    private final FlowResourcesManager resourcesManager;

    public RevertResourceAllocationAction(PersistenceManager persistenceManager,
                                          FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        this.resourcesManager = resourcesManager;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId());

            FlowResources newPrimaryResources = stateMachine.getNewPrimaryResources();
            if (newPrimaryResources != null) {
                resourcesManager.deallocatePathResources(newPrimaryResources);
                saveHistory(stateMachine, flow, newPrimaryResources);
            }

            FlowResources newProtectedResources = stateMachine.getNewProtectedResources();
            if (newProtectedResources != null) {
                resourcesManager.deallocatePathResources(newProtectedResources);
                saveHistory(stateMachine, flow, newProtectedResources);
            }

            FlowPath newPrimaryForward = null;
            FlowPath newPrimaryReverse = null;
            if (stateMachine.getNewPrimaryForwardPath() != null
                    && stateMachine.getNewPrimaryReversePath() != null) {
                newPrimaryForward = getFlowPath(stateMachine.getNewPrimaryForwardPath());
                newPrimaryReverse = getFlowPath(stateMachine.getNewPrimaryReversePath());
            }

            FlowPath newProtectedForward = null;
            FlowPath newProtectedReverse = null;
            if (stateMachine.getNewProtectedForwardPath() != null
                    && stateMachine.getNewProtectedReversePath() != null) {
                newProtectedForward = getFlowPath(stateMachine.getNewProtectedForwardPath());
                newProtectedReverse = getFlowPath(stateMachine.getNewProtectedReversePath());
            }

            if (newPrimaryForward != null && newPrimaryReverse != null) {
                log.debug("Removing the new primary paths {} / {}", newPrimaryForward, newPrimaryReverse);
                FlowPathPair pathsToDelete = FlowPathPair.builder()
                        .forward(newPrimaryForward).reverse(newPrimaryReverse).build();
                deleteFlowPaths(pathsToDelete);

                saveRemovalActionWithDumpToHistory(stateMachine, flow, pathsToDelete);
            }

            if (newProtectedForward != null && newProtectedReverse != null) {
                log.debug("Removing the new protected paths {} / {}", newProtectedForward, newProtectedReverse);
                FlowPathPair pathsToDelete = FlowPathPair.builder()
                        .forward(newProtectedForward).reverse(newProtectedReverse).build();
                deleteFlowPaths(pathsToDelete);

                saveRemovalActionWithDumpToHistory(stateMachine, flow, pathsToDelete);
            }
        });

        stateMachine.setNewPrimaryResources(null);
        stateMachine.setNewPrimaryForwardPath(null);
        stateMachine.setNewPrimaryReversePath(null);
        stateMachine.setNewProtectedResources(null);
        stateMachine.setNewProtectedForwardPath(null);
        stateMachine.setNewProtectedReversePath(null);
    }

    private void saveHistory(FlowUpdateFsm stateMachine, Flow flow, FlowResources resources) {
        FlowDumpData flowDumpData = HistoryMapper.INSTANCE.map(flow, resources, DumpType.STATE_BEFORE);
        stateMachine.saveActionWithDumpToHistory("Flow resources were deallocated",
                format("The flow resources for %s / %s were deallocated",
                        resources.getForward().getPathId(), resources.getReverse().getPathId()), flowDumpData);
    }
}
