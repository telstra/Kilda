/* Copyright 2018 Telstra Open Source
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

package org.openkilda.messaging.info.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.io.Serializable;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FlowMatchField implements Serializable {

    @JsonProperty("eth_src")
    private String ethSrc;
    @JsonProperty("eth_dst")
    private String ethDst;
    @JsonProperty("eth_type")
    private String ethType;
    @JsonProperty("ip_proto")
    private String ipProto;
    @JsonProperty("udp_src")
    private String udpSrc;
    @JsonProperty("udp_dst")
    private String udpDst;
    @JsonProperty("in_port")
    private String inPort;
    @JsonProperty("vlan_vid")
    private String vlanVid;
    @JsonProperty("tunnel_id")
    private String tunnelId;
    @JsonProperty("metadata")
    private Long metadata;

    @JsonCreator
    public FlowMatchField(
            @JsonProperty("eth_src") String ethSrc, @JsonProperty("eth_dst") String ethDst,
            @JsonProperty("eth_type") String ethType, @JsonProperty("ip_proto") String ipProto,
            @JsonProperty("udp_src") String udpSrc, @JsonProperty("udp_dst") String udpDst,
            @JsonProperty("in_port") String inPort, @JsonProperty("vlan_vid") String vlanVid,
            @JsonProperty("tunnel_id") String tunnelId, @JsonProperty("metadata") Long metadata) {
        this.ethSrc = ethSrc;
        this.ethDst = ethDst;
        this.ethType = ethType;
        this.ipProto = ipProto;
        this.udpSrc = udpSrc;
        this.udpDst = udpDst;
        this.inPort = inPort;
        this.vlanVid = vlanVid;
        this.tunnelId = tunnelId;
        this.metadata = metadata;
    }
}
