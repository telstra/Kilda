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
import lombok.Value;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Value
@EqualsAndHashCode(callSuper = true)
public class FlowEndpoint extends NetworkEndpoint {
    @JsonProperty("outer_vlan_id")
    private final int outerVlanId;

    @JsonProperty("inner_vlan_id")
    private final int innerVlanId;

    @JsonProperty("track_lldp_connected_devices")
    private final boolean trackLldpConnectedDevices;

    @JsonProperty("track_arp_connected_devices")
    private final boolean trackArpConnectedDevices;

    public FlowEndpoint(SwitchId switchId, int portNumber) {
        this(switchId, portNumber, 0);
    }

    public FlowEndpoint(SwitchId switchId, int portNumber, int outerVlanId) {
        this(switchId, portNumber, outerVlanId, 0, false, false);
    }

    public FlowEndpoint(SwitchId switchId, int portNumber, int outerVlanId, int innerVlanId) {
        this(switchId, portNumber, outerVlanId, innerVlanId, false, false);
    }

    @JsonCreator
    @Builder(toBuilder = true)
    public FlowEndpoint(
            @JsonProperty("switch_id") SwitchId switchId,
            @JsonProperty("port_number") Integer portNumber,
            @JsonProperty("outer_vlan_id") int outerVlanId,
            @JsonProperty("inner_vlan_id") int innerVlanId,
            @JsonProperty("track_lldp_connected_devices") boolean trackLldpConnectedDevices,
            @JsonProperty("track_arp_connected_devices") boolean trackArpConnectedDevices) {
        super(switchId, portNumber);

        this.trackLldpConnectedDevices = trackLldpConnectedDevices;
        this.trackArpConnectedDevices = trackArpConnectedDevices;

        // normalize VLANs representation
        List<Integer> vlanStack = makeVlanStack(innerVlanId, outerVlanId);
        if (1 < vlanStack.size()) {
            this.outerVlanId = vlanStack.get(1);
            this.innerVlanId = vlanStack.get(0);
        } else if (!vlanStack.isEmpty()) {
            this.outerVlanId = vlanStack.get(0);
            this.innerVlanId = 0;
        } else {
            this.outerVlanId = 0;
            this.innerVlanId = 0;
        }
    }

    @JsonIgnore
    public List<Integer> getVlanStack() {
        return makeVlanStack(innerVlanId, outerVlanId);
    }

    /**
     * Scan provided sequence for valid VLAN IDs and return them as a list.
     */
    public static List<Integer> makeVlanStack(Integer... sequence) {
        return Stream.of(sequence)
                .filter(FlowEndpoint::isVlanIdSet)
                .collect(Collectors.toList());
    }

    public static boolean isVlanIdSet(Integer vlanId) {
        return vlanId != null && 0 < vlanId;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(64)
                .append("switchId=\"").append(switchId)
                .append("\" port=").append(portNumber);
        if (isVlanIdSet(outerVlanId)) {
            builder.append(" vlanId=").append(outerVlanId);
        }
        if (isVlanIdSet(innerVlanId)) {
            builder.append(" innerVlanId=").append(innerVlanId);
        }
        return builder.toString();
    }

    public boolean isSwitchPortEquals(FlowEndpoint other) {
        return switchId.equals(other.switchId)
                && portNumber.equals(other.portNumber);
    }

    public boolean isSwitchPortVlanEquals(FlowEndpoint other) {
        return isSwitchPortEquals(other)
                && Objects.equals(getVlanStack(), other.getVlanStack());
    }
}
