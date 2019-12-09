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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * Represents information about a metadata.
 * Uses 64 bit to encode information about the packet:
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            Encapsulation ID           |D|L|O| Reserved Prefix |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          Reserved Prefix                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * <p>
 * L - flag indicates LLDP packet
 * O - flag indicates packet received by one switch flow
 * D - flag indicates direction
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(SnakeCaseStrategy.class)

public class Metadata implements Serializable {
    private static final long serialVersionUID = 5505079196135886296L;

    public static final long METADATA_LLDP_VALUE = 0x0000_0000_0020_0000L;
    public static final long METADATA_LLDP_MASK = 0x0000_0000_0020_0000L;

    public static final long METADATA_ONE_SWITCH_FLOW_VALUE = 0x0000_0000_0040_0000L;
    public static final long METADATA_ONE_SWITCH_FLOW_MASK = 0x0000_0000_0040_0000L;
    private static final long ENCAPSULATION_ID_MASK = 0x0000_0000_000F_FFFFL;
    private static final long FORWARD_METADATA_FLAG = 0x0000_0000_0010_0000L;

    private long encapsulationId;
    private boolean forward;
    private boolean oneSwitchFlow;
    private boolean lldp;

    public static long getOneSwitchFlowLldpValue() {
        return METADATA_LLDP_VALUE | METADATA_ONE_SWITCH_FLOW_VALUE;
    }

    public static long getOneSwitchFlowLldpMask() {
        return METADATA_LLDP_MASK | METADATA_ONE_SWITCH_FLOW_MASK;
    }


    @Builder
    public Metadata(@JsonProperty("encapsulation_id") long encapsulationId,
                    @JsonProperty("forward") boolean forward,
                    @JsonProperty("lldp") boolean lldp,
                    @JsonProperty("one_switch_flow") boolean oneSwitchFlow) {
        this.encapsulationId = encapsulationId;
        this.forward = forward;
        this.oneSwitchFlow = oneSwitchFlow;
        this.lldp = lldp;

    }

    public Metadata(long rawValue) {
        this.encapsulationId = rawValue & ENCAPSULATION_ID_MASK;
        this.forward = (rawValue & FORWARD_METADATA_FLAG) == FORWARD_METADATA_FLAG;
        this.lldp = (rawValue & METADATA_LLDP_VALUE) == METADATA_LLDP_VALUE;
        this.oneSwitchFlow = (rawValue & METADATA_ONE_SWITCH_FLOW_VALUE) == METADATA_ONE_SWITCH_FLOW_VALUE;
    }

    @Override
    public String toString() {
        return toString(getRawValue());
    }

    public static String toString(long metadata) {
        return String.format("0x%016X", metadata);
    }

    /**
     * Returns raw metadata value.
     * @return raw value
     */
    @JsonIgnore
    public long getRawValue() {
        long directionFlag = forward ? FORWARD_METADATA_FLAG : 0L;
        long lldpFlag = lldp ? METADATA_LLDP_VALUE : 0L;
        long oneSwitchFlowFlag = oneSwitchFlow ? METADATA_ONE_SWITCH_FLOW_VALUE : 0L;
        return (encapsulationId & ENCAPSULATION_ID_MASK) | directionFlag | lldpFlag | oneSwitchFlowFlag;
    }

    /**
     * Returns metadata mask based on filed items.
     * @return metadata mask
     */
    @JsonIgnore
    public  long getMask() {
        long mask = 0L;
        if (encapsulationId != 0L) {
            mask |= ENCAPSULATION_ID_MASK;
        }
        if (forward) {
            mask |= FORWARD_METADATA_FLAG;
        }
        if (lldp) {
            mask |= METADATA_LLDP_MASK;
        }
        if (oneSwitchFlow) {
            mask |= METADATA_ONE_SWITCH_FLOW_MASK;
        }
        return mask;
    }


}
