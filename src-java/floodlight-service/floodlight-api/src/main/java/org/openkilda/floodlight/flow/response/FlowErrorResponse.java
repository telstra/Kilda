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

package org.openkilda.floodlight.flow.response;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
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
public class FlowErrorResponse extends SpeakerFlowSegmentResponse {

    @JsonProperty("error_code")
    private ErrorCode errorCode;

    @JsonProperty
    private String description;

    @JsonCreator
    @Builder(builderMethodName = "errorBuilder")
    public FlowErrorResponse(@JsonProperty("error_code") @NonNull ErrorCode errorCode,
                             @JsonProperty("description") String description,
                             @JsonProperty("message_context") MessageContext messageContext,
                             @JsonProperty("command_id") UUID commandId,
                             @JsonProperty("switch_id") SwitchId switchId,
                             @JsonProperty("metadata") FlowSegmentMetadata metadata,
                             @JsonProperty("request_create_time") long requestCreateTime,
                             @JsonProperty("execution_time") long executionTime) {
        super(messageContext, commandId, switchId, metadata, false,
                requestCreateTime, executionTime);

        this.description = description;
        this.errorCode = errorCode;
    }

    public enum ErrorCode {
        SWITCH_UNAVAILABLE,
        UNSUPPORTED,
        BAD_FLAGS,
        BAD_COMMAND,
        OPERATION_TIMED_OUT,
        MISSING_OF_FLOWS,
        UNKNOWN
    }
}
