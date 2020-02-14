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

package org.openkilda.floodlight.prob.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Value;

@Value
@JsonNaming(value = SnakeCaseStrategy.class)
public class ArpPacketData {
    String switchId;
    String srcMac;
    int outPort;
    int vlan;
    String srcIpv4;
    String dstIpv4;

    @JsonCreator
    @Builder(toBuilder = true)
    public ArpPacketData(
            @JsonProperty("switch_id") String switchId,
            @JsonProperty("src_mac") String srcMac,
            @JsonProperty("out_port") int outPort,
            @JsonProperty("vlan") int vlan,
            @JsonProperty("src_ipv4") String srcIpv4,
            @JsonProperty("dst_ipv4") String dstIpv4) {
        this.switchId = switchId;
        this.srcMac = srcMac;
        this.outPort = outPort;
        this.vlan = vlan;
        this.srcIpv4 = srcIpv4;
        this.dstIpv4 = dstIpv4;
    }
}
