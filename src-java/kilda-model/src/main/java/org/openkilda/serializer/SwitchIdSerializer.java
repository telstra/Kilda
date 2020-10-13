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

package org.openkilda.serializer;

import org.openkilda.model.SwitchId;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class SwitchIdSerializer extends Serializer<SwitchId> {
    public void write(Kryo kryo, Output output, SwitchId switchId) {
        output.writeLong(switchId.getId());
    }

    public SwitchId read(Kryo kryo, Input input, Class<SwitchId> type) {
        return new SwitchId(input.readLong());
    }
}
