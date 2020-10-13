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

import org.openkilda.serializer.PathIdSerializer;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.NonNull;
import lombok.Value;

import java.io.Serializable;

/**
 * Represents a flow path id.
 */
@Value
@DefaultSerializer(PathIdSerializer.class)
public class PathId implements Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String id;

    public PathId(String id) {
        this.id = id;
    }

    @JsonValue
    @Override
    public String toString() {
        return id;
    }
}
