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

package org.openkilda.northbound.dto.v1.switches;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SwitchPropertiesDto {
    @JsonProperty("supported_transit_encapsulation")
    private List<String> supportedTransitEncapsulation;

    @JsonProperty("multi_table")
    private boolean multiTable;

    @JsonProperty("switch_lldp")
    private boolean switchLldp;

    @JsonProperty("inbound_telescope_port")
    private Integer inboundTelescopePort;

    @JsonProperty("outbound_telescope_port")
    private Integer outboundTelescopePort;

    @JsonProperty("telescope_ingress_vlan")
    private Integer telescopeIngressVlan;

    @JsonProperty("telescope_egress_vlan")
    private Integer telescopeEgressVlan;
}
