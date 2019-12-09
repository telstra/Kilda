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

import org.openkilda.messaging.command.CommandData;
import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
public abstract class ExclusionRequest extends CommandData {

    @JsonProperty("switch_id")
    private SwitchId switchId;

    @JsonProperty("cookie")
    private long cookie;

    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("src_ip")
    private String srcIp;

    @JsonProperty("src_port")
    private Integer srcPort;

    @JsonProperty("dst_ip")
    private String dstIp;

    @JsonProperty("dst_port")
    private Integer dstPort;

    @JsonProperty("proto")
    private String proto;

    @JsonProperty("eth_type")
    private String ethType;
}
