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

import org.openkilda.model.FlowPathStatus;

import org.junit.Test;

public class FlowPathStatusConverterTest {
    @Test
    public void shouldConvertToGraphProperty() {
        FlowPathStatusConverter converter = new FlowPathStatusConverter();

        assertEquals("active", converter.toGraphProperty(FlowPathStatus.ACTIVE));
        assertEquals("inactive", converter.toGraphProperty(FlowPathStatus.INACTIVE));
        assertEquals("in_progress", converter.toGraphProperty(FlowPathStatus.IN_PROGRESS));
    }

    @Test
    public void shouldConvertToEntity() {
        FlowPathStatusConverter converter = new FlowPathStatusConverter();

        assertEquals(FlowPathStatus.ACTIVE, converter.toEntityAttribute("ACTIVE"));
        assertEquals(FlowPathStatus.ACTIVE, converter.toEntityAttribute("active"));
        assertEquals(FlowPathStatus.IN_PROGRESS, converter.toEntityAttribute("In_Progress"));
    }
}
