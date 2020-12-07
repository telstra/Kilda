/* Copyright 2017 Telstra Open Source
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

import org.openkilda.messaging.info.InfoData;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Defines the payload payload of a Message representing a port info.
 */
@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@EqualsAndHashCode(of = {"switchId", "portNo", "maxCapacity", "state"}, callSuper = false)
public class PortInfoData extends InfoData {
    /**
     * Serialization version number constant.
     */
    private static final long serialVersionUID = 1L;

    @JsonProperty("rtrtkPortTest")
    private long rtrtkPortTest;

    /**
     * Switch id.
     */
    @JsonProperty("switch_id")
    private SwitchId switchId;

    /**
     * Port number.
     */
    @JsonProperty("port_no")
    private int portNo;
    /**
     * Maximum capacity.
     */
    @JsonProperty("max_capacity")
    private Integer maxCapacity;

    /**
     * Port state.
     */
    @JsonProperty("state")
    private PortChangeType state;

    @JsonProperty("enabled")
    private Boolean enabled;

    /**
     * Default constructor.
     */
    public PortInfoData() {
    }

    /**
     * Instance constructor.
     *
     * @param path path node
     */
    public PortInfoData(PathNode path) {
        this.switchId = path.getSwitchId();
        this.portNo = path.getPortNo();
        this.state = PortChangeType.OTHER_UPDATE;
        rtrtkPortTest = 1;
    }

    /**
     * Instance constructor.
     *
     * @param switchId switch id
     * @param portNo   port number
     * @param state    port state
     */
    public PortInfoData(final SwitchId switchId, final int portNo, final PortChangeType state) {
        this(switchId, portNo, null, state, null);
    }

    public PortInfoData(SwitchId switchId, int portNo, PortChangeType event, Boolean enabled) {
        this(switchId, portNo, null, event, enabled);
    }

    public PortInfoData(final SwitchId switchId,
                        final int portNo,
                        final Integer maxCapacity,
                        PortChangeType state) {
        this(switchId, portNo, maxCapacity, state, null);
    }

    /**
     * Instance constructor.
     *
     * @param switchId    switch id
     * @param portNo      port number
     * @param maxCapacity maximum capacity
     * @param state       port state
     */
    @JsonCreator
    public PortInfoData(@JsonProperty("switch_id") final SwitchId switchId,
                        @JsonProperty("port_no") final int portNo,
                        @JsonProperty("max_capacity") final Integer maxCapacity,
                        @JsonProperty("state") final PortChangeType state,
                        @JsonProperty("enabled") Boolean enabled) {
        this.switchId = switchId;
        this.portNo = portNo;
        this.maxCapacity = maxCapacity;
        this.state = state;
        this.enabled = enabled;
        rtrtkPortTest = 1;
    }
}
