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

import org.openkilda.model.Cookie;

import org.junit.Test;

public class FlowSegmentCookieSchemaTest extends GenericCookieSchemaTest {
    @Test
    public void flowForwardDirectionFlagLocation() {
        testFieldReadWrite(
                new Cookie(-1L), ~0x4000_0000_0000_0000L, FlowSegmentCookieSchema.FLOW_FORWARD_DIRECTION_FLAG, 0);
    }

    @Test
    public void flowReverseDirectionFlagLocation() {
        testFieldReadWrite(
                new Cookie(-1L), ~0x2000_0000_0000_0000L, FlowSegmentCookieSchema.FLOW_REVERSE_DIRECTION_FLAG, 0);
    }

    @Test
    public void effectiveFlowIdFieldLocation() {
        testFieldReadWrite(
                new Cookie(-1), ~0x0000_0000_000F_FFFF, FlowSegmentCookieSchema.FLOW_EFFECTIVE_ID_FIELD, 0);
    }

    @Test
    public void ensureNoFieldsIntersection() {
        testFieldsIntersection(FlowSegmentCookieSchema.ALL_FIELDS);
    }
}
