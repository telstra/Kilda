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

import org.openkilda.model.FlowStatus;

import org.junit.Test;

public class FlowStatusConverterTest {
    @Test
    public void shouldConvertToGraphProperty() {
        FlowStatusConverter converter = FlowStatusConverter.INSTANCE;

        assertEquals("up", converter.toGraphProperty(FlowStatus.UP));
        assertEquals("down", converter.toGraphProperty(FlowStatus.DOWN));
        assertEquals("in_progress", converter.toGraphProperty(FlowStatus.IN_PROGRESS));
    }

    @Test
    public void shouldConvertToEntity() {
        FlowStatusConverter converter = FlowStatusConverter.INSTANCE;

        assertEquals(FlowStatus.UP, converter.toEntityAttribute("UP"));
        assertEquals(FlowStatus.UP, converter.toEntityAttribute("up"));
        assertEquals(FlowStatus.IN_PROGRESS, converter.toEntityAttribute("In_Progress"));
    }
}
