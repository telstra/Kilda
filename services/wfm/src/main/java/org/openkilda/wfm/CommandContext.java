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

package org.openkilda.wfm;

import org.openkilda.applications.AppMessage;
import org.openkilda.messaging.Message;

import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

/**
 * Class that contains command context information.
 * @deprecated {@link org.openkilda.messaging.MessageContext} should be used instead.
 */
@Data
@Deprecated
public class CommandContext implements Serializable {
    private final String correlationId;
    private final long createTime;

    public CommandContext() {
        this(UUID.randomUUID().toString());
    }

    public CommandContext(Message message) {
        this(message.getCorrelationId(), message.getTimestamp());
    }

    public CommandContext(AppMessage message) {
        this(message.getCorrelationId(), message.getTimestamp());
    }

    public CommandContext(String correlationId) {
        this(correlationId, System.currentTimeMillis());
    }

    protected CommandContext(String correlationId, long createTime) {
        this.correlationId = correlationId;
        this.createTime = createTime;
    }

    /**
     * Create new {@link CommandContext} object using data from current one. Produced object receive extended/nested
     * correlation ID i.e. it contain original correlation ID plus part passed in argument.
     */
    public CommandContext fork(String correlationIdExtension) {
        CommandContext nested = new CommandContext(correlationId + " : " + correlationIdExtension);
        nested.merge(this);
        return nested;
    }

    /**
     * Merge data from other CommandContext object.
     *
     * <p>Become useful when part of processing is done in external "branch" and separate CommandContext was
     * created.
     */
    public void merge(CommandContext other) { }
}
