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

package org.openkilda.messaging.payload;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@JsonNaming(SnakeCaseStrategy.class)
public class SimpleMessage extends Message {
    public String message;

    @JsonCreator
    public SimpleMessage(@JsonProperty("timestamp") long timestamp,
                         @JsonProperty("correlation_id") String correlationId,
                         @JsonProperty("destination") Destination destination,
                         @JsonProperty("message") String message) {
        super(timestamp, correlationId, destination);
        this.message = message;
    }

    public SimpleMessage(String message) {
        this(System.currentTimeMillis(), UUID.randomUUID().toString(), null, message);
    }
}
