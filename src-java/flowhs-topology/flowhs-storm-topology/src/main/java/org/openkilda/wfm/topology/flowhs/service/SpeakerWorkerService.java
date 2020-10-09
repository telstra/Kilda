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
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.wfm.error.PipelineException;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SpeakerWorkerService {
    private final SpeakerCommandCarrier carrier;

    private final Map<String, FlowSegmentRequest> keyToRequest = new HashMap<>();
    private final Map<String, Long> keyToSendTime = new HashMap<>();

    public SpeakerWorkerService(SpeakerCommandCarrier carrier) {
        this.carrier = carrier;
    }

    /**
     * Sends command to speaker.
     * @param key unique operation's key.
     * @param command command to be executed.
     */
    public void sendCommand(String key, FlowSegmentRequest command) throws PipelineException {
        log.debug("Got a request from hub bolt {}", command);
        keyToRequest.put(key, command);
        keyToSendTime.put(key, System.currentTimeMillis());
        carrier.sendCommand(key, command);
    }

    /**
     * Processes received response and forwards it to the hub component.
     * @param key operation's key.
     * @param response response payload.
     */
    public void handleResponse(String key, SpeakerFlowSegmentResponse response)
            throws PipelineException {
        log.debug("Got a response from speaker {}", response);
        FlowSegmentRequest pendingRequest = keyToRequest.remove(key);
        Long receiveTime = keyToSendTime.remove(key);
        if (receiveTime == null) {
            log.error("HSTIME floodlight request send time non found for key " + key + " response " + response);
        } else {
            log.warn("HSTIME floodlight processing PLUS sending time " + (System.currentTimeMillis() - receiveTime));
        }
        if (pendingRequest != null) {
            if (pendingRequest.getCommandId().equals(response.getCommandId())) {
                carrier.sendResponse(key, response);
            } else {
                log.warn("Pending request's command id and received response's command id mismatch");
            }
        }
    }

    /**
     * Handles operation timeout.
     * @param key operation identifier.
     */
    public void handleTimeout(String key) throws PipelineException {
        FlowSegmentRequest failedRequest = keyToRequest.remove(key);

        SpeakerFlowSegmentResponse response = FlowErrorResponse.errorBuilder()
                .commandId(failedRequest.getCommandId())
                .switchId(failedRequest.getSwitchId())
                .metadata(failedRequest.getMetadata())
                .errorCode(ErrorCode.OPERATION_TIMED_OUT)
                .messageContext(failedRequest.getMessageContext())
                .build();
        carrier.sendResponse(key, response);
    }
}
