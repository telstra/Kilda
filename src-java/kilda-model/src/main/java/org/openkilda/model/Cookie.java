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
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Value;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents information about a cookie.
 * Uses 64 bit to encode information about the flow:
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            Payload Reserved           |                       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           Reserved Prefix           |C|     Rule Type   | | | |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * <p>
 * Rule types:
 * 0 - Customer flow rule
 * 1 - LLDP rule
 * 2 - Multi-table ISL rule for vlan encapsulation for egress table
 * 3 - Multi-table ISL rule for vxlan encapsulation for egress table
 * 4 - Multi-table ISL rule for vxlan encapsulation for transit table
 * 5 - Multi-table customer flow rule for ingress table pass-through
 * </p>
 */
@Value
public class Cookie implements Comparable<Cookie>, Serializable {
    private static final long serialVersionUID = 1L;

    public static final long DEFAULT_RULE_FLAG                   = 0x8000_0000_0000_0000L;
    public static final long FLOW_PATH_FORWARD_FLAG              = 0x4000_0000_0000_0000L;
    public static final long FLOW_PATH_REVERSE_FLAG              = 0x2000_0000_0000_0000L;

    // There is no alive system that use this deprecated direction flags so it should be save to drop it.
    @Deprecated
    public static final long DEPRECATED_FLOW_PATH_DIRECTION_FLAG = 0x0080_0000_0000_0000L;
    public static final long FLOW_COOKIE_VALUE_MASK              = 0x0000_0000_000F_FFFFL;
    public static final long ISL_COOKIE_VALUE_MASK               = 0x0000_0000_000F_FFFFL;
    public static final long INGRESS_RULE_COOKIE_VALUE_MASK      = 0x0000_0000_000F_FFFFL;

    public static final long DROP_RULE_COOKIE                           = 0x01L | DEFAULT_RULE_FLAG;
    public static final long VERIFICATION_BROADCAST_RULE_COOKIE         = 0x02L | DEFAULT_RULE_FLAG;
    public static final long VERIFICATION_UNICAST_RULE_COOKIE           = 0x03L | DEFAULT_RULE_FLAG;
    public static final long DROP_VERIFICATION_LOOP_RULE_COOKIE         = 0x04L | DEFAULT_RULE_FLAG;
    public static final long CATCH_BFD_RULE_COOKIE                      = 0x05L | DEFAULT_RULE_FLAG;
    public static final long ROUND_TRIP_LATENCY_RULE_COOKIE             = 0x06L | DEFAULT_RULE_FLAG;
    public static final long VERIFICATION_UNICAST_VXLAN_RULE_COOKIE     = 0x07L | DEFAULT_RULE_FLAG;
    public static final long MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE = 0x08L | DEFAULT_RULE_FLAG;
    public static final long MULTITABLE_INGRESS_DROP_COOKIE             = 0x09L | DEFAULT_RULE_FLAG;
    public static final long MULTITABLE_POST_INGRESS_DROP_COOKIE        = 0x0AL | DEFAULT_RULE_FLAG;
    public static final long MULTITABLE_EGRESS_PASS_THROUGH_COOKIE      = 0x0BL | DEFAULT_RULE_FLAG;
    public static final long MULTITABLE_TRANSIT_DROP_COOKIE             = 0x0CL | DEFAULT_RULE_FLAG;
    public static final long LLDP_INPUT_PRE_DROP_COOKIE                 = 0x0DL | DEFAULT_RULE_FLAG;
    public static final long LLDP_TRANSIT_COOKIE                        = 0x0EL | DEFAULT_RULE_FLAG;
    public static final long LLDP_INGRESS_COOKIE                        = 0x0FL | DEFAULT_RULE_FLAG;
    public static final long LLDP_POST_INGRESS_COOKIE                   = 0x10L | DEFAULT_RULE_FLAG;
    public static final long LLDP_POST_INGRESS_VXLAN_COOKIE             = 0x11L | DEFAULT_RULE_FLAG;
    public static final long LLDP_POST_INGRESS_ONE_SWITCH_COOKIE        = 0x12L | DEFAULT_RULE_FLAG;
    public static final long ARP_INPUT_PRE_DROP_COOKIE                  = 0x13L | DEFAULT_RULE_FLAG;
    public static final long ARP_TRANSIT_COOKIE                         = 0x14L | DEFAULT_RULE_FLAG;
    public static final long ARP_INGRESS_COOKIE                         = 0x15L | DEFAULT_RULE_FLAG;
    public static final long ARP_POST_INGRESS_COOKIE                    = 0x16L | DEFAULT_RULE_FLAG;
    public static final long ARP_POST_INGRESS_VXLAN_COOKIE              = 0x17L | DEFAULT_RULE_FLAG;
    public static final long ARP_POST_INGRESS_ONE_SWITCH_COOKIE         = 0x18L | DEFAULT_RULE_FLAG;

    // 9 bits cookie type "field"
    public static final long TYPE_MASK                               = 0x1FF0_0000_0000_0000L;
    public static final long FLOW_COOKIE_TYPE                        = 0x0000_0000_0000_0000L;
    public static final long LLDP_INPUT_CUSTOMER_TYPE                = 0x0010_0000_0000_0000L;
    public static final long MULTITABLE_ISL_VLAN_EGRESS_RULES_TYPE   = 0x0020_0000_0000_0000L;
    public static final long MULTITABLE_ISL_VXLAN_EGRESS_RULES_TYPE  = 0x0030_0000_0000_0000L;
    public static final long MULTITABLE_ISL_VXLAN_TRANSIT_RULES_TYPE = 0x0040_0000_0000_0000L;
    public static final long MULTITABLE_INGRESS_RULES_TYPE           = 0x0050_0000_0000_0000L;
    public static final long ARP_INPUT_CUSTOMER_TYPE                 = 0x0060_0000_0000_0000L;
    public static final long LLDP_VXLAN_INGRESS_TYPE                 = 0x0070_0000_0000_0000L;
    public static final long ARP_VXLAN_INGRESS_TYPE                  = 0x0080_0000_0000_0000L;

    private final long value;

    /**
     * Create {@code Cookie} instance and perform it's validation.
     */
    public static Cookie decode(long rawValue) {
        Cookie cookie = new Cookie(rawValue);
        cookie.ensureNoFlagsConflicts();
        return cookie;
    }

    @JsonCreator
    public Cookie(long value) {
        this.value = value;
    }

    public static Cookie buildForwardCookie(long unmaskedCookie) {
        return new Cookie(unmaskedCookie | Cookie.FLOW_PATH_FORWARD_FLAG);
    }

    public static Cookie buildReverseCookie(long unmaskedCookie) {
        return new Cookie(unmaskedCookie | Cookie.FLOW_PATH_REVERSE_FLAG);
    }

    public static long encodeIslVlanEgress(int port) {
        return port | Cookie.MULTITABLE_ISL_VLAN_EGRESS_RULES_TYPE | Cookie.DEFAULT_RULE_FLAG;
    }

    public static long encodeIslVxlanEgress(int port) {
        return port | Cookie.MULTITABLE_ISL_VXLAN_EGRESS_RULES_TYPE | Cookie.DEFAULT_RULE_FLAG;
    }

    public static long encodeIslVxlanTransit(int port) {
        return port | Cookie.MULTITABLE_ISL_VXLAN_TRANSIT_RULES_TYPE | Cookie.DEFAULT_RULE_FLAG;
    }

    public static long encodeIngressRulePassThrough(int port) {
        return port | Cookie.MULTITABLE_INGRESS_RULES_TYPE | Cookie.DEFAULT_RULE_FLAG;
    }

    public static long encodeLldpInputCustomer(int port) {
        return port | Cookie.LLDP_INPUT_CUSTOMER_TYPE | Cookie.DEFAULT_RULE_FLAG;
    }

    public static long encodeArpInputCustomer(int port) {
        return port | Cookie.ARP_INPUT_CUSTOMER_TYPE | Cookie.DEFAULT_RULE_FLAG;
    }

    /**
     * Encode flow cookie as LLDP VXLAN cookie.
     */
    public static long encodeLldpVxlanCookie(long flowCookie) {
        if (!isMaskedAsFlowCookie(flowCookie)) {
            throw new IllegalArgumentException("Cookie 0x%016X is not a flow cookie");
        }
        return flowCookie | LLDP_VXLAN_INGRESS_TYPE;
    }

    /**
     * Encode flow cookie as ARP VXLAN cookie.
     */
    public static long encodeArpVxlanCookie(long flowCookie) {
        if (!isMaskedAsFlowCookie(flowCookie)) {
            throw new IllegalArgumentException("Cookie 0x%016X is not a flow cookie");
        }
        return flowCookie | ARP_VXLAN_INGRESS_TYPE;
    }

    /**
     * Create Cookie from meter ID of default rule by using of `DEFAULT_RULES_FLAG`.
     *
     * @param meterId meter ID
     * @return cookie
     * @throws IllegalArgumentException if meter ID is out of range of default meter ID range
     */
    public static Cookie createCookieForDefaultRule(long meterId) {
        if (!MeterId.isMeterIdOfDefaultRule(meterId)) {
            throw new IllegalArgumentException(
                    String.format("Meter ID '%s' is not a meter ID of default rule.", meterId));
        }

        return new Cookie(meterId | DEFAULT_RULE_FLAG);
    }

    public boolean isDefaultRule() {
        return isDefaultRule(value);
    }

    public static boolean isDefaultRule(long cookie) {
        return (cookie & DEFAULT_RULE_FLAG) != 0L;
    }

    /**
     * Checks whether the cookie corresponds to the forward flow mask.
     */
    public boolean isMaskedAsForward() {
        boolean isMatch;
        if ((value & 0xE000000000000000L) != 0) {
            isMatch = (value & FLOW_PATH_FORWARD_FLAG) != 0;
        } else {
            isMatch = (value & DEPRECATED_FLOW_PATH_DIRECTION_FLAG) == 0;
        }
        return isMatch;
    }

    /**
     * Checks whether the cookie corresponds to the reverse flow mask.
     */
    public boolean isMaskedAsReversed() {
        boolean isMatch;
        if ((value & 0xE000000000000000L) != 0) {
            isMatch = (value & FLOW_PATH_REVERSE_FLAG) != 0;
        } else {
            isMatch = (value & DEPRECATED_FLOW_PATH_DIRECTION_FLAG) != 0;
        }
        return isMatch;
    }

    /**
     * Checks whether the cookie is main flow cookie.
     */
    public static boolean isMaskedAsFlowCookie(long value) {
        return (TYPE_MASK & value) == FLOW_COOKIE_TYPE;
    }

    public static boolean isLldpInputCustomer(long value) {
        return (TYPE_MASK & value) == LLDP_INPUT_CUSTOMER_TYPE;
    }

    public static boolean isArpInputCustomer(long value) {
        return (TYPE_MASK & value) == ARP_INPUT_CUSTOMER_TYPE;
    }

    public static boolean isIslVlanEgress(long value) {
        return (TYPE_MASK & value) == Cookie.MULTITABLE_ISL_VLAN_EGRESS_RULES_TYPE;
    }

    public static boolean isIslVxlanEgress(long value) {
        return (TYPE_MASK & value) == Cookie.MULTITABLE_ISL_VXLAN_EGRESS_RULES_TYPE;
    }

    public static boolean isIslVxlanTransit(long value) {
        return (TYPE_MASK & value) == Cookie.MULTITABLE_ISL_VXLAN_TRANSIT_RULES_TYPE;
    }

    public static boolean isIngressRulePassThrough(long value) {
        return (TYPE_MASK & value) == Cookie.MULTITABLE_INGRESS_RULES_TYPE;
    }

    public static boolean isMaskedAsLldpVxlan(long value) {
        return (TYPE_MASK & value) == Cookie.LLDP_VXLAN_INGRESS_TYPE;
    }

    public static boolean isMaskedAsArpVxlan(long value) {
        return (TYPE_MASK & value) == Cookie.ARP_VXLAN_INGRESS_TYPE;
    }

    public static long getValueFromIntermediateCookie(long value) {
        return value & ISL_COOKIE_VALUE_MASK;
    }

    public long getUnmaskedValue() {
        return value & FLOW_COOKIE_VALUE_MASK;
    }

    private void ensureNoFlagsConflicts() {
        long[] mutuallyExclusiveFlags = {DEFAULT_RULE_FLAG, FLOW_PATH_FORWARD_FLAG, FLOW_PATH_REVERSE_FLAG};
        List<Long> conflict = new ArrayList<>();
        for (long flag : mutuallyExclusiveFlags) {
            if ((value & flag) != 0) {
                conflict.add(flag);
            }
        }

        if (1 < conflict.size()) {
            String conflictAsString = conflict.stream()
                    .map(Cookie::toString)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(String.format(
                    "Invalid flags combination - more than one of mutually exclusive flags(%s) are set in cookie %s",
                    conflictAsString, this));
        }
    }

    @JsonValue
    public long getValue() {
        return value;
    }

    @Override
    public String toString() {
        return toString(value);
    }

    public static String toString(long cookie) {
        return String.format("0x%016X", cookie);
    }

    @Override
    public int compareTo(Cookie compareWith) {
        return Long.compare(value, compareWith.value);
    }
}
