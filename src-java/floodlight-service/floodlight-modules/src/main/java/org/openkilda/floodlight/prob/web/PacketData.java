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

package org.openkilda.floodlight.prob.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@JsonNaming(value = SnakeCaseStrategy.class)
@ToString
public class PacketData {
    String srcSwitch;
    String dstSwitch;
    int outPort;
    int vlan;
    String srcIpv4;
    String dstIpv4;
    int ipProto;
    int l4SrcPort;
    int l4DstPort;

    @JsonCreator
    @Builder(toBuilder = true)
    public PacketData(
            @JsonProperty("src_switch") String srcSwitch,
            @JsonProperty("dst_switch") String dstSwitch,
            @JsonProperty("out_port") int outPort,
            @JsonProperty("vlan") int vlan,
            @JsonProperty("src_ipv4") String srcIpv4,
            @JsonProperty("dst_ipv4") String dstIpv4,
            @JsonProperty("ip_proto") int ipProto,
            @JsonProperty("l4_src_port") int l4SrcPort,
            @JsonProperty("l4_dst_port") int l4DstPort) {
        this.srcSwitch = srcSwitch;
        this.dstSwitch = dstSwitch;
        this.outPort = outPort;
        this.vlan = vlan;
        this.srcIpv4 = srcIpv4;
        this.dstIpv4 = dstIpv4;
        this.ipProto = ipProto;
        this.l4SrcPort = l4SrcPort;
        this.l4DstPort = l4DstPort;
    }
}
