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
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.persistence.FetchStrategy;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.FlowPathSnapshot;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.PathSwappingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SwapFlowPathsAction extends PathSwappingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    public SwapFlowPathsAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        final FlowEncapsulationType flowEncapsulationType = stateMachine.getOriginalFlow().getFlowEncapsulationType();

        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId(), FetchStrategy.DIRECT_RELATIONS);

            flowPathRepository.updateStatus(flow.getForwardPathId(), FlowPathStatus.IN_PROGRESS);
            flowPathRepository.updateStatus(flow.getReversePathId(), FlowPathStatus.IN_PROGRESS);

            FlowPath newForward = stateMachine.getNewPrimaryForwardPath().getPath();
            FlowPath newReverse = stateMachine.getNewPrimaryReversePath().getPath();

            log.debug("Swapping the primary paths {}/{} with {}/{}",
                      flow.getForwardPathId(), flow.getReversePathId(),
                      newForward.getPathId(), newReverse.getPathId());

            saveOldPrimaryPaths(
                    stateMachine, flow, flow.getForwardPath(), flow.getReversePath(), flowEncapsulationType);

            flow.setForwardPath(newForward.getPathId());
            flow.setReversePath(newReverse.getPathId());

            saveHistory(stateMachine, flow.getFlowId(), newForward.getPathId(), newReverse.getPathId());

            if (flow.getProtectedForwardPathId() != null) {
                flowPathRepository.updateStatus(flow.getProtectedForwardPathId(), FlowPathStatus.IN_PROGRESS);
            }
            if (flow.getProtectedReversePathId() != null) {
                flowPathRepository.updateStatus(flow.getProtectedReversePathId(), FlowPathStatus.IN_PROGRESS);
            }

            saveOldProtectedPaths(
                    stateMachine, flow, flow.getProtectedForwardPath(), flow.getProtectedReversePath(),
                    flowEncapsulationType);

            FlowPathSnapshot newProtectedForward = stateMachine.getNewProtectedForwardPath();
            FlowPathSnapshot newProtectedReverse = stateMachine.getNewProtectedReversePath();
            if (newProtectedForward != null && newProtectedReverse != null) {
                PathId forwardPathId = newProtectedForward.getPath().getPathId();
                flow.setProtectedForwardPath(forwardPathId);
                PathId reversePathId = newProtectedReverse.getPath().getPathId();
                flow.setProtectedReversePath(reversePathId);

                log.debug("Swapping the protected paths {}/{} with {}/{}",
                        flow.getProtectedForwardPathId(), flow.getProtectedReversePathId(),
                        newProtectedForward, newProtectedReverse);

                saveHistory(stateMachine, flow.getFlowId(), forwardPathId, reversePathId);
            }

            flowRepository.createOrUpdate(flow);
        });
    }

    private void saveHistory(FlowUpdateFsm stateMachine, String flowId, PathId forwardPath, PathId reversePath) {
        stateMachine.saveActionToHistory("Flow was updated with new paths",
                format("The flow %s was updated with paths %s / %s", flowId, forwardPath, reversePath));
    }
}
