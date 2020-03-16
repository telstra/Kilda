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

package org.openkilda.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

@Value
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class FlowEndpoint extends NetworkEndpoint {
    @JsonProperty("outer_vlan_id")
    private final int vlanId;

    @JsonProperty("track_lldp_connected_devices")
    private final boolean trackLldpConnectedDevices;

    @JsonProperty("track_arp_connected_devices")
    private final boolean trackArpConnectedDevices;

    public FlowEndpoint(SwitchId switchId, Integer portNumber) {
        this(switchId, portNumber, 0);
    }

    public FlowEndpoint(SwitchId switchId, Integer portNumber, int vlanId) {
        this(switchId, portNumber, vlanId, false, false);
    }

    @JsonCreator
    @Builder(toBuilder = true)
    public FlowEndpoint(
            @JsonProperty("switch_id") SwitchId switchId,
            @JsonProperty("port_number") Integer portNumber,
            @JsonProperty("outer_vlan_id") int vlanId,
            @JsonProperty("track_lldp_connected_devices") boolean trackLldpConnectedDevices,
            @JsonProperty("track_arp_connected_devices") boolean trackArpConnectedDevices) {
        super(switchId, portNumber);
        this.vlanId = vlanId;
        this.trackLldpConnectedDevices = trackLldpConnectedDevices;
        this.trackArpConnectedDevices = trackArpConnectedDevices;
    }

    @JsonIgnore
    public boolean isVlanIdSet() {
        return isVlanIdSet(vlanId);
    }

    public static boolean isVlanIdSet(Integer vlanId) {
        return vlanId != null && 0 < vlanId;
    }
}
