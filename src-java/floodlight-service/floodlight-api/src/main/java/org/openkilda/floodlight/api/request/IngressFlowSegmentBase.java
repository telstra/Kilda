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
abstract class IngressFlowSegmentBase extends FlowSegmentRequest {
    @JsonProperty("endpoint")
    protected final FlowEndpoint endpoint;

    @JsonProperty("meter_config")
    protected final MeterConfig meterConfig;

    @JsonProperty("egress_switch")
    protected final SwitchId egressSwitchId;

    @JsonProperty("rules_context")
    protected final RulesContext rulesContext;

    IngressFlowSegmentBase(
            MessageContext context, UUID commandId, FlowSegmentMetadata metadata, @NonNull FlowEndpoint endpoint,
            MeterConfig meterConfig, @NonNull SwitchId egressSwitchId,
            RulesContext rulesContext) {
        super(context, endpoint.getSwitchId(), commandId, metadata);

        this.endpoint = endpoint;
        this.meterConfig = meterConfig;
        this.egressSwitchId = egressSwitchId;
        this.rulesContext = rulesContext;
    }
}
