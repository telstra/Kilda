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

import static org.openkilda.messaging.Utils.FLOW_ID;
import static org.openkilda.messaging.Utils.TRANSACTION_ID;

import org.openkilda.model.MacAddress;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.UUID;

@Value
@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(callSuper = true)
public class InstallServer42Flow extends BaseInstallFlow {
    private static final long serialVersionUID = -5129567926339150982L;

    @JsonProperty("server_42_mac_address")
    protected MacAddress server42MacAddress;

    @Builder
    @JsonCreator
    public InstallServer42Flow(@JsonProperty(TRANSACTION_ID) UUID transactionId,
                               @JsonProperty(FLOW_ID) String id,
                               @JsonProperty("cookie") Long cookie,
                               @JsonProperty("switch_id") SwitchId switchId,
                               @JsonProperty("input_port") Integer inputPort,
                               @JsonProperty("output_port") Integer outputPort,
                               @JsonProperty("multi_table") boolean multiTable,
                               @JsonProperty("server_42_mac_address") MacAddress server42MacAddress) {
        super(transactionId, id, cookie, switchId, inputPort, outputPort, multiTable);
        this.server42MacAddress = server42MacAddress;
    }
}
