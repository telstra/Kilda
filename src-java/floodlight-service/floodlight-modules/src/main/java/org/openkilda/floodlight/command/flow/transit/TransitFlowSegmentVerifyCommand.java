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

package org.openkilda.floodlight.command.flow.transit;

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TransitFlowSegmentVerifyCommand extends TransitFlowSegmentInstallCommand {
    @JsonCreator
    public TransitFlowSegmentVerifyCommand(
            @JsonProperty("message_context") MessageContext context,
            @JsonProperty("switch_id") SwitchId switchId,
            @JsonProperty("command_id") UUID commandId,
            @JsonProperty("metadata") FlowSegmentMetadata metadata,
            @JsonProperty("ingress_isl_port") int ingressIslPort,
            @JsonProperty("encapsulation") FlowTransitEncapsulation encapsulation,
            @JsonProperty("egress_isl_port") int egressIslPort,
            @JsonProperty("send_time") long sendTime) {
        super(context, switchId, commandId, metadata, ingressIslPort, encapsulation, egressIslPort, sendTime);
    }

    @Override
    protected CompletableFuture<FlowSegmentReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor) {
        return makeVerifyPlan(ImmutableList.of(makeTransitModMessage()));
    }

    @Override
    protected SegmentAction getSegmentAction() {
        return SegmentAction.VERIFY;
    }
}
