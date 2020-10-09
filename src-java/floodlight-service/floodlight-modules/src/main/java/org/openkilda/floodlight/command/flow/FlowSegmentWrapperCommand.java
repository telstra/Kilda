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

package org.openkilda.floodlight.command.flow;

import org.openkilda.floodlight.command.SpeakerCommand;
import org.openkilda.floodlight.command.SpeakerCommandProcessor;

import lombok.Getter;

import java.util.concurrent.CompletableFuture;

public class FlowSegmentWrapperCommand extends SpeakerCommand<FlowSegmentWrapperReport> {
    @Getter
    private final FlowSegmentCommand target;

    @Getter
    private final FlowSegmentResponseFactory responseFactory;

    public FlowSegmentWrapperCommand(FlowSegmentCommand target, FlowSegmentResponseFactory responseFactory) {
        super(target.getMessageContext(), target.getSwitchId(), target.sendTime);
        this.target = target;
        this.responseFactory = responseFactory;
    }

    @Override
    protected CompletableFuture<FlowSegmentWrapperReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor) {
        return commandProcessor.chain(target)
                .thenApply(this::handleTargetReport);
    }

    @Override
    protected FlowSegmentWrapperReport makeReport(Exception error) {
        return new FlowSegmentWrapperReport(this, responseFactory, error);
    }

    private FlowSegmentWrapperReport handleTargetReport(FlowSegmentReport report) {
        try {
            report.raiseError();
        } catch (Exception e) {
            throw maskCallbackException(e);
        }
        return new FlowSegmentWrapperReport(this, responseFactory);
    }

    @Override
    public String toString() {
        return String.format(
                "<flow-segment-wrapper{target=%s, outputTopic=%s}>", target, responseFactory.getTargetKafkaTopic());
    }
}
