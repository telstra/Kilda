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
        final long time = System.currentTimeMillis();
        transactionManager.doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId());

            if (stateMachine.getNewEncapsulationType() != null) {
                flow.setEncapsulationType(stateMachine.getOriginalEncapsulationType());
            }
            flow.setPathComputationStrategy(stateMachine.getOriginalPathComputationStrategy());

            if (stateMachine.getOldPrimaryForwardPath() != null) {
                FlowPath oldForward = getFlowPath(stateMachine.getOldPrimaryForwardPath());
                if (oldForward.getStatus() != FlowPathStatus.ACTIVE) {
                    oldForward.setStatus(stateMachine.getOldPrimaryForwardPathStatus());
                }

                log.debug("Swapping back the primary forward path {} with {}",
                        flow.getForwardPathId(), oldForward.getPathId());

                flow.setForwardPathId(oldForward.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldForward.getPathId());
            }

            if (stateMachine.getOldPrimaryReversePath() != null) {
                FlowPath oldReverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
                if (oldReverse.getStatus() != FlowPathStatus.ACTIVE) {
                    oldReverse.setStatus(stateMachine.getOldPrimaryReversePathStatus());
                }

                log.debug("Swapping back the primary reverse path {} with {}",
                        flow.getReversePathId(), oldReverse.getPathId());

                flow.setReversePathId(oldReverse.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldReverse.getPathId());
            }

            if (stateMachine.getOldProtectedForwardPath() != null) {
                FlowPath oldForward = getFlowPath(stateMachine.getOldProtectedForwardPath());
                if (oldForward.getStatus() != FlowPathStatus.ACTIVE) {
                    oldForward.setStatus(stateMachine.getOldProtectedForwardPathStatus());
                }

                log.debug("Swapping back the protected forward path {} with {}",
                        flow.getProtectedForwardPathId(), oldForward.getPathId());

                flow.setProtectedForwardPathId(oldForward.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldForward.getPathId());
            }

            if (stateMachine.getOldProtectedReversePath() != null) {
                FlowPath oldReverse = getFlowPath(stateMachine.getOldProtectedReversePath());
                if (oldReverse.getStatus() != FlowPathStatus.ACTIVE) {
                    oldReverse.setStatus(stateMachine.getOldProtectedReversePathStatus());
                }

                log.debug("Swapping back the protected reverse path {} with {}",
                        flow.getProtectedReversePathId(), oldReverse.getPathId());

                flow.setProtectedReversePathId(oldReverse.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldReverse.getPathId());
            }
        });
        log.warn("HSTIME reroute SHOULD NOT HAPPEN: revert path swap " + (System.currentTimeMillis() - time));
    }

    private void saveHistory(FlowRerouteFsm stateMachine, String flowId, PathId pathId) {
        stateMachine.saveActionToHistory("Flow was reverted to old paths",
                format("The flow %s was updated with the path %s", flowId, pathId));
    }
}
