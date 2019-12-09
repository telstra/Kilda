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

import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class InstallExclusionRequest extends ExclusionRequest {

    // If value is 0, the rule will be permanent.
    @JsonProperty("timeout")
    private int expirationTimeout;

    @Builder
    @JsonCreator
    public InstallExclusionRequest(@JsonProperty("switch_id") SwitchId switchId,
                                   @JsonProperty("cookie") long cookie,
                                   @JsonProperty("metadata") Metadata metadata,
                                   @JsonProperty("src_ip") String srcIp,
                                   @JsonProperty("src_port") Integer srcPort,
                                   @JsonProperty("dst_ip") String dstIp,
                                   @JsonProperty("dst_port") Integer dstPort,
                                   @JsonProperty("proto") String proto,
                                   @JsonProperty("eth_type") String ethType,
                                   @JsonProperty("timeout") int expirationTimeout) {
        super(switchId, cookie, metadata, srcIp, srcPort, dstIp, dstPort, proto, ethType);
        this.expirationTimeout = expirationTimeout;
    }
}
