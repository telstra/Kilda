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
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;

import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FlowSegmentCookieSchema extends CookieSchema {
    public static final FlowSegmentCookieSchema INSTANCE = new FlowSegmentCookieSchema();

    // update ALL_FIELDS if modify fields list
    //                                     used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField FLOW_FORWARD_DIRECTION_FLAG = new BitField(0x4000_0000_0000_0000L);
    static final BitField FLOW_REVERSE_DIRECTION_FLAG = new BitField(0x2000_0000_0000_0000L);
    static final BitField FLOW_EFFECTIVE_ID_FIELD     = new BitField(0x0000_0000_000F_FFFFL);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(
            CookieSchema.ALL_FIELDS, FLOW_FORWARD_DIRECTION_FLAG, FLOW_REVERSE_DIRECTION_FLAG, FLOW_EFFECTIVE_ID_FIELD);

    public Cookie make(long effectiveId) {
        return setFlowEffectiveId(makeBlank(), effectiveId);
    }

    public Cookie make(long effectiveId, FlowPathDirection direction) {
        Cookie cookie = setFlowEffectiveId(makeBlank(), effectiveId);
        return setDirection(cookie, direction);
    }

    @Override
    public Cookie makeBlank() {
        return new Cookie(setType(0, CookieType.SERVICE_OR_FLOW_SEGMENT));
    }

    @Override
    public void validate(Cookie cookie) throws InvalidCookieException {
        super.validate(cookie);

        validateServiceFlag(cookie, false);

        long raw = cookie.getValue();
        int directionBitsSetCount = 0;
        BitField[] mutuallyExclusiveFlags = {FLOW_FORWARD_DIRECTION_FLAG, FLOW_REVERSE_DIRECTION_FLAG};
        for (BitField field : mutuallyExclusiveFlags) {
            directionBitsSetCount += getField(raw, field);
        }

        if (1 < directionBitsSetCount) {
            throw new InvalidCookieException("Illegal flags combination - both the direction bits are set", cookie);
        }
    }


    public FlowPathDirection getValidatedDirection(Cookie cookie) {
        FlowPathDirection direction = getDirection(cookie);
        if (FlowPathDirection.UNKNOWN == direction) {
            throw new IllegalArgumentException(String.format("Cookie %s have no the direction marker", cookie));
        }
        return direction;
    }

    /**
     * Extract and return normalized representation flow path direction.
     */
    public FlowPathDirection getDirection(Cookie cookie) {
        long raw = cookie.getValue();
        if (getField(raw, FLOW_FORWARD_DIRECTION_FLAG) != 0) {
            return FlowPathDirection.FORWARD;
        } else if (getField(raw, FLOW_REVERSE_DIRECTION_FLAG) != 0) {
            return FlowPathDirection.REVERSE;
        } else {
            return FlowPathDirection.UNKNOWN;
        }
    }

    public Cookie setDirection(Cookie cookie, FlowPathDirection direction) {
        int forward = 0;
        int reverse = 0;
        if (direction == FlowPathDirection.FORWARD) {
            forward = 1;
        } else if (direction == FlowPathDirection.REVERSE) {
            reverse = 1;
        } else if (direction == FlowPathDirection.UNKNOWN) {
            // nothing to do
        } else {
            throw new IllegalArgumentException(String.format(
                    "Unable to map %s.%s into cookie direction bits",
                    FlowPathDirection.class.getSimpleName(), direction));
        }

        long raw = setField(cookie.getValue(), FLOW_FORWARD_DIRECTION_FLAG, forward);
        raw = setField(raw, FLOW_REVERSE_DIRECTION_FLAG, reverse);
        return new Cookie(raw);
    }

    public Cookie setFlowEffectiveId(Cookie cookie, long effectiveId) {
        return new Cookie(setField(cookie.getValue(), FLOW_EFFECTIVE_ID_FIELD, effectiveId));
    }

    public long getFlowEffectiveId(Cookie cookie) {
        return getField(cookie.getValue(), FLOW_EFFECTIVE_ID_FIELD);
    }

    protected FlowSegmentCookieSchema() {
        super();
    }
}
