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

import org.openkilda.floodlight.command.flow.ingress.OneSwitchFlowInstallCommand;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.metadata.RoutingMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.SwitchId;

import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionGotoTable;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionWriteMetadata;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

abstract class OneSwitchFlowInstallFlowModFactoryTest extends IngressFlowModFactoryTest {
    private static final FlowEndpoint egressEndpointZeroVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()),
            endpointSingleVlan.getPortNumber() + 10, 0);
    private static final FlowEndpoint egressEndpointSingleVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()),
            endpointSingleVlan.getPortNumber() + 10, endpointSingleVlan.getOuterVlanId() + 10);
    private static final FlowEndpoint egressEndpointDoubleVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()),
            endpointDoubleVlan.getPortNumber() + 10,
            endpointDoubleVlan.getOuterVlanId() + 10, endpointDoubleVlan.getInnerVlanId() + 10);

    // --- makeDefaultPortForwardMessage

    @Test
    public void makeDefaultPortForwardMessageMessageMeteredZeroToSingle() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointZeroVlan, egressEndpointSingleVlan, meterConfig);
        testMakeDefaultPortForwardMessageMessage(command);
    }

    @Test
    public void makeDefaultPortForwardMessageMessageMeteredZeroToDouble() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointZeroVlan, egressEndpointDoubleVlan, meterConfig);
        testMakeDefaultPortForwardMessageMessage(command);
    }

    private void testMakeDefaultPortForwardMessageMessage(OneSwitchFlowInstallCommand command) {
        OFFlowAdd expected = makeForwardingMessage(
                command, -1,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(), getTargetIngressTableId(), Collections.emptyList());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeDefaultPortForwardMessage(getEffectiveMeterId(
                        command.getMeterConfig())));
    }

    // --- makeOuterOnlyVlanForwardMessage

    @Test
    public void makeOuterOnlyVlanForwardMessageSamePort() {
        FlowEndpoint egress = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(),
                endpointSingleVlan.getOuterVlanId() + 1);
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egress, meterConfig);
        testMakeOuterOnlyVlanForwardMessage(command);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeterless() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointSingleVlan, egressEndpointSingleVlan, null);
        testMakeOuterOnlyVlanForwardMessage(command);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeteredOneToOne() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointSingleVlan, egressEndpointSingleVlan, meterConfig);
        testMakeOuterOnlyVlanForwardMessage(command);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeteredOneToZero() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointSingleVlan, egressEndpointZeroVlan, meterConfig);
        testMakeOuterOnlyVlanForwardMessage(command);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeteredZeroToOne() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointZeroVlan, egressEndpointSingleVlan, meterConfig);
        testMakeOuterOnlyVlanForwardMessage(command);
    }

    @Test
    public void makeOuterOnlyVlanForwardMessageMeteredZeroToZero() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointZeroVlan, egressEndpointZeroVlan, meterConfig);
        testMakeOuterOnlyVlanForwardMessage(command);
    }

    private void testMakeOuterOnlyVlanForwardMessage(OneSwitchFlowInstallCommand command) {
        OFFlowAdd expected = makeForwardingMessage(
                command, 0,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getOuterVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(), getTargetIngressTableId(), command.getEndpoint().getVlanStack());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeOuterOnlyVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    // --- makeSingleVlanForwardMessage
    @Test
    public void makeSingleVlanForwardMessageMeterless() {
        testMakeSingleVlanForwardMessage(null);
    }

    @Test
    public void makeSingleVlanForwardMessageMetered() {
        testMakeSingleVlanForwardMessage(meterConfig);
    }

    private void testMakeSingleVlanForwardMessage(MeterConfig meter) {
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egressEndpointSingleVlan, meter);
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(Collections.emptySet());
        OFFlowAdd expected = makeForwardingMessage(
                command, 0,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build(), getTargetIngressTableId(), FlowEndpoint.makeVlanStack(endpoint.getInnerVlanId()));
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
        OneSwitchFlowInstallCommand command = makeCommand(endpointDoubleVlan, egressEndpointDoubleVlan, meter);
        FlowEndpoint endpoint = command.getEndpoint();
        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(endpoint.getOuterVlanId())
                .build(Collections.emptySet());
        OFFlowAdd expected = makeForwardingMessage(
                command, 10,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), endpoint.getInnerVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()))
                        .build(), getTargetIngressTableId(), FlowEndpoint.makeVlanStack(endpoint.getInnerVlanId()));
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeDoubleVlanForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    // --- service methods

    private OFFlowAdd makeForwardingMessage(
            OneSwitchFlowInstallCommand command, int priorityOffset,
            Match match, TableId tableId, List<Integer> vlanStack) {
        List<OFAction> applyActions = new ArrayList<>();
        List<OFInstruction> instructions = new ArrayList<>();
        if (command.getMeterConfig() != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, command.getMeterConfig().getId(), applyActions, instructions);
        }

        applyActions.addAll(OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, vlanStack, command.getEgressEndpoint().getVlanStack()));

        FlowEndpoint ingress = command.getEndpoint();
        FlowEndpoint egress = command.getEgressEndpoint();
        OFPort outPort = ingress.getPortNumber() == egress.getPortNumber()
                ? OFPort.IN_PORT
                : OFPort.of(egress.getPortNumber());
        applyActions.add(of.actions().buildOutput().setPort(outPort).build());
        instructions.add(of.instructions().applyActions(applyActions));
        getGoToTableInstruction().ifPresent(instructions::add);
        getWriteMetadataInstruction().ifPresent(instructions::add);

        return of.buildFlowAdd()
                .setTableId(tableId)
                .setPriority(OneSwitchFlowInstallCommand.FLOW_PRIORITY + priorityOffset)
                .setCookie(U64.of(command.getCookie().getValue()))
                .setMatch(match)
                .setInstructions(instructions)
                .build();
    }

    @Override
    IngressFlowModFactory makeFactory() {
        return makeFactory(makeCommand());
    }

    abstract IngressFlowModFactory makeFactory(OneSwitchFlowInstallCommand command);

    OneSwitchFlowInstallCommand makeCommand() {
        return makeCommand(endpointSingleVlan, egressEndpointSingleVlan, meterConfig);
    }

    OneSwitchFlowInstallCommand makeCommand(
            FlowEndpoint endpoint, FlowEndpoint egressEndpoint, MeterConfig meterConfig) {
        UUID commandId = UUID.randomUUID();
        return new OneSwitchFlowInstallCommand(
                new MessageContext(commandId.toString()), commandId, makeMetadata(), endpoint, meterConfig,
                egressEndpoint, RulesContext.builder().build(), -1);
    }

    abstract FlowSegmentMetadata makeMetadata();

    abstract Optional<OFInstructionGotoTable> getGoToTableInstruction();

    abstract Optional<OFInstructionWriteMetadata> getWriteMetadataInstruction();
}
