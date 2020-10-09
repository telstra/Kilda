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

package org.openkilda.floodlight.api.request;

import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@Getter
@ToString
@EqualsAndHashCode(callSuper = false)
public abstract class SpeakerRequest extends AbstractMessage {
    @JsonProperty("switch_id")
    protected final SwitchId switchId;

    @JsonProperty("command_id")
    protected final UUID commandId;

    @JsonProperty("send_time")
    public long sendTime;

    public SpeakerRequest(MessageContext messageContext, @NonNull SwitchId switchId, @NonNull UUID commandId,
                          long sendTime) {
        super(messageContext);

        this.switchId = switchId;
        this.commandId = commandId;
        this.sendTime = sendTime;
    }
}
