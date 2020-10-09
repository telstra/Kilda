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
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public abstract class TransitFlowSegmentRequest extends FlowSegmentRequest {
    @JsonProperty("ingress_isl_port")
    protected final int ingressIslPort;

    @JsonProperty("egress_isl_port")
    protected final int egressIslPort;

    @JsonProperty("encapsulation")
    protected final FlowTransitEncapsulation encapsulation;

    protected TransitFlowSegmentRequest(
            MessageContext context, SwitchId switchId, UUID commandId, FlowSegmentMetadata metadata,
            int ingressIslPort, int egressIslPort, @NonNull FlowTransitEncapsulation encapsulation, long sendTime) {
        super(context, switchId, commandId, metadata, sendTime);

        this.ingressIslPort = ingressIslPort;
        this.egressIslPort = egressIslPort;
        this.encapsulation = encapsulation;
    }

    protected TransitFlowSegmentRequest(@NonNull TransitFlowSegmentRequest other, @NonNull UUID commandId) {
        this(
                other.messageContext, other.switchId, commandId, other.metadata,
                other.ingressIslPort, other.egressIslPort, other.encapsulation, other.sendTime);
    }
}
