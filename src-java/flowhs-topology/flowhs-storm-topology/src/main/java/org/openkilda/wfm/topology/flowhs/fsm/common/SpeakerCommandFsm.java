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

package org.openkilda.wfm.topology.flowhs.fsm.common;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.messaging.MessageContext;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.flowhs.fsm.common.SpeakerCommandFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.common.SpeakerCommandFsm.State;
import org.openkilda.wfm.topology.flowhs.service.FlowCreateHubCarrier;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Getter
public final class SpeakerCommandFsm
        extends WithCommandContextFsm<SpeakerCommandFsm, State, Event, SpeakerFlowSegmentResponse> {

    private final FlowSegmentRequest request;
    private final FlowCreateHubCarrier carrier;
    private final Set<ErrorCode> giveUpErrors = new HashSet<>();
    private final int allowedRetriesCount;
    private int remainingRetries;

    private SpeakerCommandFsm(
            FlowSegmentRequest request, FlowCreateHubCarrier carrier, Set<ErrorCode> giveUpErrors,
            Integer retriesLimit) {
        super(new CommandContext(request.getMessageContext().getCorrelationId()));
        this.request = request;
        this.carrier = carrier;
        this.giveUpErrors.addAll(giveUpErrors);
        this.allowedRetriesCount = this.remainingRetries = retriesLimit;
    }

    protected void processResponse(State from, State to, Event event, SpeakerFlowSegmentResponse response) {
        if (response.isSuccess()) {
            log.debug("Successfully executed the command {}", response);
            fire(Event.NEXT);
        } else {
            FlowErrorResponse errorResponse = (FlowErrorResponse) response;
            final ErrorCode errorCode = errorResponse.getErrorCode();

            String giveUpReason = null;
            if (remainingRetries <= 0) {
                giveUpReason = String.format("all %s retry attempts have been used", allowedRetriesCount);
            } else if (giveUpErrors.contains(errorCode)) {
                giveUpReason = String.format(
                        "Error %s is in \"give up\" list, no more retry attempts allowed", errorCode);
            }

            if (giveUpReason == null) {
                remainingRetries--;
                log.debug("About to retry execution of the command {}", response);
                fire(Event.RETRY);
            } else {
                fireError(giveUpReason);
            }
        }
    }

    protected void sendCommand(State from, State to, Event event, SpeakerFlowSegmentResponse response) {
        log.debug("Sending a flow command {} to a speaker", request);
        // FIXME(surabujin): new commandId must be used for each retry attempt, because in case of timeout new and
        //  ongoing requests can collide on speaker side and there is no way to distinguish responses (stale and actual)
        //  if both of them have same commandId

        Instant now = Instant.now();
        log.error("Stuck in FSM {}", Duration.between(Instant.ofEpochMilli(request.getMessageContext().getCreateTime()),
                now));

        request.setMessageContext(new MessageContext(request.getMessageContext().getCorrelationId(),
                now.toEpochMilli()));
        carrier.sendSpeakerRequest(request);
    }

    @Override
    public void fireNext(SpeakerFlowSegmentResponse context) {
        fire(Event.NEXT);
    }

    @Override
    public void fireError(String errorReason) {
        log.info("Failed to execute the flow command {}", errorReason);
        fire(Event.ERROR);
    }

    public static Builder getBuilder(FlowCreateHubCarrier carrier, int retriesLimit) {
        return new Builder(carrier, retriesLimit);
    }

    public enum Event {
        ACTIVATE,
        NEXT,
        REPLY,
        RETRY,
        TIMEOUT,
        ERROR
    }

    public enum State {
        INIT,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        SUCCESS
    }

    public static final class Builder {
        private final FlowCreateHubCarrier carrier;
        private final int retriesLimit;
        private final StateMachineBuilder<SpeakerCommandFsm, State, Event, SpeakerFlowSegmentResponse> builder;

        private Builder(FlowCreateHubCarrier carrier, int retriesLimit) {
            this.carrier = carrier;
            this.retriesLimit = retriesLimit;

            builder = StateMachineBuilderFactory.create(
                    SpeakerCommandFsm.class, State.class, Event.class, SpeakerFlowSegmentResponse.class,
                    // extra params
                    FlowSegmentRequest.class, FlowCreateHubCarrier.class, Set.class, Integer.class
            );

            builder.transition()
                    .from(State.INIT)
                    .to(State.IN_PROGRESS)
                    .on(Event.ACTIVATE);

            builder.onEntry(State.IN_PROGRESS)
                    .callMethod("sendCommand");

            builder.transition()
                    .from(State.IN_PROGRESS)
                    .to(State.COMPLETED)
                    .on(Event.REPLY);

            builder.onEntry(State.COMPLETED)
                    .callMethod("processResponse");

            builder.transitions()
                    .from(State.COMPLETED)
                    .toAmong(State.SUCCESS, State.IN_PROGRESS, State.FAILED)
                    .onEach(Event.NEXT, Event.RETRY, Event.ERROR);

            builder.defineFinalState(State.SUCCESS);
            builder.defineFinalState(State.FAILED);
        }

        public SpeakerCommandFsm newInstance(Set<ErrorCode> giveUpErrors, FlowSegmentRequest request) {
            return builder.newStateMachine(State.INIT, request, carrier, giveUpErrors, retriesLimit);
        }
    }
}
