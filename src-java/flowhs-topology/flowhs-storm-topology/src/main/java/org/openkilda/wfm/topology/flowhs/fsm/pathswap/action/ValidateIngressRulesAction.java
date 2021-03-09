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

package org.openkilda.wfm.topology.flowhs.fsm.pathswap.action;

import static java.lang.String.format;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapContext;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.State;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ValidateIngressRulesAction extends
        HistoryRecordingAction<FlowPathSwapFsm, State, Event, FlowPathSwapContext> {
    private final int speakerCommandRetriesLimit;

    public ValidateIngressRulesAction(int speakerCommandRetriesLimit) {
        this.speakerCommandRetriesLimit = speakerCommandRetriesLimit;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowPathSwapContext context,
                           FlowPathSwapFsm stateMachine) {
        SpeakerFlowSegmentResponse response = context.getSpeakerFlowResponse();
        UUID commandId = response.getCommandId();
        FlowSegmentRequestFactory command = stateMachine.getIngressCommands().get(commandId);
        if (!stateMachine.getPendingCommands().containsKey(commandId) || command == null) {
            log.info("Received a response for unexpected command: {}", response);
            return;
        }

        if (response.isSuccess()) {
            stateMachine.removePendingCommand(commandId);
            stateMachine.saveActionToHistory("Rule was validated",
                    format("The ingress rule has been validated successfully: switch %s, cookie %s",
                            command.getSwitchId(), command.getCookie()));
        } else {
            FlowErrorResponse errorResponse = (FlowErrorResponse) response;

            int attempt = stateMachine.doRetryForCommand(commandId);
            if (attempt <= speakerCommandRetriesLimit
                    && errorResponse.getErrorCode() != FlowErrorResponse.ErrorCode.MISSING_OF_FLOWS) {
                stateMachine.saveErrorToHistory("Rule validation failed", format(
                        "Failed to validate the ingress rule: commandId %s, switch %s, cookie %s. Error %s. "
                                + "Retrying (attempt %d)",
                        commandId, errorResponse.getSwitchId(), command.getCookie(), errorResponse, attempt));

                stateMachine.getCarrier().sendSpeakerRequest(command.makeInstallRequest(commandId));
            } else {
                stateMachine.removePendingCommand(commandId);

                stateMachine.saveErrorToHistory("Rule validation failed",
                        format("Failed to validate the ingress rule: commandId %s, switch %s, cookie %s. Error %s",
                                commandId, errorResponse.getSwitchId(), command.getCookie(), errorResponse));

                stateMachine.getFailedValidationResponses().put(commandId, response);
            }
        }

        if (stateMachine.getPendingCommands().isEmpty()) {
            if (stateMachine.getFailedValidationResponses().isEmpty()) {
                log.debug("Ingress rules have been validated for flow {}", stateMachine.getFlowId());
                stateMachine.fire(Event.RULES_VALIDATED);
            } else {
                stateMachine.saveErrorToHistory(format(
                        "Found missing rules or received error response(s) on %d validation commands",
                        stateMachine.getFailedValidationResponses().size()));
                stateMachine.fire(Event.MISSING_RULE_FOUND);
            }
        }
    }
}
