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

import org.openkilda.model.cookie.CookieSchema.CookieType;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.io.Serializable;

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
@EqualsAndHashCode(of = {"value"})
public class Cookie implements Comparable<Cookie>, Serializable {
    private static final long serialVersionUID = 1L;

    // FIXME(surabujin): get rid from this constants
    public static final long DROP_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.DROP_RULE_COOKIE).getValue();
    public static final long VERIFICATION_BROADCAST_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.VERIFICATION_BROADCAST_RULE_COOKIE).getValue();
    public static final long VERIFICATION_UNICAST_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.VERIFICATION_UNICAST_RULE_COOKIE).getValue();
    public static final long DROP_VERIFICATION_LOOP_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.DROP_VERIFICATION_LOOP_RULE_COOKIE).getValue();
    public static final long CATCH_BFD_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.CATCH_BFD_RULE_COOKIE).getValue();
    public static final long ROUND_TRIP_LATENCY_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ROUND_TRIP_LATENCY_RULE_COOKIE).getValue();
    public static final long VERIFICATION_UNICAST_VXLAN_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue();
    public static final long MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE).getValue();
    public static final long MULTITABLE_INGRESS_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_INGRESS_DROP_COOKIE).getValue();
    public static final long MULTITABLE_POST_INGRESS_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_POST_INGRESS_DROP_COOKIE).getValue();
    public static final long MULTITABLE_EGRESS_PASS_THROUGH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_EGRESS_PASS_THROUGH_COOKIE).getValue();
    public static final long MULTITABLE_TRANSIT_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_TRANSIT_DROP_COOKIE).getValue();
    public static final long LLDP_INPUT_PRE_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_INPUT_PRE_DROP_COOKIE).getValue();
    public static final long LLDP_TRANSIT_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_TRANSIT_COOKIE).getValue();
    public static final long LLDP_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_INGRESS_COOKIE).getValue();
    public static final long LLDP_POST_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_POST_INGRESS_COOKIE).getValue();
    public static final long LLDP_POST_INGRESS_VXLAN_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_POST_INGRESS_VXLAN_COOKIE).getValue();
    public static final long LLDP_POST_INGRESS_ONE_SWITCH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue();
    public static final long ARP_INPUT_PRE_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_INPUT_PRE_DROP_COOKIE).getValue();
    public static final long ARP_TRANSIT_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_TRANSIT_COOKIE).getValue();
    public static final long ARP_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_INGRESS_COOKIE).getValue();
    public static final long ARP_POST_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_POST_INGRESS_COOKIE).getValue();
    public static final long ARP_POST_INGRESS_VXLAN_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_POST_INGRESS_VXLAN_COOKIE).getValue();
    public static final long ARP_POST_INGRESS_ONE_SWITCH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue();

    private long value;

    @JsonCreator
    public Cookie(long value) {
        this.value = value;
    }

    /**
     * Convert port number into isl-VLAN-egress "cookie".
     */
    public static long encodeIslVlanEgress(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_ISL_VLAN_EGRESS_RULES, port).getValue();
    }

    /**
     * Convert port number into isl-VxLAN-egress "cookie".
     */
    public static long encodeIslVxlanEgress(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_ISL_VXLAN_EGRESS_RULES, port).getValue();
    }

    /**
     * Convert port number into isl-VxLAN-transit "cookie".
     */
    public static long encodeIslVxlanTransit(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES, port).getValue();
    }

    /**
     * Convert port number into ingress-rule-pass-through "cookie".
     */
    public static long encodeIngressRulePassThrough(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_INGRESS_RULES, port).getValue();
    }

    /**
     * Creates masked cookie for LLDP rule.
     */
    public static long encodeLldpInputCustomer(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.LLDP, port).getValue();
    }

    public static long encodeArpInputCustomer(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.ARP_INPUT_CUSTOMER_TYPE, port).getValue();
    }

    /**
     * Create Cookie from meter ID of default rule by using of `DEFAULT_RULES_FLAG`.
     *
     * @param meterId meter ID
     * @return cookie
     * @throws IllegalArgumentException if meter ID is out of range of default meter ID range
     */
    public static Cookie createCookieForDefaultRule(long meterId) {
        // FIXME(surabujin): replace with direct schema call
        Cookie blank = ServiceCookieSchema.INSTANCE.makeBlank();
        return ServiceCookieSchema.INSTANCE.setMeterId(blank, new MeterId(meterId));
    }

    public static boolean isDefaultRule(long cookie) {
        // FIXME(surabujin): replace with direct schema call
        return ServiceCookieSchema.INSTANCE.isServiceCookie(new Cookie(cookie));
    }

    /**
     * Check is cookie have type MULTI_TABLE_INGRESS_RULES.
     *
     * <p>Deprecated {@code ServiceCookieSchema.getType()} must be used instead of this method.
     */
    @Deprecated
    public static boolean isIngressRulePassThrough(long value) {
        // FIXME(surabujin): replace with direct schema call
        Cookie cookie = new Cookie(value);
        return CookieType.MULTI_TABLE_INGRESS_RULES == ServiceCookieSchema.INSTANCE.getType(cookie);
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
