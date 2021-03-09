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

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteContext;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.State;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class OnErrorResponseAction extends HistoryRecordingAction<FlowDeleteFsm, State, Event, FlowDeleteContext> {
    private final int speakerCommandRetriesLimit;

    public OnErrorResponseAction(int speakerCommandRetriesLimit) {
        this.speakerCommandRetriesLimit = speakerCommandRetriesLimit;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowDeleteContext context, FlowDeleteFsm stateMachine) {
        SpeakerFlowSegmentResponse response = context.getSpeakerFlowResponse();
        if (response.isSuccess() || !(response instanceof FlowErrorResponse)) {
            throw new IllegalArgumentException(
                    format("Invoked %s for a success response: %s", this.getClass(), response));
        }

        UUID failedCommandId = response.getCommandId();
        FlowSegmentRequestFactory failedCommand = stateMachine.getRemoveCommands().get(failedCommandId);
        if (!stateMachine.getPendingCommands().contains(failedCommandId) || failedCommand == null) {
            log.info("Received a response for unexpected command: {}", response);
            return;
        }

        FlowErrorResponse errorResponse = (FlowErrorResponse) response;
        int attempt = stateMachine.doRetryForCommand(failedCommandId);
        if (attempt <= speakerCommandRetriesLimit) {
            stateMachine.saveErrorToHistory("Failed to remove rule", format(
                    "Failed to remove the rule: commandId %s, switch %s, cookie %s. Error %s. Retrying (attempt %d)",
                    failedCommandId, errorResponse.getSwitchId(), response.getCookie(), errorResponse, attempt));

            stateMachine.getCarrier().sendSpeakerRequest(failedCommand.makeRemoveRequest(failedCommandId));
        } else {
            stateMachine.removePendingCommand(failedCommandId);

            stateMachine.saveErrorToHistory("Failed to remove rule",
                    format("Failed to remove the rule: commandId %s, switch %s, cookie %s. Error %s",
                            failedCommandId, errorResponse.getSwitchId(), response.getCookie(), errorResponse));

            stateMachine.addFailedCommand(failedCommandId, errorResponse);

            if (stateMachine.getPendingCommands().isEmpty()) {
                String errorMessage = format("Received error response(s) for %d remove commands",
                        stateMachine.getFailedCommands().size());
                stateMachine.saveErrorToHistory(errorMessage);
                stateMachine.fire(Event.RULES_REMOVED);
            }
        }
    }
}
