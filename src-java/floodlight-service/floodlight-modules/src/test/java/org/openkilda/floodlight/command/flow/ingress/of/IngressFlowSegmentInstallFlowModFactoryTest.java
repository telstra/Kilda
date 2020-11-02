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

import org.openkilda.floodlight.command.flow.FlowSegmentCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentInstallCommand;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.metadata.RoutingMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.SwitchId;

import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

abstract class IngressFlowSegmentInstallFlowModFactoryTest extends IngressFlowModFactoryTest {
    // --- makeOuterOnlyVlanForwardMessage

    @Test
    public void makeOuterOnlyVlanForwardMessageMeterlessVlanEncoded() {
        testMakeOuterOnlyVlanForwardMessageVlanEncoded(null);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeteredVlanEncoded() {
        testMakeOuterOnlyVlanForwardMessageVlanEncoded(meterConfig);
    }

    private void testMakeOuterOnlyVlanForwardMessageVlanEncoded(MeterConfig meter) {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, meter, encapsulationVlan);
        FlowEndpoint endpoint = command.getEndpoint();

        List<OFAction> vlanTransformation = OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, endpoint.getVlanStack(), makeTransitVlanStack(endpoint, command.getEncapsulation().getId()));
        OFFlowAdd expected = makeVlanForwardingMessage(
                command, 0,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getOuterVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(), getTargetIngressTableId(), vlanTransformation);
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeOuterOnlyVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeterlessVxLanEncoded() {
        testMakeOuterOnlyVlanForwardMessageVxLanEncoded(null);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeteredVxLanEncoded() {
        testMakeOuterOnlyVlanForwardMessageVxLanEncoded(meterConfig);
    }

    private void testMakeOuterOnlyVlanForwardMessageVxLanEncoded(MeterConfig meter) {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, meter, encapsulationVxLan);

        List<OFAction> vlanTransformation = OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, command.getEndpoint().getVlanStack(), Collections.emptyList());
        OFFlowAdd expected = makeVxLanForwardingMessage(
                command, 0,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getOuterVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(), getTargetIngressTableId(), vlanTransformation);
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeOuterOnlyVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageConnectedDevices() {
        FlowEndpoint endpoint = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(),
                endpointSingleVlan.getOuterVlanId(), 0, true, true);
        IngressFlowSegmentInstallCommand command = makeCommand(endpoint, meterConfig, encapsulationVlan);

        IngressFlowModFactory factory = makeFactory(command);
        verifyGoToTableInstruction(factory.makeOuterOnlyVlanForwardMessage(meterConfig.getId()));
    }

    // --- makeDefaultPortForwardMessage

    @Test
    public void makeDefaultPortForwardMessageMeterlessVlanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointZeroVlan, null, encapsulationVlan);
        testMakeDefaultPortForwardMessageVlan(command);
    }

    @Test
    public void makeDefaultPortForwardMessageMeteredVlanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointZeroVlan, meterConfig, encapsulationVlan);
        testMakeDefaultPortForwardMessageVlan(command);
    }

    @Test
    public void makeDefaultPortForwardMessageMeteredVxLanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointZeroVlan, meterConfig, encapsulationVxLan);
        testMakeDefaultPortForwardMessageVxLan(command);
    }

    @Test
    public void makeDefaultPortForwardMessageConnectedDevices() {
        FlowEndpoint endpoint = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(),
                endpointSingleVlan.getOuterVlanId(), 0, true, true);
        IngressFlowSegmentInstallCommand command = makeCommand(endpoint, meterConfig, encapsulationVlan);

        IngressFlowModFactory factory = makeFactory(command);
        verifyGoToTableInstruction(factory.makeDefaultPortForwardMessage(meterConfig.getId()));
    }

    private void testMakeDefaultPortForwardMessageVlan(IngressFlowSegmentInstallCommand command) {
        List<OFAction> vlanTransformation = OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, Collections.emptyList(), Collections.singletonList(command.getEncapsulation().getId()));
        OFFlowAdd expected = makeVlanForwardingMessage(
                command, -1, of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(), getTargetIngressTableId(), vlanTransformation);
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(expected, factory.makeDefaultPortForwardMessage(getEffectiveMeterId(
                command.getMeterConfig())));
    }

    private void testMakeDefaultPortForwardMessageVxLan(IngressFlowSegmentInstallCommand command) {
        OFFlowAdd expected = makeVxLanForwardingMessage(
                command, -1,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(), getTargetIngressTableId(), Collections.emptyList());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(expected, factory.makeDefaultPortForwardMessage(
                getEffectiveMeterId(command.getMeterConfig())));
    }

    // --- makeSingleVlanForwardMessage

    @Test
    public void makeSingleVlanForwardMessageMeterlessVlanEncoded() {
        testMakeSingleVlanForwardMessageVlanEncoded(null);
    }

    @Test
    public void makeSingleVlanForwardMessageMeteredVlanEncoded() {
        testMakeSingleVlanForwardMessageVlanEncoded(meterConfig);
    }

    private void testMakeSingleVlanForwardMessageVlanEncoded(MeterConfig meter) {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, meter, encapsulationVlan);

        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(Collections.emptySet());
        List<OFAction> vlanTransformation = OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, Collections.emptyList(), Collections.singletonList(command.getEncapsulation().getId()));
        OFFlowAdd expected = makeVlanForwardingMessage(
                command, 0,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build(), getTargetIngressTableId(), vlanTransformation);
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeSingleVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    @Test
    public void makeSingleVlanForwardMessageMeterlessVxLanEncoded() {
        testMakeSingleVlanForwardMessageVxLanEncoded(meterConfig);
    }

    @Test
    public void makeSingleVlanForwardMessageMeteredVxLanEncoded() {
        testMakeSingleVlanForwardMessageVxLanEncoded(null);
    }

    private void testMakeSingleVlanForwardMessageVxLanEncoded(MeterConfig meter) {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, meter, encapsulationVxLan);

        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(Collections.emptySet());
        OFFlowAdd expected = makeVxLanForwardingMessage(
                command, 0,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build(), getTargetIngressTableId(), Collections.emptyList());

        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeSingleVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    // --- makeDoubleVlanForwardMessage

    @Test
    public void makeDoubleVlanForwardMessageMeterlessVlanEncoded() {
        testMakeDoubleVlanForwardMessageVlanEncoded(null);
    }

    @Test
    public void makeDoubleVlanForwardMessageMeteredVlanEncoded() {
        testMakeDoubleVlanForwardMessageVlanEncoded(meterConfig);
    }

    private void testMakeDoubleVlanForwardMessageVlanEncoded(MeterConfig meter) {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointDoubleVlan, meter, encapsulationVlan);

        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(Collections.emptySet());
        List<OFAction> vlanTransformation = OfAdapter.INSTANCE.makeVlanReplaceActions(
                of,
                FlowEndpoint.makeVlanStack(endpoint.getInnerVlanId()),
                FlowEndpoint.makeVlanStack(command.getEncapsulation().getId()));
        OFFlowAdd expected = makeVlanForwardingMessage(
                command, 10,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), endpoint.getInnerVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build(), getTargetIngressTableId(), vlanTransformation);
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeDoubleVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    @Test
    public void makeDoubleVlanForwardMessageMeterlessVxLanEncoded() {
        testMakeDoubleVlanForwardMessageVxLanEncoded(null);
    }

    @Test
    public void makeDoubleVlanForwardMessageMeteredVxLanEncoded() {
        testMakeDoubleVlanForwardMessageVxLanEncoded(meterConfig);
    }

    private void testMakeDoubleVlanForwardMessageVxLanEncoded(MeterConfig meter) {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointDoubleVlan, meter, encapsulationVxLan);

        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(Collections.emptySet());
        List<OFAction> vlanTransformation = OfAdapter.INSTANCE.makeVlanReplaceActions(
                of,
                FlowEndpoint.makeVlanStack(endpoint.getInnerVlanId()), Collections.emptyList());
        OFFlowAdd expected = makeVxLanForwardingMessage(
                command, 10,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), endpoint.getInnerVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build(), getTargetIngressTableId(), vlanTransformation);
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeDoubleVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    // --- service methods

    private OFFlowAdd makeVlanForwardingMessage(
            IngressFlowSegmentInstallCommand command, int priorityOffset,
            Match match, TableId tableId, List<OFAction> vlanTransformation) {
        List<OFAction> applyActions = new ArrayList<>();
        List<OFInstruction> instructions = new ArrayList<>();
        if (command.getMeterConfig() != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, command.getMeterConfig().getId(), applyActions, instructions);
        }
        instructions.add(of.instructions().applyActions(applyActions));
        applyActions.addAll(vlanTransformation);
        applyActions.add(of.actions().buildOutput().setPort(OFPort.of(command.getIslPort())).build());
        if (expectPostIngressTableRedirect()) {
            instructions.add(of.instructions().gotoTable(TableId.of(SwitchManager.POST_INGRESS_TABLE_ID)));
        }

        return of.buildFlowAdd()
                .setTableId(tableId)
                .setPriority(FlowSegmentCommand.FLOW_PRIORITY + priorityOffset)
                .setCookie(U64.of(command.getCookie().getValue()))
                .setMatch(match)
                .setInstructions(instructions)
                .build();
    }

    private OFFlowAdd makeVxLanForwardingMessage(
            IngressFlowSegmentInstallCommand command, int priorityOffset,
            Match match, TableId tableId, List<OFAction> vlanTransformation) {
        List<OFInstruction> instructions = new ArrayList<>();
        List<OFAction> applyActions = new ArrayList<>(vlanTransformation);
        if (command.getMeterConfig() != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, command.getMeterConfig().getId(), applyActions, instructions);
        }
        applyActions.add(of.actions().buildNoviflowPushVxlanTunnel()
                .setEthSrc(MacAddress.of(DatapathId.of(command.getSwitchId().getId())))
                .setEthDst(MacAddress.of(command.getEgressSwitchId().toLong()))
                .setIpv4Src(IPv4Address.of("127.0.0.1"))
                .setIpv4Dst(IPv4Address.of("127.0.0.2"))
                .setUdpSrc(4500)
                .setVni(command.getEncapsulation().getId())
                .setFlags((short) 0x01)
                .build());
        applyActions.add(of.actions().buildOutput().setPort(OFPort.of(command.getIslPort())).build());
        instructions.add(of.instructions().applyActions(applyActions));
        if (expectPostIngressTableRedirect()) {
            instructions.add(of.instructions().gotoTable(TableId.of(SwitchManager.POST_INGRESS_TABLE_ID)));
        }

        return of.buildFlowAdd()
                .setTableId(tableId)
                .setPriority(FlowSegmentCommand.FLOW_PRIORITY + priorityOffset)
                .setCookie(U64.of(command.getCookie().getValue()))
                .setMatch(match)
                .setInstructions(instructions)
                .build();
    }

    @Override
    IngressFlowModFactory makeFactory() {
        return makeFactory(makeCommand());
    }

    abstract IngressFlowModFactory makeFactory(IngressFlowSegmentInstallCommand command);

    IngressFlowSegmentInstallCommand makeCommand() {
        return makeCommand(endpointSingleVlan, meterConfig, encapsulationVlan);
    }

    IngressFlowSegmentInstallCommand makeCommand(
            FlowEndpoint endpoint, MeterConfig meterConfig, FlowTransitEncapsulation encapsulation) {
        UUID commandId = UUID.randomUUID();
        return new IngressFlowSegmentInstallCommand(
                new MessageContext(commandId.toString()), commandId, makeMetadata(), endpoint, meterConfig,
                new SwitchId(datapathIdBeta.getLong()), 1, encapsulation,
                RulesContext.builder().build(), null);
    }

    private List<Integer> makeTransitVlanStack(FlowEndpoint ingressEndpoint, int transitVlanId) {
        List<Integer> vlanStack = ingressEndpoint.getVlanStack();
        if (vlanStack.isEmpty()) {
            vlanStack.add(transitVlanId);
        } else {
            vlanStack.set(0, transitVlanId);
        }
        return vlanStack;
    }

    abstract FlowSegmentMetadata makeMetadata();
}
