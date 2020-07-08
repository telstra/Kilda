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

package org.openkilda.messaging.info.event;

import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArpInfoData extends ConnectedDevicePacketBase {

    private static final long serialVersionUID = 5558751248753354195L;

    @JsonProperty("ip_address")
    private String ipAddress;

    @JsonCreator
    public ArpInfoData(@JsonProperty("switch_id") SwitchId switchId,
                       @JsonProperty("port_number") int portNumber,
                       @JsonProperty("vlans") List<Integer> vlans,
                       @JsonProperty("vni") Integer vni,
                       @JsonProperty("cookie") long cookie,
                       @JsonProperty("mac_address") String macAddress,
                       @JsonProperty("ip_address") String ipAddress) {
        super(switchId, portNumber, vlans, vni, cookie, macAddress);
        this.ipAddress = ipAddress;
    }
}
