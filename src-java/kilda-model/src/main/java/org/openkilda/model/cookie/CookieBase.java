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
import org.openkilda.model.bitops.BitField;
import org.openkilda.model.bitops.NumericEnumField;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@EqualsAndHashCode(of = {"value"})
public abstract class CookieBase implements Serializable {
    private static final long serialVersionUID = 1L;

    // update ALL_FIELDS if modify fields list
    static final BitField TYPE_FIELD = new BitField(0x1FF0_0000_0000_0000L);
    static final BitField SERVICE_FLAG = new BitField(0x8000_0000_0000_0000L);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = new BitField[]{SERVICE_FLAG, TYPE_FIELD};

    private final long value;

    CookieBase(long value) {
        this.value = value;
    }

    protected CookieBase(long value, CookieType type) {
        this.value = setField(value, TYPE_FIELD, type.getValue());
    }

    public boolean getServiceFlag() {
        return getField(SERVICE_FLAG) != 0;
    }

    /**
     * Validate cookie value without throwing exception in case of validation fail, but returning {@code false} result.
     */
    public boolean isValid() {
        try {
            validate();
            return true;
        } catch (InvalidCookieException e) {
            return false;
        }
    }

    public void validate() throws InvalidCookieException {
        // inheritors can implement validate logic
    }

    @JsonValue
    public long getValue() {
        return value;
    }

    /**
     * Extract and return "type" field.
     */
    public CookieType getType() {
        int numericType = (int) getField(TYPE_FIELD);
        return resolveEnum(CookieType.values(), numericType).orElse(CookieType.INVALID);
    }

    protected long getField(BitField field) {
        long payload = value & field.getMask();
        return payload >>> field.getOffset();
    }

    @Override
    public String toString() {
        return toString(value);
    }

    @Deprecated
    public static String toString(long cookie) {
        return String.format("0x%016X", cookie);
    }

    protected int cookieComparison(CookieBase other) {
        return Long.compare(value, other.value);
    }

    protected void validateServiceFlag(boolean expectedValue) throws InvalidCookieException {
        boolean actual = getField(SERVICE_FLAG) != 0;
        if (expectedValue != actual) {
            throw new InvalidCookieException(
                    String.format("Service flag is expected to be %s", expectedValue ? "set" : "unset"), this);
        }
    }

    /**
     * Scan all enum elements and compare their numberic representation with {@code needle} argument. Returns matched
     * enum element.
     */
    protected static <T extends NumericEnumField> Optional<T> resolveEnum(T[] valuesSpace, long needle) {
        for (T entry : valuesSpace) {
            if (entry.getValue() == needle) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    protected static long setField(long value, BitField field, long payload) {
        long mask = field.getMask();
        payload <<= field.getOffset();
        payload &= mask;
        return (value & ~mask) | payload;
    }

    protected static String formatIllegalTypeError(CookieType illegalType, CookieType legalType) {
        return String.format("%s is not allowed type, only %s is allowed", illegalType, legalType);
    }

    protected static String formatIllegalTypeError(CookieType illegalType, Set<CookieType> legalTypes) {
        return String.format(
                "%s do not belong to subset of allowed types (%s)", illegalType, legalTypes.stream()
                        .map(CookieType::toString)
                        .sorted()
                        .collect(Collectors.joining(", ")));
    }

    // 9 bit long type field
    public enum CookieType implements NumericEnumField {
        SERVICE_OR_FLOW_SEGMENT(0x000),
        LLDP_INPUT_CUSTOMER_TYPE(0x001),
        MULTI_TABLE_ISL_VLAN_EGRESS_RULES(0x002),
        MULTI_TABLE_ISL_VXLAN_EGRESS_RULES(0x003),
        MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES(0x004),
        MULTI_TABLE_INGRESS_RULES(0x005),
        ARP_INPUT_CUSTOMER_TYPE(0x006),
        INGRESS_SEGMENT(0x007),   // used for ingress flow segment and for one switch flow segments
        SHARED_OF_FLOW(0x008),
        SERVER_42_INPUT(0x009),
        APPLICATION_MIRROR_FLOW(0x00A),
        EXCLUSION_FLOW(0x0B),
        SERVER_42_INGRESS(0x00C),
        APPLICATION_MIRROR_EGRESS(0x00D),

        // This do not consume any value from allowed address space - you can define another field with -1 value.
        // (must be last entry)
        INVALID(-1);

        private final int value;

        CookieType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
