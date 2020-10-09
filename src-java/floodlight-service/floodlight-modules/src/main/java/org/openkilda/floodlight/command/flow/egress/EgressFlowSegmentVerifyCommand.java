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

package org.openkilda.floodlight.command.flow.egress;

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EgressFlowSegmentVerifyCommand extends EgressFlowSegmentInstallCommand {
    @JsonCreator
    public EgressFlowSegmentVerifyCommand(
            @JsonProperty("message_context") MessageContext messageContext,
            @JsonProperty("command_id") UUID commandId,
            @JsonProperty("metadata") FlowSegmentMetadata metadata,
            @JsonProperty("endpoint") FlowEndpoint endpoint,
            @JsonProperty("ingress_endpoint") FlowEndpoint ingressEndpoint,
            @JsonProperty("isl_port") int islPort,
            @JsonProperty("encapsulation") FlowTransitEncapsulation encapsulation,
            @JsonProperty("send_time") long sendTime) {
        super(messageContext, commandId, metadata, endpoint, ingressEndpoint, islPort, encapsulation, sendTime);
    }

    @Override
    protected CompletableFuture<FlowSegmentReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor) {
        return makeVerifyPlan(ImmutableList.of(makeEgressModMessage()));
    }

    @Override
    protected SegmentAction getSegmentAction() {
        return SegmentAction.VERIFY;
    }
}
