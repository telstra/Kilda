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

import lombok.Getter;

public abstract class CookieSchema {
    // update ALL_FIELDS if modify fields list
    static final BitField SERVICE_FLAG = new BitField(0x8000_0000_0000_0000L);
    static final BitField TYPE_FIELD   = new BitField(0x1FF0_0000_0000_0000L);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = new BitField[]{SERVICE_FLAG, TYPE_FIELD};

    protected abstract Cookie makeBlank();

    public void validate(Cookie cookie) throws InvalidCookieException {
        // inheritors can implement validate logic
    }

    /**
     * Extract and return "type" field.
     */
    public CookieType getType(Cookie cookie) {
        return resolveEnum(CookieType.values(), getRawType(cookie), CookieType.class);
    }

    // TODO: drop?
    protected int getRawType(Cookie cookie) {
        return (int) getField(cookie.getValue(), TYPE_FIELD);
    }

    protected long setType(long value, CookieType type) {
        return setField(value, TYPE_FIELD, type.getValue());
    }

    protected long getField(long value, BitField field) {
        long payload = value & field.getMask();
        return payload >>> field.offset;
    }

    protected Cookie setField(Cookie cookie, BitField field, long payload) {
        long raw = setField(cookie.getValue(), field, payload);
        return new Cookie(raw);
    }

    protected long setField(long value, BitField field, long payload) {
        long mask = field.getMask();
        payload <<= field.offset;
        payload &= mask;
        return (value & ~mask) | payload;
    }

    protected void validateServiceFlag(Cookie cookie, boolean expectedValue) throws InvalidCookieException {
        boolean actual = getField(cookie.getValue(), SERVICE_FLAG) != 0;
        if (expectedValue != actual) {
            throw new InvalidCookieException(
                    String.format("Service flag is expected to be %s", expectedValue ? "set" : "unset"), cookie);
        }
    }

    protected static <T extends CookieEnumField> T resolveEnum(T[] valuesSpace, long needle, Class<T> typeRef) {
        for (T entry : valuesSpace) {
            if (entry.getValue() == needle) {
                return entry;
            }
        }

        throw new IllegalArgumentException(String.format(
                "Unable to map value %x value into %s value", needle, typeRef.getSimpleName()));
    }

    @Getter
    static class BitField {
        private final long mask;
        private final int offset;

        public BitField(long mask) {
            Integer start = null;
            Integer end = null;

            long probe = 1;
            for (int i = 0; i < 8 * 8; i++) {
                boolean isSet = (mask & probe) != 0;
                if (start == null && isSet) {
                    start = i;
                } else if (start != null && end == null && !isSet) {
                    end = i;
                } else if (end != null && isSet) {
                    throw new IllegalArgumentException(String.format(
                            "Illegal bit field mask %s - it contain gaps", Cookie.toString(mask)));
                }

                probe <<= 1;
            }
            if (start == null) {
                throw new IllegalArgumentException("Bit field mask must not be 0");
            }

            this.mask = mask;
            this.offset = start;
        }
    }

    // 9 bit long field
    public enum CookieType implements CookieEnumField {
        SERVICE_OR_FLOW_SEGMENT(0x000),
        LLDP(0x001),
        MULTI_TABLE_ISL_VLAN_EGRESS_RULES(0x002),
        MULTI_TABLE_ISL_VXLAN_EGRESS_RULES(0x003),
        MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES(0x004),
        MULTI_TABLE_INGRESS_RULES(0x005),
        ARP_INPUT_CUSTOMER_TYPE(0x006),
        INGRESS_SEGMENT(0x007),   // used for ingress flow segment and for one switch flow segments
        SHARED_OF_FLOW(0x008);

        private int value;

        CookieType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
