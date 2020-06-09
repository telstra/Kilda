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

package org.openkilda.wfm.topology.flowhs.fsm.create.action;

import static java.lang.String.format;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class OnReceivedInstallResponseAction extends OnReceivedResponseAction {
    public OnReceivedInstallResponseAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void handleResponse(FlowCreateFsm stateMachine, SpeakerFlowSegmentResponse response) {
        if (response.isSuccess()) {
            stateMachine.saveActionToHistory("Rule was installed",
                    format("The rule was installed: switch %s, cookie %s",
                            response.getSwitchId(), response.getCookie()));
            stateMachine.getMeterRegistry().counter("fsm.install_rule.success", "flow_id",
                    stateMachine.getFlowId()).increment();
        } else {
            handleError(stateMachine, response);
        }
    }

    @Override
    protected void onComplete(FlowCreateFsm stateMachine, FlowCreateContext context) {
        if (stateMachine.getIngressInstallationTimer() != null) {
            long duration = stateMachine.getIngressInstallationTimer().stop();
            if (duration > 0) {
                stateMachine.getMeterRegistry().timer("fsm.install_ingress_rule.execution",
                        "flow_id", stateMachine.getFlowId())
                        .record(duration, TimeUnit.NANOSECONDS);
            }
            stateMachine.setIngressInstallationTimer(null);
        }

        if (stateMachine.getNoningressInstallationTimer() != null) {
            long duration = stateMachine.getNoningressInstallationTimer().stop();
            if (duration > 0) {
                stateMachine.getMeterRegistry().timer("fsm.install_noningress_rule.execution",
                        "flow_id", stateMachine.getFlowId())
                        .record(duration, TimeUnit.NANOSECONDS);
            }
            stateMachine.setNoningressInstallationTimer(null);
        }

        if (stateMachine.getFailedCommands().isEmpty()) {
            log.debug("Received responses for all pending commands of the flow {} ({})",
                    stateMachine.getFlowId(), stateMachine.getCurrentState());
            stateMachine.fireNext(context);
        } else {
            String errorMessage = format("Received error response(s) for %d commands (%s)",
                    stateMachine.getFailedCommands().size(), stateMachine.getCurrentState());
            stateMachine.getFailedCommands().clear();
            stateMachine.saveErrorToHistory(errorMessage);
            stateMachine.fireError(errorMessage);
        }
    }

    private void handleError(FlowCreateFsm stateMachine, SpeakerFlowSegmentResponse response) {
        FlowErrorResponse errorResponse = (FlowErrorResponse) response;
        stateMachine.getFailedCommands().add(response.getCommandId());
        stateMachine.saveErrorToHistory("Failed to install rule",
                format("Failed to install the rule: switch %s, cookie %s. Error: %s",
                        errorResponse.getSwitchId(), response.getCookie(), errorResponse));
        stateMachine.getMeterRegistry().counter("fsm.install_rule.failed", "flow_id",
                stateMachine.getFlowId()).increment();
    }
}
