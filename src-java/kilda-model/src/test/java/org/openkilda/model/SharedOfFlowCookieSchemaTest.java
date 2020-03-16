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

import org.openkilda.model.Cookie.BitField;
import org.openkilda.model.cookie.SharedOfFlowCookieSchema;

import org.junit.Test;

import java.util.stream.Stream;

public class SharedOfFlowCookieSchemaTest extends AbstractCookieTest {
    @Test
    public void ensureIngressSegmentNoFieldsIntersection() {
        // FIXME(surabujin) FLOW_EFFECTIVE_ID_FIELD must be defined only for flow-segment cookie, not for generic cookie
        BitField[] genericFields = Stream.of(Cookie.ALL_FIELDS)
                .filter(entry -> entry != Cookie.FLOW_EFFECTIVE_ID_FIELD)
                .toArray(BitField[]::new);
        testFieldsIntersection(SharedOfFlowCookieSchema.ALL_FIELDS, genericFields);
    }
}
