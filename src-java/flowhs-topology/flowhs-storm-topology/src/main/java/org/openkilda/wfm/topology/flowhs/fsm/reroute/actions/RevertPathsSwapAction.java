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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RevertPathsSwapAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public RevertPathsSwapAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId());

            if (stateMachine.getNewEncapsulationType() != null) {
                flow.setEncapsulationType(stateMachine.getOriginalEncapsulationType());
            }
            flow.setPathComputationStrategy(stateMachine.getOriginalPathComputationStrategy());

            if (stateMachine.getOldPrimaryForwardPath() != null && stateMachine.getOldPrimaryReversePath() != null) {
                FlowPath oldForward = getFlowPath(stateMachine.getOldPrimaryForwardPath());
                if (oldForward.getStatus() != FlowPathStatus.ACTIVE) {
                    oldForward.setStatus(stateMachine.getOldPrimaryForwardPathStatus());
                }

                FlowPath oldReverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
                if (oldReverse.getStatus() != FlowPathStatus.ACTIVE) {
                    oldReverse.setStatus(stateMachine.getOldPrimaryReversePathStatus());
                }

                log.debug("Swapping back the primary paths {}/{} with {}/{}",
                        flow.getForwardPath().getPathId(), flow.getReversePath().getPathId(),
                        oldForward.getPathId(), oldReverse.getPathId());

                flow.setForwardPathId(oldForward.getPathId());
                flow.setReversePathId(oldReverse.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldForward.getPathId(), oldReverse.getPathId());
            }

            if (stateMachine.getOldProtectedForwardPath() != null
                    && stateMachine.getOldProtectedReversePath() != null) {
                FlowPath oldForward = getFlowPath(stateMachine.getOldProtectedForwardPath());
                if (oldForward.getStatus() != FlowPathStatus.ACTIVE) {
                    oldForward.setStatus(stateMachine.getOldProtectedForwardPathStatus());
                }

                FlowPath oldReverse = getFlowPath(stateMachine.getOldProtectedReversePath());
                if (oldReverse.getStatus() != FlowPathStatus.ACTIVE) {
                    oldReverse.setStatus(stateMachine.getOldProtectedReversePathStatus());
                }

                log.debug("Swapping back the protected paths {}/{} with {}/{}",
                        flow.getProtectedForwardPath().getPathId(), flow.getProtectedReversePath().getPathId(),
                        oldForward.getPathId(), oldReverse.getPathId());

                flow.setProtectedForwardPathId(oldForward.getPathId());
                flow.setProtectedReversePathId(oldReverse.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldForward.getPathId(), oldReverse.getPathId());
            }
        });
    }

    private void saveHistory(FlowRerouteFsm stateMachine, String flowId, PathId forwardPath, PathId reversePath) {
        stateMachine.saveActionToHistory("Flow was reverted to old paths",
                format("The flow %s was updated with paths %s / %s", flowId, forwardPath, reversePath));
    }
}
