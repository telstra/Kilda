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

import org.openkilda.model.bitops.BitField;
import org.openkilda.model.bitops.NumericEnumField;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Builder;
import lombok.Getter;
import org.apache.commons.lang3.ArrayUtils;

public class FlowSharedSegmentCookie extends CookieBase {
    // update ALL_FIELDS if modify fields list
    //                           used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField SHARED_TYPE_FIELD = new BitField(0x000F_0000_0000_0000L);
    static final BitField PORT_NUMBER_FIELD = new BitField(0x0000_0000_0000_FFFFL);
    static final BitField VLAN_ID_FIELD     = new BitField(0x0000_0000_0FFF_0000L);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(
            CookieBase.ALL_FIELDS,
            SHARED_TYPE_FIELD, PORT_NUMBER_FIELD, VLAN_ID_FIELD);

    private static final CookieType VALID_TYPE = CookieType.SHARED_OF_FLOW;

    @JsonCreator
    public FlowSharedSegmentCookie(long value) {
        super(value);
    }

    @Builder
    protected FlowSharedSegmentCookie(CookieType type, SharedSegmentType segmentType, int portNumber, int vlanId) {
        super(makeValue(type, segmentType, portNumber, vlanId), type);
    }

    /**
     * Convert existing object into builder.
     */
    public FlowSharedSegmentCookieBuilder toBuilder() {
        return new FlowSharedSegmentCookieBuilder()
                .type(getType())
                .segmentType(getSegmentType())
                .portNumber(getPortNumber())
                .vlanId(getVlanId());
    }

    public SharedSegmentType getSegmentType() {
        int numericValue = (int) getField(SHARED_TYPE_FIELD);
        return resolveEnum(SharedSegmentType.values(), numericValue).orElse(SharedSegmentType.INVALID);
    }

    public int getPortNumber() {
        return (int) getField(PORT_NUMBER_FIELD);
    }

    public int getVlanId() {
        return (int) getField(VLAN_ID_FIELD);
    }

    /**
     * Make builder.
     */
    public static FlowSharedSegmentCookieBuilder builder(SharedSegmentType segmentType) {
        return new FlowSharedSegmentCookieBuilder()
                .type(VALID_TYPE)
                .segmentType(segmentType);
    }

    private static long makeValue(CookieType type, SharedSegmentType segmentType, int portNumber, int vlanId) {
        if (type != VALID_TYPE) {
            throw new IllegalArgumentException(formatIllegalTypeError(type, VALID_TYPE));
        }

        long value = setField(0, SHARED_TYPE_FIELD, segmentType.getValue());
        value = setField(value, PORT_NUMBER_FIELD, portNumber);
        return setField(value, VLAN_ID_FIELD, vlanId);
    }

    public enum SharedSegmentType implements NumericEnumField {
        QINQ_OUTER_VLAN(0),
        SERVER42_QINQ_OUTER_VLAN(1),

        // This do not consume any value from allowed address space - you can define another field with -1 value.
        // (must be last entry)
        INVALID(-1);

        @Getter
        private final int value;

        SharedSegmentType(int value) {
            this.value = value;
        }
    }
}
