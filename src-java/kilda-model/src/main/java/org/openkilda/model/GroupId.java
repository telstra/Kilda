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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Value;

import java.io.Serializable;

@Value
public final class GroupId implements Comparable<GroupId>, Serializable {
    private static final long serialVersionUID = 1L;

    public static final GroupId MIN_FLOW_GROUP_ID = new GroupId(2);

    public static final GroupId MAX_FLOW_GROUP_ID = new GroupId(2500);

    private final long value;

    @JsonCreator
    public GroupId(long value) {
        this.value = value;
    }

    @JsonValue
    public long getValue() {
        return value;
    }

    public int intValue() {
        return (int) value;
    }

    @Override
    public int compareTo(GroupId compareWith) {
        return Long.compare(value, compareWith.value);
    }
}
