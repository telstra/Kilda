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

package org.openkilda.floodlight.command.flow.ingress.of;

import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_INGRESS_DEFAULT_FLOW_PRIORITY_OFFSET;
import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_INGRESS_DOUBLE_VLAN_FLOW_PRIORITY_OFFSET;

import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentBase;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.switchmanager.factory.generator.server42.Server42InputFlowGenerator;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.floodlight.utils.metadata.RoutingMetadata;
import org.openkilda.floodlight.utils.metadata.RoutingMetadata.RoutingMetadataBuilder;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.cookie.Cookie;
import org.openkilda.model.cookie.CookieBase.CookieType;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSharedSegmentCookie;
import org.openkilda.model.cookie.FlowSharedSegmentCookie.SharedSegmentType;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import lombok.AccessLevel;
import lombok.Getter;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowMod.Builder;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public abstract class IngressFlowModFactory {
    @VisibleForTesting
    @Getter(AccessLevel.MODULE)
    final IngressFlowSegmentBase command;

    protected final Set<SwitchFeature> switchFeatures;

    protected final IOFSwitch sw;

    protected final OFFactory of;

    protected final OfFlowModBuilderFactory flowModBuilderFactory;

    public IngressFlowModFactory(
            OfFlowModBuilderFactory flowModBuilderFactory, IngressFlowSegmentBase command, IOFSwitch sw,
            Set<SwitchFeature> features) {
        this.flowModBuilderFactory = flowModBuilderFactory;
        this.command = command;
        this.sw = sw;
        this.switchFeatures = features;

        of = sw.getOFFactory();
    }

    /**
     * Make rule to match traffic by port+vlan and route it into ISL/egress end.
     */
    public OFFlowMod makeOuterOnlyVlanForwardMessage(MeterId effectiveMeterId) {
        FlowEndpoint endpoint = command.getEndpoint();
        OFFlowMod.Builder builder = flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setMatch(OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), endpoint.getOuterVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .build());
        return makeForwardMessage(builder, effectiveMeterId, FlowEndpoint.makeVlanStack(endpoint.getOuterVlanId()));
    }

    /**
     * Make rule to forward traffic matched by outer VLAN tag and forward in in ISL (or out port in case one-switch
     * flow).
     */
    public OFFlowMod makeSingleVlanForwardMessage(MeterId effectiveMeterId) {
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(switchFeatures);
        OFFlowMod.Builder builder = flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build());
        // Outer VLAN tag already removed, no inner VLAN must be set for this kind or rules, so we can/should pass empty
        // list as current vlanStack
        return makeForwardMessage(builder, effectiveMeterId, Collections.emptyList());
    }

    /**
     * Make rule to match inner VLAN tag and forward in in ISL (or out port in case one-switch flow).
     */
    public OFFlowMod makeDoubleVlanForwardMessage(MeterId effectiveMeterId) {
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(switchFeatures);
        OFFlowMod.Builder builder = flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID), 10)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(endpoint.getInnerVlanId()))
                        .setMasked(MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build());
        return makeForwardMessage(builder, effectiveMeterId, FlowEndpoint.makeVlanStack(endpoint.getInnerVlanId()));
    }

    /**
     * Make rule to match whole port traffic and route it into ISL/egress end.
     */
    public OFFlowMod makeDefaultPortForwardMessage(MeterId effectiveMeterId) {
        // FIXME we need some space between match rules (so priorityOffset should be -10 instead of -1)
        OFFlowMod.Builder builder = flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID), -1)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build());
        return makeForwardMessage(builder, effectiveMeterId, Collections.emptyList());
    }

    /**
     * Make rule to match traffic by port and vlan, write vlan into metadata and pass packet into "next" table. This
     * rule is shared across all flow with equal port and vlan(outer).
     */
    public OFFlowMod makeOuterVlanMatchSharedMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        FlowSharedSegmentCookie cookie = FlowSharedSegmentCookie.builder(SharedSegmentType.QINQ_OUTER_VLAN)
                .portNumber(endpoint.getPortNumber())
                .vlanId(endpoint.getOuterVlanId())
                .build();
        return flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.PRE_INGRESS_TABLE_ID))
                .setCookie(U64.of(cookie.getValue()))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(endpoint.getOuterVlanId()))
                        .build())
                .setInstructions(makeOuterVlanMatchInstructions())
                .build();
    }

    /**
     * Make rule to match traffic by server 42 port and vlan, write vlan into metadata and pass packet into next table.
     * This rule is shared across all flow with equal vlan(outer).
     */
    public OFFlowMod makeServer42OuterVlanMatchSharedMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        FlowSharedSegmentCookie cookie = FlowSharedSegmentCookie.builder(SharedSegmentType.SERVER42_QINQ_OUTER_VLAN)
                .portNumber(getCommand().getRulesContext().getServer42Port())
                .vlanId(endpoint.getOuterVlanId())
                .build();
        return flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.PRE_INGRESS_TABLE_ID))
                .setCookie(U64.of(cookie.getValue()))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(getCommand().getRulesContext().getServer42Port()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(endpoint.getOuterVlanId()))
                        .build())
                .setInstructions(makeOuterVlanMatchInstructions())
                .build();
    }

    /**
     * Make server 42 ingress rule to match RTT packets by port+vlan and route it into ISL/egress end.
     */
    public OFFlowMod makeOuterOnlyVlanServer42IngressFlowMessage(int server42UpdPortOffset) {
        Match match = makeServer42IngressFlowMatch(
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getOuterVlanId()),
                server42UpdPortOffset);

        OFFlowMod.Builder builder = flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setMatch(match);
        return makeServer42IngressFlowMessage(
                builder, FlowEndpoint.makeVlanStack(command.getEndpoint().getOuterVlanId()));
    }

    private Match makeServer42IngressFlowMatch(Match.Builder builder, int server42UpdPortOffset) {
        builder.setExact(MatchField.IN_PORT, OFPort.of(command.getRulesContext().getServer42Port()));

        if (getCommand().getMetadata().isMultiTable()) {
            RoutingMetadata metadata = buildServer42IngressMetadata();

            builder.setMasked(MatchField.METADATA,
                    OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                    .build();
        } else {
            MacAddress macAddress = MacAddress.of(getCommand().getRulesContext().getServer42MacAddress().toString());
            int udpSrcPort = server42UpdPortOffset + command.getEndpoint().getPortNumber();

            builder.setExact(MatchField.ETH_SRC, macAddress)
                    .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                    .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                    .setExact(MatchField.UDP_SRC, TransportPort.of(udpSrcPort));
        }

        return builder.build();
    }

    /**
     * Make server 42 ingress rule to match all RTT packets traffic and route it into ISL/egress end.
     */
    public OFFlowMod makeDefaultPortServer42IngressFlowMessage(int server42UpdPortOffset) {
        Match match = makeServer42IngressFlowMatch(of.buildMatch(), server42UpdPortOffset);

        OFFlowMod.Builder builder = flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID),
                SERVER_42_INGRESS_DEFAULT_FLOW_PRIORITY_OFFSET)
                .setMatch(match);
        return makeServer42IngressFlowMessage(builder, Collections.emptyList());
    }

    /**
     * Make rule to match server 42 packets by inner VLAN tag and forward in in ISL.
     */
    public OFFlowMod makeDoubleServer42IngressFlowMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = buildServer42IngressMetadata();
        OFFlowMod.Builder builder = flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID),
                        SERVER_42_INGRESS_DOUBLE_VLAN_FLOW_PRIORITY_OFFSET)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(getCommand().getRulesContext().getServer42Port()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(endpoint.getInnerVlanId()))
                        .setMasked(MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build());
        return makeServer42IngressFlowMessage(builder, FlowEndpoint.makeVlanStack(endpoint.getInnerVlanId()));
    }

    /**
     * Make rule to forward server 42 traffic matched by outer VLAN tag and forward in in ISL.
     */
    public OFFlowMod makeSingleVlanServer42IngressFlowMessage() {
        RoutingMetadata metadata = buildServer42IngressMetadata();
        OFFlowMod.Builder builder = flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getRulesContext().getServer42Port()))
                        .setMasked(MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build());
        return makeServer42IngressFlowMessage(builder, Collections.emptyList());
    }

    /**
     * Route all traffic for specific physical port into pre-ingress table. Shared across all flows for this physical
     * port (if OF flow-mod ADD message use same match and priority fields with existing OF flow, existing OF flow will
     * be replaced/not added).
     */
    public OFFlowMod makeCustomerPortSharedCatchMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        return flowModBuilderFactory.makeBuilder(of, TableId.of(SwitchManager.INPUT_TABLE_ID))
                .setPriority(SwitchManager.INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE)
                .setCookie(U64.of(Cookie.encodeIngressRulePassThrough(endpoint.getPortNumber())))
                .setMatch(of.buildMatch()
                                  .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                                  .build())
                .setInstructions(makeCustomerPortSharedCatchInstructions())
                .build();
    }

    /**
     * Route all LLDP traffic for specific physical port into pre-ingress table and mark it by metadata. Shared across
     * all flows for this physical (if OF flow-mod ADD message use same match and priority fields with existing OF flow,
     * existing OF flow will be replaced/not added).
     */
    public OFFlowMod makeLldpInputCustomerFlowMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        return flowModBuilderFactory.makeBuilder(of, SwitchManager.INPUT_TABLE_ID)
                .setPriority(SwitchManager.LLDP_INPUT_CUSTOMER_PRIORITY)
                .setCookie(U64.of(Cookie.encodeLldpInputCustomer(endpoint.getPortNumber())))
                .setCookieMask(U64.NO_MASK)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setExact(MatchField.ETH_TYPE, EthType.LLDP)
                        .build())
                .setInstructions(makeConnectedDevicesMatchInstructions(
                        RoutingMetadata.builder().lldpFlag(true).build(switchFeatures)))
                .build();
    }

    /**
     * Route all ARP traffic for specific physical port into pre-ingress table and mark it by metadata. Shared across
     * all flows for this physical (if OF flow-mod ADD message use same match and priority fields with existing OF flow,
     * existing OF flow will be replaced/not added).
     */
    public OFFlowMod makeArpInputCustomerFlowMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        return flowModBuilderFactory.makeBuilder(of, SwitchManager.INPUT_TABLE_ID)
                .setPriority(SwitchManager.ARP_INPUT_CUSTOMER_PRIORITY)
                .setCookie(U64.of(Cookie.encodeArpInputCustomer(endpoint.getPortNumber())))
                .setCookieMask(U64.NO_MASK)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setExact(MatchField.ETH_TYPE, EthType.ARP)
                        .build())
                .setInstructions(makeConnectedDevicesMatchInstructions(
                        RoutingMetadata.builder().arpFlag(true).build(switchFeatures)))
                .build();
    }

    /**
     * Route RTT packet received from Server 42 to Pre Ingress table. Shared across all flows for this physical port
     * (if OF flow-mod ADD message use same match and priority fields with existing OF flow,
     * existing OF flow will be replaced/not added).
     * If rule could not be installed on this switch Optional.empty() will be returned.
     */
    public Optional<OFFlowMod> makeServer42InputFlowMessage(int server42UpdPortOffset) {
        RulesContext context = command.getRulesContext();

        Optional<OFFlowMod> flowMod = Server42InputFlowGenerator.generateFlowMod(of, switchFeatures,
                server42UpdPortOffset, command.getEndpoint().getPortNumber(), context.getServer42Port(),
                context.getServer42MacAddress());

        return flowMod.map(ofFlowMod -> flowModBuilderFactory.makeBuilder(of, ofFlowMod.getTableId())
                .setPriority(ofFlowMod.getPriority())
                .setCookie(ofFlowMod.getCookie())
                .setCookieMask(ofFlowMod.getCookieMask())
                .setMatch(ofFlowMod.getMatch())
                .setInstructions(ofFlowMod.getInstructions())
                .build());
    }

    /**
     * Make ingress flow loop rule to match all port traffic and route it back to port from where it came.
     */
    public OFFlowMod makeDefaultPortIngressFlowLoopMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        return flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID), -10)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .build())
                .setCookie(U64.of(command.getCookie().getValue()))
                .setInstructions(makeIngressFlowLoopInstructions(endpoint))
                .build();
    }

    /**
     * Make ingress flow loop rule to match all flow traffic by port and two vlans and route it back to port from
     * where it came and restore vlan stack.
     */
    public OFFlowMod makeDoubleVlanFlowLoopMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(switchFeatures);

        return flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID), 10)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(endpoint.getInnerVlanId()))
                        .setMasked(MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build())
                .setCookie(U64.of(command.getCookie().getValue()))
                .setInstructions(makeIngressFlowLoopInstructions(endpoint))
                .build();
    }

    /**
     * Make ingress flow loop rule to match all flow traffic by port&vlan and route it back to port from
     * where it came.
     */
    public OFFlowMod makeSingleVlanFlowLoopMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(switchFeatures);

        return flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build())
                .setCookie(U64.of(command.getCookie().getValue()))
                .setInstructions(makeIngressFlowLoopInstructions(endpoint))
                .build();
    }

    /**
     * Make ingress flow loop rule to match all flow traffic by port and outer vlan and route it back to port from
     * where it came.
     */
    public OFFlowMod makeOuterOnlyVlanIngressFlowLoopMessage() {
        FlowEndpoint endpoint = command.getEndpoint();
        return flowModBuilderFactory
                .makeBuilder(of, TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setMatch(OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), endpoint.getOuterVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .build())
                .setCookie(U64.of(command.getCookie().getValue()))
                .setInstructions(makeIngressFlowLoopInstructions(endpoint))
                .build();
    }

    private OFFlowMod makeServer42IngressFlowMessage(Builder builder, List<Integer> vlanStack) {
        builder.setCookie(U64.of(buildServer42IngressCookie().getValue()));
        builder.setInstructions(makeServer42IngressFlowMessageInstructions(vlanStack));
        if (switchFeatures.contains(SwitchFeature.RESET_COUNTS_FLAG)) {
            builder.setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS));
        }
        return builder.build();
    }

    private FlowSegmentCookie buildServer42IngressCookie() {
        return new FlowSegmentCookie(command.getCookie().getValue()).toBuilder()
                .type(CookieType.SERVER_42_INGRESS)
                .build();
    }

    private RoutingMetadata buildServer42IngressMetadata() {
        RoutingMetadataBuilder builder = RoutingMetadata.builder()
                .inputPort(command.getEndpoint().getPortNumber());

        if (FlowEndpoint.isVlanIdSet(command.getEndpoint().getInnerVlanId())) {
            builder.outerVlanId(command.getEndpoint().getOuterVlanId());
        }
        return builder.build(switchFeatures);
    }

    protected abstract List<OFInstruction> makeCustomerPortSharedCatchInstructions();

    protected abstract List<OFInstruction> makeConnectedDevicesMatchInstructions(RoutingMetadata metadata);

    protected abstract List<OFInstruction> makeServer42IngressFlowMessageInstructions(List<Integer> vlanStack);

    private OFFlowMod makeForwardMessage(OFFlowMod.Builder builder, MeterId effectiveMeterId, List<Integer> vlanStack) {
        builder.setCookie(U64.of(command.getCookie().getValue()))
                .setInstructions(makeForwardMessageInstructions(effectiveMeterId, vlanStack));
        if (switchFeatures.contains(SwitchFeature.RESET_COUNTS_FLAG)) {
            builder.setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS));
        }
        return builder.build();
    }

    protected abstract List<OFInstruction> makeForwardMessageInstructions(
            MeterId effectiveMeterId, List<Integer> vlanStack);

    protected abstract List<OFInstruction> makeIngressFlowLoopInstructions(FlowEndpoint endpoint);

    protected abstract List<OFInstruction> makeOuterVlanMatchInstructions();
}
