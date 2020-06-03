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

package org.openkilda.messaging;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = PROPERTY, property = "clazz")
@Getter
@ToString
@EqualsAndHashCode
public abstract class AbstractMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    @Setter
    @JsonProperty("message_context")
    protected MessageContext messageContext;

    public AbstractMessage(@NonNull MessageContext messageContext) {
        this.messageContext = messageContext;
    }
}
