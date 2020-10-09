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
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@JsonIgnoreProperties({"switch_id", "egress_switch"})
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class OneSwitchFlowRequest extends IngressFlowSegmentBase {
    @JsonProperty("egress_endpoint")
    protected final FlowEndpoint egressEndpoint;

    protected OneSwitchFlowRequest(
            MessageContext context, UUID commandId, FlowSegmentMetadata metadata, FlowEndpoint endpoint,
            MeterConfig meterConfig, @NonNull FlowEndpoint egressEndpoint,
            RulesContext rulesContext, long sendTime) {
        super(context, commandId, metadata, endpoint, meterConfig, egressEndpoint.getSwitchId(),
                rulesContext, sendTime);

        if (! getSwitchId().equals(egressEndpoint.getSwitchId())) {
            throw new IllegalArgumentException(String.format(
                    "Ingress(%s) and egress(%s) switches must match in %s",
                    getSwitchId(), egressEndpoint.getSwitchId(), getClass().getName()));
        }

        this.egressEndpoint = egressEndpoint;
    }

    protected OneSwitchFlowRequest(@NonNull OneSwitchFlowRequest other, @NonNull UUID commandId) {
        this(
                other.messageContext, commandId, other.metadata, other.endpoint, other.meterConfig,
                other.egressEndpoint, other.rulesContext, other.sendTime);
    }
}
