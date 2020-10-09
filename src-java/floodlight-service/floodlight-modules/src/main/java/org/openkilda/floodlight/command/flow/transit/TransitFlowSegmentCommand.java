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
import org.openkilda.floodlight.command.flow.NotIngressFlowSegmentCommand;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.service.session.Session;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.SwitchId;

import lombok.Getter;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
abstract class TransitFlowSegmentCommand extends NotIngressFlowSegmentCommand {
    protected final int egressIslPort;

    @SuppressWarnings("squid:S00107")
    TransitFlowSegmentCommand(
            MessageContext messageContext, SwitchId switchId, UUID commandId, FlowSegmentMetadata metadata,
            int ingressIslPort, FlowTransitEncapsulation encapsulation, int egressIslPort,
            OfFlowModBuilderFactory flowModBuilderFactory, long sendTime) {
        super(messageContext, switchId, commandId, metadata, ingressIslPort, encapsulation, flowModBuilderFactory,
                sendTime);
        this.egressIslPort = egressIslPort;
    }

    @Override
    protected CompletableFuture<FlowSegmentReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor) {
        try (Session session = getSessionService().open(messageContext, getSw())) {
            return session.write(makeTransitModMessage())
                    .thenApply(ignore -> makeSuccessReport());
        }
    }

    protected OFFlowMod makeTransitModMessage() {
        OFFactory of = getSw().getOFFactory();
        return flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.TRANSIT_TABLE_ID))
                .setCookie(U64.of(metadata.getCookie().getValue()))
                .setInstructions(makeTransitModMessageInstructions(of))
                .setMatch(makeTransitMatch(of))
                .build();
    }

    protected abstract List<OFInstruction> makeTransitModMessageInstructions(OFFactory of);

    public String toString() {
        return String.format(
                "<transit-flow-segment-%s{"
                        + "id=%s, metadata=%s, ingress_isl_port=%s, egress_isl_port=%s, encapsulation=%s}>",
                getSegmentAction(), commandId, metadata, ingressIslPort, egressIslPort, encapsulation);
    }

    protected abstract SegmentAction getSegmentAction();
}
