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

import static org.openkilda.floodlight.switchmanager.SwitchManager.STUB_VXLAN_UDP_SRC;

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.command.flow.NotIngressFlowSegmentCommand;
import org.openkilda.floodlight.error.NotImplementedEncapsulationException;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.service.session.Session;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.floodlight.utils.metadata.AppsMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.Utils;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MirrorConfig;
import org.openkilda.model.cookie.CookieBase.CookieType;
import org.openkilda.model.cookie.FlowSegmentCookie;

import lombok.Getter;
import lombok.NonNull;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
abstract class EgressFlowSegmentCommand extends NotIngressFlowSegmentCommand {
    protected final FlowEndpoint endpoint;
    protected final FlowEndpoint ingressEndpoint;
    protected final MirrorConfig mirrorConfig;

    @SuppressWarnings("squid:S00107")
    EgressFlowSegmentCommand(
            MessageContext messageContext, UUID commandId, FlowSegmentMetadata metadata,
            @NonNull FlowEndpoint endpoint, @NonNull FlowEndpoint ingressEndpoint, int islPort,
            FlowTransitEncapsulation encapsulation, OfFlowModBuilderFactory flowModBuilderFactory,
            MirrorConfig mirrorConfig) {
        super(
                messageContext, endpoint.getSwitchId(), commandId, metadata, islPort, encapsulation,
                flowModBuilderFactory);
        this.endpoint = endpoint;
        this.ingressEndpoint = ingressEndpoint;
        this.mirrorConfig = MirrorConfig.builder().mirrorPort(10).mirrorVlan(100).build();
    }

    @Override
    protected CompletableFuture<FlowSegmentReport> makeExecutePlan(
            SpeakerCommandProcessor commandProcessor) {

        List<OFFlowMod> ofMessages = new ArrayList<>();
        ofMessages.add(makeEgressModMessage());
        if (mirrorConfig != null) {
            ofMessages.add(makeEgressMirrorModMessage());
        }
        List<CompletableFuture<Optional<OFMessage>>> writeResults = new ArrayList<>(ofMessages.size());
        try (Session session = getSessionService().open(messageContext, getSw())) {
            for (OFFlowMod message : ofMessages) {
                writeResults.add(session.write(message));
            }
        }
        return CompletableFuture.allOf(writeResults.toArray(new CompletableFuture[0]))
                .thenApply(ignore -> makeSuccessReport());
    }

    protected OFFlowMod makeEgressModMessage() {
        OFFactory of = getSw().getOFFactory();

        return flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.EGRESS_TABLE_ID))
                .setCookie(U64.of(metadata.getCookie().getValue()))
                .setMatch(makeTransitMatch(of))
                .setInstructions(makeEgressModMessageInstructions(of))
                .build();
    }

    protected OFFlowMod makeEgressMirrorModMessage() {
        OFFactory of = getSw().getOFFactory();
        AppsMetadata appsMetadata = AppsMetadata.builder().encapsulationId(getEncapsulation().getId())
                .isForward(false).build(getSwitchFeatures());
        List<OFAction> actions = new ArrayList<>();

        actions.addAll(transformMirror(of));
        actions.add(of.actions().buildPushVlan().setEthertype(EthType.of(Utils.ETH_TYPE)).build());
        actions.add(of.actions().buildSetField().setField(of.oxms().buildVlanVid()
                .setValue(OFVlanVidMatch.ofVlan(mirrorConfig.getMirrorVlan())).build())
                .build());
        actions.add(of.actions().buildOutput()
                .setPort(OFPort.of(mirrorConfig.getMirrorPort()))
                .build());

        OFFlowMod mod = flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.APPLICATONS_TABLE_ID))
                .setCookie(U64.of(new FlowSegmentCookie(getCookie().getValue()).toBuilder()
                        .type(CookieType.APPLICATION_MIRROR_EGRESS).build().getValue()))
                .setMatch(of.buildMatch()
                        .setMasked(MatchField.METADATA, OFMetadata.of(appsMetadata.getValue()),
                                OFMetadata.of(appsMetadata.getMask()))
                        .build())
                .setInstructions(Collections.singletonList(of.instructions().applyActions(actions))).build();
        return mod;
    }

    private List<OFAction> transformMirror(OFFactory of) {
        switch (encapsulation.getType()) {
            case TRANSIT_VLAN:
                return buildVlanForMirror(of);
            case VXLAN:
                return buildVxlanForMirror(of);
            default:
                throw new NotImplementedEncapsulationException(
                        getClass(), encapsulation.getType(), switchId, metadata.getFlowId());
        }
    }

    private List<OFAction> buildVlanForMirror(OFFactory of) {
        List<OFAction> actions = new ArrayList<>();
        actions.add(of.actions().pushVlan(EthType.VLAN_FRAME));
        actions.add(of.actions().buildSetField().setField(of.oxms().buildVlanVid()
                .setValue(OFVlanVidMatch.ofVlan(encapsulation.getId())).build())
                .build());
        return actions;
    }

    private List<OFAction> buildVxlanForMirror(OFFactory of) {
        return Collections.singletonList(of.actions().buildNoviflowPushVxlanTunnel()
                .setVni(getEncapsulation().getId())
                .setEthSrc(MacAddress.of(getSw().getId()))
                .setEthDst(MacAddress.of(ingressEndpoint.getSwitchId().toLong()))
                .setUdpSrc(STUB_VXLAN_UDP_SRC)
                .setIpv4Src(IPv4Address.of("127.0.0.1"))
                .setIpv4Dst(IPv4Address.of("127.0.0.2"))
                .setFlags((short) 0x01)
                .build());
    }


    protected abstract List<OFInstruction> makeEgressModMessageInstructions(OFFactory of);

    public String toString() {
        return String.format(
                "<egress-flow-segment-%s{"
                        + "id=%s, metadata=%s, endpoint=%s, ingressEndpoint=%s, isl_port=%s, encapsulation=%s}>",
                getSegmentAction(), commandId, metadata, endpoint, ingressEndpoint, ingressIslPort, encapsulation);
    }

    protected abstract SegmentAction getSegmentAction();
}
