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

package org.openkilda.floodlight.api.request;

import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.Cookie;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public abstract class FlowSegmentRequest extends SpeakerRequest {
    @JsonProperty("metadata")
    protected final FlowSegmentMetadata metadata;

    public FlowSegmentRequest(
            MessageContext context, SwitchId switchId, UUID commandId, @NonNull FlowSegmentMetadata metadata,
            long sendTime) {
        super(context, switchId, commandId, sendTime);
        this.metadata = metadata;
    }

    @JsonIgnore
    public Cookie getCookie() {
        return metadata.getCookie();
    }

    @JsonIgnore
    public String getFlowId() {
        return metadata.getFlowId();
    }

    /**
     * Detect is the provided request is an install request.
     */
    @JsonIgnore
    public boolean isInstallRequest() {
        return false;
    }

    /**
     * Detect is the provided request is a remove request.
     */
    @JsonIgnore
    public boolean isRemoveRequest() {
        return false;
    }

    /**
     * Detect is the provided request is a verify request.
     */
    @JsonIgnore
    public boolean isVerifyRequest() {
        return false;
    }
}
