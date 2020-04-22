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

import lombok.Value;

import java.io.Serializable;

/**
 * Represents information about a metadata.
 * Uses 64 bit to encode information about the packet:
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |L|O|A| |Customer port|      Reserved Prefix                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          Reserved Prefix                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * <p>
 * L - flag indicates LLDP packet
 * O - flag indicates packet received by one switch flow
 * A - flag indicates ARP packet
 * Customer port - encode customer port for Server 2 Ping
 * </p>
 */
@Value
public class Metadata implements Serializable {
    private static final long serialVersionUID = 5505079196135886296L;

    public static final long METADATA_LLDP_VALUE = 0x0000_0000_0000_0001L;
    public static final long METADATA_LLDP_MASK =  0x0000_0000_0000_0001L;

    public static final long METADATA_ONE_SWITCH_FLOW_VALUE = 0x0000_0000_0000_0002L;
    public static final long METADATA_ONE_SWITCH_FLOW_MASK =  0x0000_0000_0000_0002L;

    public static final long METADATA_ARP_VALUE = 0x0000_0000_0000_0004L;
    public static final long METADATA_ARP_MASK =  0x0000_0000_0000_0004L;

    public static final long METADATA_CUSTOMER_PORT_MASK =  0x0000_0000_0000_07F0L;

    private final long value;

    public static long getOneSwitchFlowLldpValue() {
        return METADATA_LLDP_VALUE | METADATA_ONE_SWITCH_FLOW_VALUE;
    }

    public static long getOneSwitchFlowLldpMask() {
        return METADATA_LLDP_MASK | METADATA_ONE_SWITCH_FLOW_MASK;
    }

    public static long getOneSwitchFlowArpValue() {
        return METADATA_ARP_VALUE | METADATA_ONE_SWITCH_FLOW_VALUE;
    }

    public static long getOneSwitchFlowArpMask() {
        return METADATA_ARP_MASK | METADATA_ONE_SWITCH_FLOW_MASK;
    }

    public static long encodeCustomerPort(int port) {
        return (port << 4) & METADATA_CUSTOMER_PORT_MASK;
    }

    @Override
    public String toString() {
        return toString(value);
    }

    public static String toString(long metadata) {
        return String.format("0x%016X", metadata);
    }
}
