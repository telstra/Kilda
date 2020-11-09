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
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.bitops.BitField;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.google.common.collect.ImmutableSet;
import lombok.Builder;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Set;

public class FlowSegmentCookie extends Cookie {
    // update ALL_FIELDS if modify fields list
    //                                     used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField FLOW_EFFECTIVE_ID_FIELD     = new BitField(0x0000_0000_000F_FFFFL);
    static final BitField FLOW_REVERSE_DIRECTION_FLAG = new BitField(0x2000_0000_0000_0000L);
    static final BitField FLOW_FORWARD_DIRECTION_FLAG = new BitField(0x4000_0000_0000_0000L);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(
            CookieBase.ALL_FIELDS, FLOW_FORWARD_DIRECTION_FLAG, FLOW_REVERSE_DIRECTION_FLAG, FLOW_EFFECTIVE_ID_FIELD);

    private static final Set<CookieType> VALID_TYPES = ImmutableSet.of(
                    CookieType.SERVICE_OR_FLOW_SEGMENT,
                    CookieType.SERVER_42_INGRESS, CookieType.APPLICATION_MIRROR_FLOW,
                    CookieType.APPLICATION_MIRROR_EGRESS);

    @JsonCreator
    public FlowSegmentCookie(long value) {
        super(value);
    }

    public FlowSegmentCookie(FlowPathDirection direction, long flowEffectiveId) {
        this(CookieType.SERVICE_OR_FLOW_SEGMENT, direction, flowEffectiveId);
    }

    FlowSegmentCookie(CookieType type, long value) {
        super(value, type);
    }

    @Builder
    private FlowSegmentCookie(CookieType type, FlowPathDirection direction, long flowEffectiveId) {
        super(makeValue(type, direction, flowEffectiveId), type);
    }

    @Override
    public void validate() throws InvalidCookieException {
        super.validate();

        validateServiceFlag(false);

        CookieType type = getType();
        if (!VALID_TYPES.contains(type)) {
            throw new InvalidCookieException(formatIllegalTypeError(type, VALID_TYPES), this);
        }

        int directionBitsSetCount = 0;
        BitField[] mutuallyExclusiveFlags = {FLOW_FORWARD_DIRECTION_FLAG, FLOW_REVERSE_DIRECTION_FLAG};
        for (BitField field : mutuallyExclusiveFlags) {
            directionBitsSetCount += getField(field);
        }

        if (1 < directionBitsSetCount) {
            throw new InvalidCookieException("Illegal flags combination - both the direction bits are set", this);
        }
    }

    @Override
    public FlowSegmentCookieBuilder toBuilder() {
        return new FlowSegmentCookieBuilder()
                .type(getType())
                .direction(getDirection())
                .flowEffectiveId(getFlowEffectiveId());
    }

    /**
     * Read the direction bits and return direction as {@link FlowPathDirection} constant.
     *
     * <p>Raise {@link IllegalArgumentException} if all direction bits are equal to 0.
     */
    public FlowPathDirection getValidatedDirection() {
        FlowPathDirection direction = getDirection();
        if (FlowPathDirection.UNDEFINED == direction) {
            throw new IllegalArgumentException(String.format("Cookie %s have no direction marker", this));
        }
        return direction;
    }

    /**
     * Extract and return normalized representation flow path direction.
     */
    public FlowPathDirection getDirection() {
        if (getField(FLOW_FORWARD_DIRECTION_FLAG) != 0) {
            return FlowPathDirection.FORWARD;
        } else if (getField(FLOW_REVERSE_DIRECTION_FLAG) != 0) {
            return FlowPathDirection.REVERSE;
        } else {
            return FlowPathDirection.UNDEFINED;
        }
    }

    public long getFlowEffectiveId() {
        return getField(FLOW_EFFECTIVE_ID_FIELD);
    }

    public static FlowSegmentCookieBuilder builder() {
        return new FlowSegmentCookieBuilder()
                .type(CookieType.SERVICE_OR_FLOW_SEGMENT);
    }

    private static long makeValue(CookieType type, FlowPathDirection direction, long flowEffectiveId) {
        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException(formatIllegalTypeError(type, VALID_TYPES));
        }

        long value = 0;
        if (direction != null) {
            value = makeValueDirection(direction);
        }
        return setField(value, FLOW_EFFECTIVE_ID_FIELD, flowEffectiveId);
    }

    /**
     * Set direction bits to the value passed as directions argument.
     */
    protected static long makeValueDirection(FlowPathDirection direction) {
        int forward = 0;
        int reverse = 0;
        switch (direction) {
            case FORWARD:
                forward = 1;
                break;
            case REVERSE:
                reverse = 1;
                break;
            case UNDEFINED:
                // nothing to do
                break;
            default:
                throw new IllegalArgumentException(String.format(
                        "Unable to map %s.%s into cookie direction bits",
                        FlowPathDirection.class.getSimpleName(), direction));
        }

        long value = setField(0, FLOW_FORWARD_DIRECTION_FLAG, forward);
        return setField(value, FLOW_REVERSE_DIRECTION_FLAG, reverse);
    }

    /**
     * Need to declare builder inheritance, to be able to override {@code toBuilder()} method.
     */
    public static class FlowSegmentCookieBuilder extends CookieBuilder {
        // lombok is responsible for injecting here all required methods fields
    }
}
