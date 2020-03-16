/*
 * Copyright 2020 Telstra Open Source
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
import org.apache.commons.lang3.ArrayUtils;

public class SharedOfFlowCookieSchema extends CookieSchema {
    public static final SharedOfFlowCookieSchema INSTANCE = new SharedOfFlowCookieSchema();

    // update ALL_FIELDS if modify fields list
    //                           used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField SHARED_TYPE_FIELD = new BitField(0x000F_0000_0000_0000L);
    static final BitField UNIQUE_ID_FIELD   = new BitField(0x0000_0000_FFFF_FFFFL);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(CookieSchema.ALL_FIELDS, SHARED_TYPE_FIELD, UNIQUE_ID_FIELD);

    @Override
    public Cookie makeBlank() {
        return new Cookie(setType(0, CookieType.SHARED_OF_FLOW));
    }

    @Override
    public void validate(Cookie cookie) throws InvalidCookieException {
        super.validate(cookie);
        validateServiceFlag(cookie, false);
    }

    public SharedOfFlowType getSharedType(Cookie cookie) {
        long raw = getField(cookie.getValue(), SHARED_TYPE_FIELD);
        return resolveEnum(SharedOfFlowType.values(), raw, SharedOfFlowType.class);
    }

    public long getUniqueIdField(Cookie cookie) {
        return getField(cookie.getValue(), UNIQUE_ID_FIELD);
    }

    /**
     * Produce and store unique shared flow id from port number.
     */
    public Cookie setUniqueIdField(Cookie cookie, int portNumber) {
        return setUniqueIdField(cookie, portNumber, 0);
    }

    /**
     * Produce and store unique shared flow id from port number and vlan-id.
     */
    public Cookie setUniqueIdField(Cookie cookie, int portNumber, int vlanId) {
        int vlanUpperRange = 0xFFF;
        if ((vlanId & vlanUpperRange) != vlanId) {
            throw new IllegalArgumentException(String.format(
                    "VLAN-id %d is not within allowed range from 0 to %d", vlanId, vlanUpperRange));
        }

        int portUpperRange = 0xFFFF;
        if ((portNumber & portUpperRange) != portNumber) {
            throw new IllegalArgumentException(String.format(
                    "Port number %d is not within allowed range from 0 to %d", portNumber, portUpperRange));
        }

        long uniqueId = (vlanId << 16) | portNumber;
        return new Cookie(setField(cookie.getValue(), UNIQUE_ID_FIELD, uniqueId));
    }

    protected SharedOfFlowCookieSchema() {
        super();
    }

    public enum SharedOfFlowType implements CookieEnumField {
        CUSTOMER_PORT(0),
        QINQ_OUTER_VLAN(1);

        @Getter
        private final int value;

        SharedOfFlowType(int value) {
            this.value = value;
        }
    }
}
