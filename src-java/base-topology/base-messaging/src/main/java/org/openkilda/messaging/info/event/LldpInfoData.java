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
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(SnakeCaseStrategy.class)
public class LldpInfoData extends ConnectedDevicePacketBase {

    private static final long serialVersionUID = 7963516610743491465L;

    @JsonProperty("chassis_id")
    private String chassisId;

    @JsonProperty("port_id")
    private String portId;

    @JsonProperty("ttl")
    private Integer ttl;

    @JsonProperty("port_description")
    private String portDescription;

    @JsonProperty("system_name")
    private String systemName;

    @JsonProperty("system_description")
    private String systemDescription;

    @JsonProperty("system_capabilities")
    private String systemCapabilities;

    @JsonProperty("management_address")
    private String managementAddress;

    @JsonCreator
    public LldpInfoData(@JsonProperty("switch_id") SwitchId switchId,
                              @JsonProperty("port_number") int portNumber,
                              @JsonProperty("vlans") List<Integer> vlans,
                              @JsonProperty("vni") Integer vni,
                              @JsonProperty("cookie") long cookie,
                              @JsonProperty("mac_address") String macAddress,
                              @JsonProperty("chassis_id") String chassisId,
                              @JsonProperty("port_id") String portId,
                              @JsonProperty("ttl") Integer ttl,
                              @JsonProperty("port_description") String portDescription,
                              @JsonProperty("system_name") String systemName,
                              @JsonProperty("system_description") String systemDescription,
                              @JsonProperty("system_capabilities") String systemCapabilities,
                              @JsonProperty("management_address") String managementAddress) {
        super(switchId, portNumber, vlans, vni, cookie, macAddress);
        this.chassisId = chassisId;
        this.portId = portId;
        this.ttl = ttl;
        this.portDescription = portDescription;
        this.systemName = systemName;
        this.systemDescription = systemDescription;
        this.systemCapabilities = systemCapabilities;
        this.managementAddress = managementAddress;
    }
}
