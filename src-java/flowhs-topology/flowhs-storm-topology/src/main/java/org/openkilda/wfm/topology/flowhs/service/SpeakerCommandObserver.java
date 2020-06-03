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

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.wfm.topology.flowhs.fsm.common.SpeakerCommandFsm;
import org.openkilda.wfm.topology.flowhs.fsm.common.SpeakerCommandFsm.Event;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

@Slf4j
public class SpeakerCommandObserver {

    private final SpeakerCommandFsm commandExecutor;

    public SpeakerCommandObserver(SpeakerCommandFsm.Builder builder, FlowSegmentRequest request) {
        this(builder, Collections.emptySet(), request);
    }

    public SpeakerCommandObserver(
            SpeakerCommandFsm.Builder builder, Set<ErrorCode> giveUpErrors, FlowSegmentRequest request) {
        Instant start = Instant.now();
        commandExecutor = builder.newInstance(giveUpErrors, request);
        log.error("SpeakerCommandObserver - Constructor {}", Duration.between(start, Instant.now()));
    }

    /**
     * Starts execution of speaker command: sends a command and waits for a response from a speaker.
     */
    public void start() {
        Instant start = Instant.now();
        log.error("Wait in FSM before start {}",
                Duration.between(Instant.ofEpochMilli(commandExecutor.getRequest().getMessageContext().getCreateTime()),
                        start));
        commandExecutor.start();
        Instant start2 = Instant.now();
        commandExecutor.fire(Event.ACTIVATE);
        log.error("SpeakerCommandObserver - start {} / {}", Duration.between(start, start2),
                Duration.between(start2, Instant.now()));
    }

    /**
     * Processes a response. If command wasn't executed successfully then it will be retried if limit is not exceeded.
     *
     * @param response a response from a speaker.
     */
    public void handleResponse(SpeakerFlowSegmentResponse response) {
        Instant start = Instant.now();
        commandExecutor.fire(Event.REPLY, response);
        log.error("SpeakerCommandObserver - handleResponse {}", Duration.between(start, Instant.now()));
    }

    /**
     * Tells whether command execution is completed.
     */
    public boolean isFinished() {
        return commandExecutor.isTerminated();
    }
}
