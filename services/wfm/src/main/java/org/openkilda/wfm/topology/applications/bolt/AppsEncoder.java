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

package org.openkilda.wfm.topology.applications.bolt;

import org.openkilda.applications.AppData;
import org.openkilda.applications.AppMessage;
import org.openkilda.applications.command.CommandAppData;
import org.openkilda.applications.command.CommandAppMessage;
import org.openkilda.applications.error.ErrorAppData;
import org.openkilda.applications.error.ErrorAppMessage;
import org.openkilda.applications.info.InfoAppData;
import org.openkilda.applications.info.InfoAppMessage;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.bolt.KafkaEncoder;
import org.openkilda.wfm.topology.applications.AppsTopology.ComponentId;

import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class AppsEncoder extends KafkaEncoder {
    public static final String BOLT_ID = ComponentId.NOTIFICATION_ENCODER.toString();
    public static final String INPUT_STREAM_ID = "notification.stream";

    @Override
    protected void handleInput(Tuple input) throws Exception {
        AppData payload = pullAppPayload(input);
        try {
            AppMessage message = wrap(pullContext(input), payload);
            getOutput().emit(input, new Values(pullKey(input), message));
        } catch (IllegalArgumentException e) {
            log.error(e.getMessage());
            unhandledInput(input);
        }
    }

    protected AppData pullAppPayload(Tuple input) throws PipelineException {
        return pullValue(input, FIELD_ID_PAYLOAD, AppData.class);
    }

    protected AppMessage wrap(CommandContext commandContext, AppData payload) {
        AppMessage message = null;
        if (payload instanceof CommandAppData) {
            message = wrapCommand(commandContext, (CommandAppData) payload);
        } else if (payload instanceof InfoAppData) {
            message = wrapInfo(commandContext, (InfoAppData) payload);
        } else if (payload instanceof ErrorAppData) {
            message = wrapError(commandContext, (ErrorAppData) payload);
        } else {
            throw new IllegalArgumentException(String.format("There is not rule to build envelope for: %s", payload));
        }

        return message;
    }

    private CommandAppMessage wrapCommand(CommandContext commandContext, CommandAppData payload) {
        return new CommandAppMessage(System.currentTimeMillis(), commandContext.getCorrelationId(), payload);
    }

    private InfoAppMessage wrapInfo(CommandContext commandContext, InfoAppData payload) {
        return new InfoAppMessage(System.currentTimeMillis(), commandContext.getCorrelationId(), payload);
    }

    private ErrorAppMessage wrapError(CommandContext commandContext, ErrorAppData payload) {
        return new ErrorAppMessage(System.currentTimeMillis(), commandContext.getCorrelationId(), payload);
    }
}
