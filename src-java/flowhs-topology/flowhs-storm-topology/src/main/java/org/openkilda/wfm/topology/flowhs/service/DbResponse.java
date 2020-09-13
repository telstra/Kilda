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

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.MessageContext;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@JsonNaming(SnakeCaseStrategy.class)
@ToString
@EqualsAndHashCode(callSuper = true)
public class DbResponse extends AbstractMessage {
    protected UUID commandId;

    public boolean isSuccess() {
        return !(this instanceof DbErrorResponse);
    }

    @JsonCreator
    public DbResponse(@NonNull MessageContext messageContext, UUID commandId) {
        super(messageContext);
        this.commandId = commandId;
    }
}
