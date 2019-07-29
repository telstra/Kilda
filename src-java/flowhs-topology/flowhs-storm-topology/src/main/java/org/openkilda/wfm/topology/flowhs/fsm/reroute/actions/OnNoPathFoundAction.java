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
        String flowId = stateMachine.getFlowId();
        log.debug("Setting the flow status of {} to down", flowId);

        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(flowId);

            dashboardLogger.onFlowStatusUpdate(flowId, FlowStatus.DOWN);
            flow.setStatus(FlowStatus.DOWN);
            stateMachine.setOriginalFlowStatus(null);
            stateMachine.setNewFlowStatus(FlowStatus.DOWN);

            if (stateMachine.isReroutePrimary() && stateMachine.getNewPrimaryForwardPath() == null
                    && stateMachine.getNewPrimaryReversePath() == null) {
                if (flow.getForwardPathId() == null && flow.getReversePathId() == null) {
                    log.debug("Skip marking flow path statuses as inactive: flow {} doesn't have main paths", flowId);
                } else {
                    log.debug("Set the flow path status of {}/{} to inactive",
                            flow.getForwardPathId(), flow.getReversePathId());
                    flowPathRepository.updateStatus(flow.getForwardPathId(), FlowPathStatus.INACTIVE);
                    flowPathRepository.updateStatus(flow.getReversePathId(), FlowPathStatus.INACTIVE);
                }
            }

            if (stateMachine.isRerouteProtected() && stateMachine.getNewProtectedForwardPath() == null
                    && stateMachine.getNewProtectedReversePath() == null) {
                if (flow.getForwardPathId() == null && flow.getReversePathId() == null) {
                    log.debug("Skip marking flow path statuses as inactive: flow {} doesn't have protected paths",
                            flowId);
                } else {
                    log.debug("Set the flow path status of {}/{} to inactive",
                            flow.getProtectedForwardPathId(), flow.getProtectedReversePathId());
                    flowPathRepository.updateStatus(flow.getProtectedForwardPathId(), FlowPathStatus.INACTIVE);
                    flowPathRepository.updateStatus(flow.getProtectedReversePathId(), FlowPathStatus.INACTIVE);
                }
            }
        });

        stateMachine.saveActionToHistory("The flow status was set to down");
    }
}
