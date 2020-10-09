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
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@JsonIgnoreProperties({"switch_id"})
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public abstract class EgressFlowSegmentRequest extends FlowSegmentRequest {
    @JsonProperty("endpoint")
    protected final FlowEndpoint endpoint;

    @JsonProperty("ingress_endpoint")
    protected final FlowEndpoint ingressEndpoint;

    @JsonProperty("isl_port")
    protected final int islPort;

    @JsonProperty("encapsulation")
    protected final FlowTransitEncapsulation encapsulation;

    protected EgressFlowSegmentRequest(
            MessageContext messageContext, UUID commandId, FlowSegmentMetadata metadata,
            @NonNull FlowEndpoint endpoint, @NonNull FlowEndpoint ingressEndpoint, int islPort,
            @NonNull FlowTransitEncapsulation encapsulation, long sendTime) {
        super(messageContext, endpoint.getSwitchId(), commandId, metadata, sendTime);

        this.endpoint = endpoint;
        this.ingressEndpoint = ingressEndpoint;
        this.islPort = islPort;
        this.encapsulation = encapsulation;
    }

    protected EgressFlowSegmentRequest(@NonNull EgressFlowSegmentRequest other, @NonNull UUID commandId) {
        this(
                other.messageContext, commandId, other.metadata, other.endpoint,
                other.ingressEndpoint, other.islPort, other.encapsulation, other.sendTime);
    }
}
