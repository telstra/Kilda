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
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RevertPathsSwapAction extends FlowProcessingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    public RevertPathsSwapAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        if (stateMachine.getEndpointUpdate().isPartialUpdate()) {
            stateMachine.saveActionToHistory("Skip paths swap");
            return;
        }

        transactionManager.doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId());

            if (stateMachine.getNewPrimaryForwardPath() != null) {
                PathId oldPrimaryForward = stateMachine.getOldPrimaryForwardPath();
                if (oldPrimaryForward != null) {
                    flowPathRepository.findById(oldPrimaryForward)
                            .ifPresent(path -> {
                                if (path.getStatus() != FlowPathStatus.ACTIVE) {
                                    path.setStatus(stateMachine.getOldPrimaryForwardPathStatus());
                                }
                            });
                }

                log.debug("Swapping back the primary forward path {} with {}",
                        flow.getForwardPathId(), oldPrimaryForward != null ? oldPrimaryForward : "no path");

                flow.setForwardPathId(oldPrimaryForward);

                saveHistory(stateMachine, flow.getFlowId(), oldPrimaryForward);
            }

            if (stateMachine.getNewPrimaryReversePath() != null) {
                PathId oldPrimaryReverse = stateMachine.getOldPrimaryReversePath();
                if (oldPrimaryReverse != null) {
                    flowPathRepository.findById(oldPrimaryReverse)
                            .ifPresent(path -> {
                                if (path.getStatus() != FlowPathStatus.ACTIVE) {
                                    path.setStatus(stateMachine.getOldPrimaryReversePathStatus());
                                }
                            });
                }

                log.debug("Swapping back the primary reverse path {} with {}",
                        flow.getReversePathId(), oldPrimaryReverse != null ? oldPrimaryReverse : "no path");

                flow.setReversePathId(oldPrimaryReverse);

                saveHistory(stateMachine, flow.getFlowId(), oldPrimaryReverse);
            }

            if (stateMachine.getNewProtectedForwardPath() != null) {
                PathId oldProtectedForward = stateMachine.getOldProtectedForwardPath();
                if (oldProtectedForward != null) {
                    flowPathRepository.findById(oldProtectedForward)
                            .ifPresent(path -> {
                                if (path.getStatus() != FlowPathStatus.ACTIVE) {
                                    path.setStatus(stateMachine.getOldProtectedForwardPathStatus());
                                }
                            });
                }

                log.debug("Swapping back the protected forward path {} with {}",
                        flow.getProtectedForwardPathId(),
                        oldProtectedForward != null ? oldProtectedForward : "no path");

                flow.setProtectedForwardPathId(oldProtectedForward);

                saveHistory(stateMachine, flow.getFlowId(), oldProtectedForward);
            }

            if (stateMachine.getNewProtectedReversePath() != null) {
                PathId oldProtectedReverse = stateMachine.getOldProtectedReversePath();
                if (oldProtectedReverse != null) {
                    flowPathRepository.findById(oldProtectedReverse)
                            .ifPresent(path -> {
                                if (path.getStatus() != FlowPathStatus.ACTIVE) {
                                    path.setStatus(stateMachine.getOldProtectedReversePathStatus());
                                }
                            });
                }

                log.debug("Swapping back the protected reverse path {} with {}",
                        flow.getProtectedReversePathId(),
                        oldProtectedReverse != null ? oldProtectedReverse : "no path");

                flow.setProtectedReversePathId(oldProtectedReverse);

                saveHistory(stateMachine, flow.getFlowId(), oldProtectedReverse);
            }
        });
    }

    private void saveHistory(FlowUpdateFsm stateMachine, String flowId, PathId pathId) {
        String description;
        if (pathId != null) {
            description = format("The flow %s was updated with the path %s", flowId, pathId);
        } else {
            description = format("The flow %s was updated with no path", flowId);
        }
        stateMachine.saveActionToHistory("Flow was reverted to old paths", description);
    }
}
