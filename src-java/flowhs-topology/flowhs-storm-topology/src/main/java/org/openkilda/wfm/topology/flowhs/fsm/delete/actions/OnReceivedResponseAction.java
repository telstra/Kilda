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

package org.openkilda.wfm.topology.flowhs.fsm.delete.actions;

import static java.lang.String.format;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.model.cookie.Cookie;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteContext;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.State;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class OnReceivedResponseAction extends
        HistoryRecordingAction<FlowDeleteFsm, State, Event, FlowDeleteContext> {
    @Override
    protected void perform(State from, State to, Event event, FlowDeleteContext context, FlowDeleteFsm stateMachine) {
        SpeakerFlowSegmentResponse response = context.getSpeakerFlowResponse();
        if (!response.isSuccess() || response instanceof FlowErrorResponse) {
            throw new IllegalArgumentException(
                    format("Invoked %s for an error response: %s", this.getClass(), response));
        }

        UUID commandId = response.getCommandId();
        if (!stateMachine.removePendingCommand(commandId)) {
            log.info("Received a response for unexpected command: {}", response);
            return;
        }

        Cookie cookie = response.getCookie();
        stateMachine.saveActionToHistory("Rule was deleted",
                format("The rule was deleted: switch %s, cookie %s", response.getSwitchId(), cookie));

        if (stateMachine.getPendingCommands().isEmpty()) {
            if (stateMachine.getFailedCommands().isEmpty()) {
                log.debug("Received responses for all pending remove commands of the flow {}",
                        stateMachine.getFlowId());
            } else {
                String errorMessage = format("Received error response(s) for %d remove commands",
                        stateMachine.getFailedCommands().size());
                stateMachine.saveErrorToHistory(errorMessage);

            }
            stateMachine.fire(Event.RULES_REMOVED);
        }
    }
}
