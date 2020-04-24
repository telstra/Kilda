/* Copyright 2018 Telstra Open Source
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

package org.openkilda.persistence.ferma.frames.converters;

import static org.junit.Assert.assertEquals;

import org.openkilda.model.SwitchStatus;

import org.junit.Test;

public class SwitchStatusConverterTest {
    @Test
    public void shouldConvertToGraphProperty() {
        SwitchStatusConverter converter = SwitchStatusConverter.INSTANCE;

        assertEquals("active", converter.toGraphProperty(SwitchStatus.ACTIVE));
        assertEquals("inactive", converter.toGraphProperty(SwitchStatus.INACTIVE));
    }

    @Test
    public void shouldConvertToEntity() {
        SwitchStatusConverter converter = SwitchStatusConverter.INSTANCE;

        assertEquals(SwitchStatus.ACTIVE, converter.toEntityAttribute("ACTIVE"));
        assertEquals(SwitchStatus.ACTIVE, converter.toEntityAttribute("active"));
        assertEquals(SwitchStatus.INACTIVE, converter.toEntityAttribute("InActive"));
    }
}
