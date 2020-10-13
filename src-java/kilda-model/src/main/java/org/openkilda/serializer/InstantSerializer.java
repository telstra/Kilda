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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.time.Instant;

public class InstantSerializer extends Serializer<Instant> {

    /**
     * .
     */
    public void write(Kryo kryo, Output output, Instant instant) {
        output.writeLong(instant.getEpochSecond());
        output.writeInt(instant.getNano());
    }

    /**
     * .
     */
    public Instant read(Kryo kryo, Input input, Class<Instant> type) {
        long seconds = input.readLong();
        int nanos = input.readInt();
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
