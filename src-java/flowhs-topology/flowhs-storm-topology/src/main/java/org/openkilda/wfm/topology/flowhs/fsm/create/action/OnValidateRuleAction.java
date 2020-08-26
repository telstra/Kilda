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
import static org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode.MISSING_OF_FLOWS;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
abstract class OnValidateRuleAction extends OnReceivedResponseAction {
    public OnValidateRuleAction(PersistenceManager persistenceManager, int speakerCommandRetriesLimit) {
        super(persistenceManager, speakerCommandRetriesLimit);
    }

    @Override
    protected void handleSuccessResponse(FlowCreateFsm stateMachine, SpeakerFlowSegmentResponse response) {
        stateMachine.saveActionToHistory(
                "Rule was validated",
                format("Rule (%s) has been validated successfully: switch %s, cookie %s",
                        getRuleType(), response.getSwitchId(), response.getCookie()));
    }

    @Override
    protected boolean isRetryableError(FlowErrorResponse errorResponse) {
        return errorResponse.getErrorCode() != MISSING_OF_FLOWS && super.isRetryableError(errorResponse);
    }

    @Override
    protected void handleErrorAndRetry(FlowCreateFsm stateMachine, FlowSegmentRequestFactory command,
                                       UUID commandId, FlowErrorResponse errorResponse, int retries) {
        stateMachine.saveErrorToHistory("Failed to validate rule", format(
                "Failed to validate the rule: commandId %s, switch %s, cookie %s. Error %s. "
                        + "Retrying (attempt %d)",
                commandId, errorResponse.getSwitchId(), command.getCookie(), errorResponse, retries));

        stateMachine.getCarrier().sendSpeakerRequest(command.makeVerifyRequest(commandId));
    }

    @Override
    protected void handleErrorResponse(FlowCreateFsm stateMachine, FlowErrorResponse response) {
        stateMachine.saveErrorToHistory(
                "Rule validation failed",
                format("Rule (%s) is missing or invalid: switch %s, cookie %s - %s",
                        getRuleType(), response.getSwitchId(), response.getCookie(),
                        formatErrorResponse(response)));
    }

    @Override
    protected void onComplete(FlowCreateFsm stateMachine, FlowCreateContext context) {
        if (stateMachine.getIngressValidationTimer() != null) {
            long duration = stateMachine.getIngressValidationTimer().stop();
            if (duration > 0) {
                stateMachine.getMeterRegistry().timer("fsm.validate_ingress_rule.execution")
                        .record(duration, TimeUnit.NANOSECONDS);
            }
            stateMachine.setIngressValidationTimer(null);
        }

        if (stateMachine.getNoningressValidationTimer() != null) {
            long duration = stateMachine.getNoningressValidationTimer().stop();
            if (duration > 0) {
                stateMachine.getMeterRegistry().timer("fsm.validate_noningress_rule.execution")
                        .record(duration, TimeUnit.NANOSECONDS);
            }
            stateMachine.setNoningressValidationTimer(null);
        }

        log.debug("Rules ({}) have been validated for flow {}", getRuleType(), stateMachine.getFlowId());
        stateMachine.fireNext(context);
    }

    @Override
    protected void onCompleteWithErrors(FlowCreateFsm stateMachine, FlowCreateContext context) {
        String errorMessage = format(
                "Found missing rules (%s) or received error response(s) on validation commands", getRuleType());
        stateMachine.saveErrorToHistory(errorMessage);
        stateMachine.fireError(errorMessage);
    }

    protected abstract String getRuleType();

    protected String formatErrorResponse(SpeakerFlowSegmentResponse response) {
        if (response instanceof FlowErrorResponse) {
            return formatErrorResponse((FlowErrorResponse) response);
        }
        return response.toString();
    }

    private String formatErrorResponse(FlowErrorResponse errorResponse) {
        return String.format("%s %s", errorResponse.getErrorCode(), errorResponse.getDescription());
    }
}
