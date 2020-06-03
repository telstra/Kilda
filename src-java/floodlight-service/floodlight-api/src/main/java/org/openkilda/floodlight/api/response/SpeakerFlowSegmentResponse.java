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

package org.openkilda.floodlight.api.response;

import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.Cookie;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SpeakerFlowSegmentResponse extends SpeakerResponse {

    @JsonProperty("metadata")
    private final FlowSegmentMetadata metadata;

    @JsonProperty
    private final boolean success;

    private final long requestCreateTime;
    private final long executionTime;

    @JsonCreator
    @Builder
    public SpeakerFlowSegmentResponse(
            @JsonProperty("message_context") MessageContext messageContext,
            @JsonProperty("command_id") UUID commandId,
            @JsonProperty("switch_id") SwitchId switchId,
            @JsonProperty("metadata") @NonNull FlowSegmentMetadata metadata,
            @JsonProperty("success") boolean success,
            @JsonProperty("request_create_time") long requestCreateTime,
            @JsonProperty("execution_time") long executionTime) {
        super(messageContext, commandId, switchId);

        this.metadata = metadata;
        this.success = success;
        this.requestCreateTime = requestCreateTime;
        this.executionTime = executionTime;
    }

    @JsonIgnore
    public Cookie getCookie() {
        return metadata.getCookie();
    }
}
