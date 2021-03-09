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

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class HandleNotCompletedCommandsAction extends
        HistoryRecordingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    @Override
    public void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        FlowSegmentRequestFactory requestFactory;
        for (UUID commandId : stateMachine.getPendingCommands().keySet()) {
            requestFactory = stateMachine.getRemoveCommands().get(commandId);
            if (requestFactory != null) {
                stateMachine.saveErrorToHistory(
                        "Command is not finished yet",
                        format("Completing the reroute operation although the remove command may not be "
                                       + "finished yet: commandId %s, switch %s, cookie %s",
                               commandId, requestFactory.getSwitchId(), requestFactory.getCookie()));
            } else {
                requestFactory = stateMachine.getIngressCommands().get(commandId);
                stateMachine.saveErrorToHistory(
                        "Command is not finished yet",
                        format("Completing the reroute operation although the install command may not be "
                                       + "finished yet: commandId %s, switch %s, cookie %s",
                               commandId, requestFactory.getSwitchId(), requestFactory.getCookie()));
            }
        }

        for (FlowErrorResponse errorResponse : stateMachine.getFailedCommands().values()) {
            log.warn(
                    "Receive error response from {} for command {}: {}",
                    errorResponse.getSwitchId(), errorResponse.getCommandId(), errorResponse);
        }

        log.debug("Abandoning all pending commands: {}", stateMachine.getPendingCommands());
        stateMachine.clearPendingCommands();
    }
}
