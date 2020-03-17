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

import org.junit.Assert;

abstract class GenericCookieSchemaTest {
    protected final CookieSchemaStub schema = new CookieSchemaStub();

    protected void testFieldsIntersection(CookieSchema.BitField[] fields) {
        CookieSchemaStub schema = new CookieSchemaStub();
        for (int leftIdx = 0; leftIdx < fields.length; leftIdx++) {
            long leftField = schema.setField(new Cookie(0L), fields[leftIdx], -1L).getValue();

            for (int rightIdx = 0; rightIdx < fields.length; rightIdx++) {
                if (leftIdx == rightIdx) {
                    continue;
                }

                long rightField = schema.setField(new Cookie(0L), fields[rightIdx], -1).getValue();
                Assert.assertEquals(
                        String.format(
                                "Detect cookie fields collision between 0x%016x and 0x%016x", leftField, rightField),
                        0, (leftField & rightField));
            }
        }
    }

    protected void testFieldReadWrite(Cookie cookie, long expected, CookieSchema.BitField field, long value) {
        testFieldReadWrite(cookie, expected, field, value, value);
    }

    protected void testFieldReadWrite(
            Cookie cookie, long expected, CookieSchema.BitField field, long valueWrite, long valueRead) {
        Cookie result = schema.setField(cookie, field, valueWrite);
        Assert.assertEquals(expected, result.getValue());
        Assert.assertEquals(valueRead, schema.getField(result, field));
    }
}
