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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnNoPathFoundAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowOperationsDashboardLogger dashboardLogger;

    public OnNoPathFoundAction(PersistenceManager persistenceManager, FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager);
        this.dashboardLogger = dashboardLogger;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        final long time = System.currentTimeMillis();
        String flowId = stateMachine.getFlowId();
        log.debug("Updating the flow status of {} after 'no path found' event", flowId);

        FlowStatus flowStatus = transactionManager.doInTransaction(() -> {
            stateMachine.setOriginalFlowStatus(null);

            Flow flow = getFlow(flowId);
            if (stateMachine.isReroutePrimary() && stateMachine.getNewPrimaryForwardPath() == null
                    && stateMachine.getNewPrimaryReversePath() == null) {
                if (flow.getForwardPathId() == null && flow.getReversePathId() == null) {
                    log.debug("Skip marking flow path statuses as inactive: flow {} doesn't have main paths", flowId);
                } else {
                    log.debug("Set the flow path status of {}/{} to inactive",
                            flow.getForwardPathId(), flow.getReversePathId());
                    if (flow.getForwardPathId() != null) {
                        flow.getForwardPath().setStatus(FlowPathStatus.INACTIVE);
                    }
                    if (flow.getReversePathId() != null) {
                        flow.getReversePath().setStatus(FlowPathStatus.INACTIVE);
                    }
                }
            }

            if (stateMachine.isRerouteProtected() && stateMachine.getNewProtectedForwardPath() == null
                    && stateMachine.getNewProtectedReversePath() == null) {
                if (flow.getProtectedForwardPathId() == null && flow.getProtectedReversePathId() == null) {
                    log.debug("Skip marking flow path statuses as inactive: flow {} doesn't have protected paths",
                            flowId);
                } else {
                    log.debug("Set the flow path status of {}/{} to inactive",
                            flow.getProtectedForwardPathId(), flow.getProtectedReversePathId());
                    if (flow.getProtectedForwardPathId() != null) {
                        flow.getProtectedForwardPath().setStatus(FlowPathStatus.INACTIVE);
                    }
                    if (flow.getProtectedReversePathId() != null) {
                        flow.getProtectedReversePath().setStatus(FlowPathStatus.INACTIVE);
                    }
                }
            }

            FlowStatus newFlowStatus = flow.computeFlowStatus();
            if (newFlowStatus != FlowStatus.DOWN && newFlowStatus != FlowStatus.DEGRADED) {
                log.error("Computed unexpected status {} of flow {} after 'no path found' event", newFlowStatus, flow);
                newFlowStatus = FlowStatus.DOWN;
            }

            log.debug("Setting the flow status of {} to {}", flowId, newFlowStatus);
            dashboardLogger.onFlowStatusUpdate(flowId, newFlowStatus);
            flow.setStatus(newFlowStatus);
            stateMachine.setNewFlowStatus(newFlowStatus);
            return newFlowStatus;
        });
        log.warn("HSTIME reroute SHOULD NOT HAPPEN: no path found " + (System.currentTimeMillis() - time));
        stateMachine.saveActionToHistory(String.format("The flow status was set to %s", flowStatus));
    }
}
