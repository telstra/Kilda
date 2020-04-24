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

import org.openkilda.model.SwitchId;

import org.junit.Test;

public class SwitchIdConverterTest {
    @Test
    public void shouldConvertIdToString() {
        // given
        SwitchId switchId = new SwitchId((long) 0x123);

        // when
        String graphObject = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);

        // then
        assertEquals(switchId.toString(), graphObject);
    }

    @Test
    public void shouldConvertStringToId() {
        // given
        SwitchId switchId = new SwitchId((long) 0x123);

        // when
        SwitchId actualEntity = SwitchIdConverter.INSTANCE.toEntityAttribute(switchId.toString());

        // then
        assertEquals(switchId, actualEntity);
    }
}
