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

package org.openkilda.model.cookie;

import org.openkilda.exception.InvalidCookieException;
import org.openkilda.model.Cookie;
import org.openkilda.model.MeterId;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServiceCookieSchema extends CookieSchema {
    public static final ServiceCookieSchema INSTANCE = new ServiceCookieSchema();

    private static final Set<CookieType> allowedTypes = Stream.of(
            CookieType.SERVICE_OR_FLOW_SEGMENT,
            CookieType.LLDP,
            CookieType.MULTI_TABLE_ISL_VLAN_EGRESS_RULES,
            CookieType.MULTI_TABLE_ISL_VXLAN_EGRESS_RULES,
            CookieType.MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES,
            CookieType.MULTI_TABLE_INGRESS_RULES,
            CookieType.ARP_INPUT_CUSTOMER_TYPE
    ).collect(Collectors.toSet());

    // update ALL_FIELDS if modify fields list
    //                   used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField UNIQUE_ID = new BitField(0x0000_0000_FFFF_FFFFL);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(CookieSchema.ALL_FIELDS, UNIQUE_ID);

    public Cookie make(ServiceCookieTag tag) {
        return make(CookieType.SERVICE_OR_FLOW_SEGMENT, tag.getValue());
    }

    public Cookie make(CookieType type) {
        return make(type, 0);
    }

    /**
     * Create new cookie instance and fill type and id fields.
     */
    public Cookie make(CookieType type, long uniqueId) {
        if (!allowedTypes.contains(type)) {
            throw new IllegalArgumentException(String.format(
                    "%s do not belong to subset of service types (%s)", type, allowedTypes.stream()
                            .map(CookieType::toString)
                            .sorted()
                            .collect(Collectors.joining(", "))));
        }

        long payload = setType(0, type);
        payload = setField(payload, SERVICE_FLAG, 1);
        payload = setField(payload, UNIQUE_ID, uniqueId);
        return new Cookie(payload);
    }

    @Override
    public Cookie makeBlank() {
        return make(CookieType.SERVICE_OR_FLOW_SEGMENT);
    }

    @Override
    public void validate(Cookie cookie) throws InvalidCookieException {
        super.validate(cookie);
        validateServiceFlag(cookie, true);
    }

    public boolean isServiceCookie(Cookie cookie) {
        return getField(cookie.getValue(), SERVICE_FLAG) != 0;
    }

    public long getUniqueId(Cookie cookie) {
        return getField(cookie.getValue(), UNIQUE_ID);
    }

    public ServiceCookieTag getServiceTag(Cookie cookie) {
        long raw = getField(cookie.getValue(), UNIQUE_ID);
        return resolveEnum(ServiceCookieTag.values(), raw, ServiceCookieTag.class);
    }

    public Cookie setServiceTag(Cookie cookie, ServiceCookieTag serviceId) {
        long payload = setField(cookie.getValue(), UNIQUE_ID, serviceId.getValue());
        return new Cookie(payload);
    }

    /**
     * Save meter id into id field. Check meter id value for allowed ranges.
     */
    public Cookie setMeterId(Cookie cookie, MeterId meterId) {
        if (!MeterId.isMeterIdOfDefaultRule(meterId.getValue())) {
            throw new IllegalArgumentException(
                    String.format("Meter ID '%s' is not a meter ID of default rule.", meterId));
        }
        long raw = setField(cookie.getValue(), UNIQUE_ID, meterId.getValue());
        return new Cookie(raw);
    }

    protected ServiceCookieSchema() {
        super();
    }

    public enum ServiceCookieTag implements CookieEnumField {
        DROP_RULE_COOKIE(0x01),
        VERIFICATION_BROADCAST_RULE_COOKIE(0x02),
        VERIFICATION_UNICAST_RULE_COOKIE(0x03),
        DROP_VERIFICATION_LOOP_RULE_COOKIE(0x04),
        CATCH_BFD_RULE_COOKIE(0x05),
        ROUND_TRIP_LATENCY_RULE_COOKIE(0x06),
        VERIFICATION_UNICAST_VXLAN_RULE_COOKIE(0x07),
        MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE(0x08),
        MULTITABLE_INGRESS_DROP_COOKIE(0x09),
        MULTITABLE_POST_INGRESS_DROP_COOKIE(0x0A),
        MULTITABLE_EGRESS_PASS_THROUGH_COOKIE(0x0B),
        MULTITABLE_TRANSIT_DROP_COOKIE(0x0C),
        LLDP_INPUT_PRE_DROP_COOKIE(0x0D),
        LLDP_TRANSIT_COOKIE(0x0E),
        LLDP_INGRESS_COOKIE(0x0F),
        LLDP_POST_INGRESS_COOKIE(0x10),
        LLDP_POST_INGRESS_VXLAN_COOKIE(0x11),
        LLDP_POST_INGRESS_ONE_SWITCH_COOKIE(0x12),
        ARP_INPUT_PRE_DROP_COOKIE(0x13),
        ARP_TRANSIT_COOKIE(0x14),
        ARP_INGRESS_COOKIE(0x15),
        ARP_POST_INGRESS_COOKIE(0x16),
        ARP_POST_INGRESS_VXLAN_COOKIE(0x17),
        ARP_POST_INGRESS_ONE_SWITCH_COOKIE(0x18);

        private int value;

        ServiceCookieTag(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
