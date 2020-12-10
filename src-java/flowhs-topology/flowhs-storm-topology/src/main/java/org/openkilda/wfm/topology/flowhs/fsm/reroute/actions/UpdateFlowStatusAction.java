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
import org.openkilda.model.FlowStatus;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateFlowStatusAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowOperationsDashboardLogger dashboardLogger;

    public UpdateFlowStatusAction(PersistenceManager persistenceManager,
                                  FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager);
        this.dashboardLogger = dashboardLogger;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        Sample sample = Timer.start();
        try {
            String flowId = stateMachine.getFlowId();

            FlowStatus resultStatus = transactionManager.doInTransaction(() -> {
                Flow flow = getFlow(flowId);
                FlowStatus flowStatus = flow.computeFlowStatus();

                if (flowStatus != flow.getStatus()) {
                    dashboardLogger.onFlowStatusUpdate(flowId, flowStatus);
                    flow.setStatus(flowStatus);
                    flow.setStatusInfo(getFlowStatusInfo(flow, flowStatus, stateMachine));
                } else if (FlowStatus.DEGRADED.equals(flowStatus)) {
                    flow.setStatusInfo(getDegradedFlowStatusInfo(flow, stateMachine));
                }
                stateMachine.setNewFlowStatus(flowStatus);
                return flowStatus;
            });

            stateMachine.saveActionToHistory(format("The flow status was set to %s", resultStatus));
        } finally {
            sample.stop(stateMachine.getMeterRegistry().timer("fsm.update_flow_status"));
        }
    }

    private String getFlowStatusInfo(Flow flow, FlowStatus flowStatus, FlowRerouteFsm stateMachine) {
        String flowStatusInfo = null;
        if (!FlowStatus.UP.equals(flowStatus) && !flowStatus.equals(stateMachine.getOriginalFlowStatus())) {
            flowStatusInfo = stateMachine.getErrorReason();
            if (FlowStatus.DEGRADED.equals(flowStatus)) {
                flowStatusInfo = getDegradedFlowStatusInfo(flow, stateMachine);
            }
        }
        return flowStatusInfo;
    }

    private String getDegradedFlowStatusInfo(Flow flow, FlowRerouteFsm stateMachine) {
        boolean ignoreBandwidth = stateMachine.isIgnoreBandwidth();
        boolean ignoreLatency = stateMachine.getNewPrimaryPathComputationStrategy() != flow.getPathComputationStrategy()
                || (flow.isAllocateProtectedPath()
                && stateMachine.getNewProtectedPathComputationStrategy() != flow.getPathComputationStrategy());
        if (ignoreBandwidth && ignoreLatency) {
            return "Couldn't find path with required bandwidth and latency";
        } else if (ignoreBandwidth) {
            return "Couldn't find path with required bandwidth";
        } else if (ignoreLatency) {
            return "Couldn't find path with required latency";
        } else {
            return "Couldn't find non overlapping protected path";
        }
    }
}
