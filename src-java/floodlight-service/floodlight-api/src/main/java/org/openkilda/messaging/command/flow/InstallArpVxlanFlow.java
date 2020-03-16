/* Copyright 2020 Telstra Open Source
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

package org.openkilda.messaging.command.flow;

import static java.lang.String.format;
import static org.openkilda.messaging.Utils.FLOW_ID;
import static org.openkilda.messaging.Utils.TRANSACTION_ID;

import org.openkilda.model.Cookie;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.UUID;

@Value
@EqualsAndHashCode(callSuper = true)
@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstallArpVxlanFlow extends ConnectedDeviceVxlanFlowBase {

    @JsonCreator
    public InstallArpVxlanFlow(@JsonProperty(TRANSACTION_ID) final UUID transactionId,
                               @JsonProperty(FLOW_ID) final String id,
                               @JsonProperty("cookie") final Long cookie,
                               @JsonProperty("switch_id") final SwitchId switchId,
                               @JsonProperty("input_port") final Integer inputPort,
                               @JsonProperty("output_port") final Integer outputPort,
                               @JsonProperty("input_vlan_id") final Integer inputVlanId,
                               @JsonProperty("transit_encapsulation_id") final Integer transitEncapsulationId,
                               @JsonProperty("meter_id") final Long meterId,
                               @JsonProperty("egress_switch_id") SwitchId egressSwitchId)  {
        super(transactionId, id, cookie, switchId, inputPort, outputPort, inputVlanId, transitEncapsulationId, meterId,
                egressSwitchId);
        if (!Cookie.isMaskedAsArpVxlan(cookie)) {
            throw new IllegalArgumentException(format("Invalid cookie %s for InstallArpVxlanFlow command", cookie));
        }
    }
}
