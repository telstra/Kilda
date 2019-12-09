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

package org.openkilda.messaging.command.switches;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class InstallTelescopeRuleRequest extends BaseTelescopeRuleRequest {

    @JsonProperty("telescope_port")
    private int telescopePort;

    @JsonProperty("telescope_vlan")
    private int telescopeVlan;

    @JsonProperty("flow_tunnel_id")
    private long flowTunnelId;

    @JsonProperty("transit_encapsulation_type")
    private FlowEncapsulationType flowEncapsulationType;

    @JsonProperty("src_switch_id")
    private SwitchId srcSwitchId;

    @JsonProperty("dst_switch_id")
    private SwitchId dstSwitchId;

    @Builder
    @JsonCreator
    public InstallTelescopeRuleRequest(@JsonProperty("switch_id") SwitchId switchId,
                                       @JsonProperty("telescope_cookie") long telescopeCookie,
                                       @JsonProperty("metadata") Metadata metadata,
                                       @JsonProperty("flow_tunnel_id") long flowTunnelId,
                                       @JsonProperty("transit_encapsulation_type")
                                                   FlowEncapsulationType flowEncapsulationType,
                                       @JsonProperty("telescope_port") int telescopePort,
                                       @JsonProperty("telescope_vlan") int telescopeVlan,
                                       @JsonProperty("src_switch_id") SwitchId srcSwitchId,
                                       @JsonProperty("dst_switch_id") SwitchId dstSwitchId) {
        super(switchId, telescopeCookie, metadata);
        this.telescopePort = telescopePort;
        this.telescopeVlan = telescopeVlan;
        this.flowEncapsulationType = flowEncapsulationType;
        this.flowTunnelId = flowTunnelId;
        this.srcSwitchId = srcSwitchId;
        this.dstSwitchId = dstSwitchId;
    }
}
