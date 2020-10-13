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

import org.openkilda.model.cookie.FlowSegmentCookie;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

public class FlosSegmentCookieSerializer extends Serializer<FlowSegmentCookie> {
    public void write(Kryo kryo, Output output, FlowSegmentCookie cookie) {
        output.writeLong(cookie.getValue());
    }

    public FlowSegmentCookie read(Kryo kryo, Input input, Class<FlowSegmentCookie> type) {
        return new FlowSegmentCookie(input.readLong());
    }
}
