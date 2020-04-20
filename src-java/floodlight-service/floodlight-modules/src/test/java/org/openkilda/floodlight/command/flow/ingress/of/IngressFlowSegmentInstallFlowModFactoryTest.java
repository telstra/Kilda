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

import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentInstallCommand;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.SwitchId;

import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionGotoTable;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

abstract class IngressFlowSegmentInstallFlowModFactoryTest extends IngressFlowModFactoryTest {
    // --- makeOuterVlanOnlyForwardMessage

    @Test
    public void makeOuterVlanOnlyForwardMessageMeterlessVlanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, null, encapsulationVlan);
        processMakeOuterVlanOnlyForwardMessageVlan(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeteredVlanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, meterConfig, encapsulationVlan);
        processMakeOuterVlanOnlyForwardMessageVlan(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeteredVxLanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(
                endpointSingleVlan, meterConfig, encapsulationVxLan);
        processMakeOuterVlanOnlyForwardMessageVxLan(command);
    }

    public void processMakeOuterVlanOnlyForwardMessageVlan(IngressFlowSegmentInstallCommand command) {
        OFFlowAdd expected = makeVlanForwardingMessage(
                command, 0,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(),
                getTargetTableId());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeOuterVlanOnlyForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    public void processMakeOuterVlanOnlyForwardMessageVxLan(IngressFlowSegmentInstallCommand command) {
        OFFlowAdd expected = makeVxLanForwardingMessage(
                command, 0,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(),
                getTargetTableId());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeOuterVlanOnlyForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageConnectedDevices() {
        FlowEndpoint endpoint = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(), endpointSingleVlan.getVlanId(),
                true, true);
        IngressFlowSegmentInstallCommand command = makeCommand(endpoint, meterConfig, encapsulationVlan);

        IngressFlowModFactory factory = makeFactory(command);
        verifyGoToTableInstruction(getGoToTableInstruction().map(OFInstructionGotoTable::getTableId),
                factory.makeOuterVlanOnlyForwardMessage(meterConfig.getId()));
    }

    // --- makeDefaultPortFlowMatchAndForward

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeterlessVlanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointZeroVlan, null, encapsulationVlan);
        processMakeDefaultPortFlowMatchAndForwardMessageVlan(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeteredVlanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointZeroVlan, meterConfig, encapsulationVlan);
        processMakeDefaultPortFlowMatchAndForwardMessageVlan(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeteredVxLanEncoded() {
        IngressFlowSegmentInstallCommand command = makeCommand(endpointZeroVlan, meterConfig, encapsulationVxLan);
        processMakeDefaultPortFlowMatchAndForwardMessageVxLan(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageConnectedDevices() {
        FlowEndpoint endpoint = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(), endpointSingleVlan.getVlanId(),
                true, true);
        IngressFlowSegmentInstallCommand command = makeCommand(endpoint, meterConfig, encapsulationVlan);

        IngressFlowModFactory factory = makeFactory(command);
        verifyGoToTableInstruction(getGoToTableInstruction().map(OFInstructionGotoTable::getTableId),
                factory.makeDefaultPortFlowMatchAndForwardMessage(meterConfig.getId()));
    }

    public void processMakeDefaultPortFlowMatchAndForwardMessageVlan(IngressFlowSegmentInstallCommand command) {
        OFFlowAdd expected = makeVlanForwardingMessage(
                command, -1, of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(),
                getTargetTableId());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(expected, factory.makeDefaultPortFlowMatchAndForwardMessage(getEffectiveMeterId(
                command.getMeterConfig())));
    }

    public void processMakeDefaultPortFlowMatchAndForwardMessageVxLan(IngressFlowSegmentInstallCommand command) {
        OFFlowAdd expected = makeVxLanForwardingMessage(
                command, -1,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(),
                getTargetTableId());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(expected, factory.makeDefaultPortFlowMatchAndForwardMessage(
                getEffectiveMeterId(command.getMeterConfig())));
    }

    // --- service methods

    protected OFFlowAdd makeVlanForwardingMessage(
            IngressFlowSegmentInstallCommand command, int prioOffset, Match match, TableId tableId) {
        List<OFAction> applyActions = new ArrayList<>();
        List<OFInstruction> instructions = new ArrayList<>();
        if (command.getMeterConfig() != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, command.getMeterConfig().getId(), applyActions, instructions);
        }
        instructions.add(of.instructions().applyActions(applyActions));
        getGoToTableInstruction().ifPresent(instructions::add);
        if (! FlowEndpoint.isVlanIdSet(command.getEndpoint().getVlanId())) {
            applyActions.add(of.actions().pushVlan(EthType.VLAN_FRAME));
        }
        applyActions.add(OfAdapter.INSTANCE.setVlanIdAction(of, command.getEncapsulation().getId()));
        applyActions.add(of.actions().buildOutput().setPort(OFPort.of(command.getIslPort())).build());

        return of.buildFlowAdd()
                .setTableId(tableId)
                .setPriority(IngressFlowSegmentInstallCommand.FLOW_PRIORITY + prioOffset)
                .setCookie(U64.of(command.getCookie().getValue()))
                .setMatch(match)
                .setInstructions(instructions)
                .build();
    }

    protected OFFlowAdd makeVxLanForwardingMessage(
            IngressFlowSegmentInstallCommand command, int prioOffset, Match match, TableId tableId) {
        List<OFInstruction> instructions = new ArrayList<>();
        List<OFAction> applyActions = new ArrayList<>();
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
        applyActions.add(of.actions().buildNoviflowCopyField()
                .setOxmSrcHeader(of.oxms().buildNoviflowPacketOffset().getTypeLen())
                .setSrcOffset(56 * 8)
                .setOxmDstHeader(of.oxms().buildNoviflowPacketOffset().getTypeLen())
                .setDstOffset(6 * 8)
                .setNBits(MacAddress.BROADCAST.getLength() * 8)
                .build());
        applyActions.add(of.actions().buildOutput().setPort(OFPort.of(command.getIslPort())).build());
        instructions.add(of.instructions().applyActions(applyActions));
        getGoToTableInstruction().ifPresent(instructions::add);

        return of.buildFlowAdd()
                .setTableId(tableId)
                .setPriority(IngressFlowSegmentInstallCommand.FLOW_PRIORITY + prioOffset)
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
                RulesContext.builder().build());
    }

    abstract FlowSegmentMetadata makeMetadata();

    abstract TableId getTargetTableId();

    abstract Optional<OFInstructionGotoTable> getGoToTableInstruction();
}
