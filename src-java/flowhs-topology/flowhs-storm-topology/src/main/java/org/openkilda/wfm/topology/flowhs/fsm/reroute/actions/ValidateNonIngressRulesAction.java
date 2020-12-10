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
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ValidateNonIngressRulesAction extends
        HistoryRecordingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final int speakerCommandRetriesLimit;

    public ValidateNonIngressRulesAction(int speakerCommandRetriesLimit) {
        this.speakerCommandRetriesLimit = speakerCommandRetriesLimit;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        Sample sample = Timer.start();
        try {
            SpeakerFlowSegmentResponse response = context.getSpeakerFlowResponse();
            UUID commandId = response.getCommandId();
            FlowSegmentRequestFactory command = stateMachine.getNonIngressCommands().get(commandId);
            if (!stateMachine.getPendingCommands().containsKey(commandId) || command == null) {
                log.info("Received a response for unexpected command: {}", response);
                return;
            }

            if (response.getRequestCreateTime() > 0) {
                Duration abs = Duration.between(Instant.ofEpochMilli(response.getRequestCreateTime()),
                        Instant.now()).abs();
                stateMachine.getMeterRegistry().timer("fsm.validate_command.roundtrip")
                        .record(abs.toNanos(), TimeUnit.NANOSECONDS);
            }

            if (response.getResponseCreateTime() > 0) {
                Duration abs = Duration.between(Instant.ofEpochMilli(response.getResponseCreateTime()),
                        Instant.now()).abs();
                stateMachine.getMeterRegistry().timer("floodlight.validate_command.in_transfer")
                        .record(abs.toNanos(), TimeUnit.NANOSECONDS);
            }

            if (response.getRouterPassTime() > 0) {
                Duration abs = Duration.between(Instant.ofEpochMilli(response.getRouterPassTime()),
                        Instant.now()).abs();
                stateMachine.getMeterRegistry().timer("floodlight.validate_command.router_hub_transfer")
                        .record(abs.toNanos(), TimeUnit.NANOSECONDS);
            }

            if (response.getWorkerPassTime() > 0) {
                Duration abs = Duration.between(Instant.ofEpochMilli(response.getWorkerPassTime()),
                        Instant.now()).abs();
                stateMachine.getMeterRegistry().timer("floodlight.validate_command.worker_hub_transfer")
                        .record(abs.toNanos(), TimeUnit.NANOSECONDS);
            }

            if (response.getTransferTime() > 0) {
                stateMachine.getMeterRegistry().timer("floodlight.validate_command.out_transfer")
                        .record(response.getTransferTime(), TimeUnit.NANOSECONDS);
            }

            if (response.getWaitTime() > 0) {
                stateMachine.getMeterRegistry().timer("floodlight.validate_command.wait")
                        .record(response.getWaitTime(), TimeUnit.NANOSECONDS);
            }

            if (response.getExecutionTime() > 0) {
                stateMachine.getMeterRegistry().timer("floodlight.validate_command.execution")
                        .record(response.getExecutionTime(), TimeUnit.NANOSECONDS);
            }

            if (response.isSuccess()) {
                stateMachine.getPendingCommands().remove(commandId);

                stateMachine.saveActionToHistory("Rule was validated",
                        format("The non ingress rule has been validated successfully: switch %s, cookie %s",
                                command.getSwitchId(), command.getCookie()));
            } else {
                FlowErrorResponse errorResponse = (FlowErrorResponse) response;

                int retries = stateMachine.getRetriedCommands().getOrDefault(commandId, 0);
                if (retries < speakerCommandRetriesLimit
                        && errorResponse.getErrorCode() != FlowErrorResponse.ErrorCode.MISSING_OF_FLOWS) {
                    stateMachine.getRetriedCommands().put(commandId, ++retries);

                    stateMachine.saveErrorToHistory("Rule validation failed", format(
                            "Failed to validate non ingress rule: commandId %s, switch %s, cookie %s. Error %s. "
                                    + "Retrying (attempt %d)",
                            commandId, errorResponse.getSwitchId(), command.getCookie(), errorResponse, retries));

                    stateMachine.getCarrier().sendSpeakerRequest(command.makeVerifyRequest(commandId));
                } else {
                    stateMachine.getPendingCommands().remove(commandId);

                    stateMachine.saveErrorToHistory("Rule validation failed",
                            format("Failed to validate non ingress rule: commandId %s, switch %s, cookie %s. Error %s",
                                    commandId, errorResponse.getSwitchId(), command.getCookie(), errorResponse));

                    stateMachine.getFailedValidationResponses().put(commandId, response);
                }
            }

            if (stateMachine.getPendingCommands().isEmpty()) {
                if (stateMachine.getNoningressValidationTimer() != null) {
                    long duration = stateMachine.getNoningressValidationTimer().stop();
                    if (duration > 0) {
                        stateMachine.getMeterRegistry().timer("fsm.validate_noningress_rule.execution")
                                .record(duration, TimeUnit.NANOSECONDS);
                    }
                    stateMachine.setNoningressValidationTimer(null);
                }

                if (stateMachine.getFailedValidationResponses().isEmpty()) {
                    log.debug("Non ingress rules have been validated for flow {}", stateMachine.getFlowId());
                    stateMachine.fire(Event.RULES_VALIDATED);
                } else {
                    stateMachine.saveErrorToHistory(format(
                            "Found missing rules or received error response(s) on %d validation commands",
                            stateMachine.getFailedValidationResponses().size()));
                    stateMachine.fire(Event.MISSING_RULE_FOUND);
                }
            }
        } finally {
            sample.stop(stateMachine.getMeterRegistry().timer("fsm.validate_noningress_rules"));
        }
    }
}
