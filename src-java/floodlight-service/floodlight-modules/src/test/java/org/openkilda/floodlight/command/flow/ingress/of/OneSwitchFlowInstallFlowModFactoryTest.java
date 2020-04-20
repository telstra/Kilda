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
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

abstract class OneSwitchFlowInstallFlowModFactoryTest extends IngressFlowModFactoryTest {
    protected static final FlowEndpoint egressEndpointSingleVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()),
            endpointSingleVlan.getPortNumber() + 10, endpointSingleVlan.getVlanId() + 10);
    protected static final FlowEndpoint egressEndpointZeroVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()),
            endpointSingleVlan.getPortNumber() + 10, 0);

    // --- makeOuterVlanOnlyForwardMessage

    @Test
    public void makeOuterVlanOnlyForwardMessageSamePort() {
        FlowEndpoint egress = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(),
                endpointSingleVlan.getVlanId() + 1);
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egress, meterConfig);
        processMakeOuterVlanOnlyForwardMessage(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeterless() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointSingleVlan, egressEndpointSingleVlan, null);
        processMakeOuterVlanOnlyForwardMessage(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeteredOneToOne() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointSingleVlan, egressEndpointSingleVlan, meterConfig);
        processMakeOuterVlanOnlyForwardMessage(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeteredOneToZero() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointSingleVlan, egressEndpointZeroVlan, meterConfig);
        processMakeOuterVlanOnlyForwardMessage(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeteredZeroToOne() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointZeroVlan, egressEndpointSingleVlan, meterConfig);
        processMakeOuterVlanOnlyForwardMessage(command);
    }

    @Test
    public void makeOuterVlanOnlyForwardMessageMeteredZeroToZero() {
        OneSwitchFlowInstallCommand command = makeCommand(
                endpointZeroVlan, egressEndpointZeroVlan, meterConfig);
        processMakeOuterVlanOnlyForwardMessage(command);
    }

    // --- makeDefaultPortFlowMatchAndForward

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageSamePort() {
        FlowEndpoint egress = new FlowEndpoint(
                endpointSingleVlan.getSwitchId(), endpointSingleVlan.getPortNumber(),
                endpointSingleVlan.getVlanId() + 1);
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egress, meterConfig);
        processMakeDefaultPortFlowMatchAndForwardMessage(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeterless() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egressEndpointSingleVlan, null);
        processMakeDefaultPortFlowMatchAndForwardMessage(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeteredOneToOne() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egressEndpointSingleVlan, meterConfig);
        processMakeDefaultPortFlowMatchAndForwardMessage(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeteredOneToZero() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointSingleVlan, egressEndpointZeroVlan, meterConfig);
        processMakeDefaultPortFlowMatchAndForwardMessage(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeteredZeroToOne() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointZeroVlan, egressEndpointSingleVlan, meterConfig);
        processMakeDefaultPortFlowMatchAndForwardMessage(command);
    }

    @Test
    public void makeDefaultPortFlowMatchAndForwardMessageMeteredZeroToZero() {
        OneSwitchFlowInstallCommand command = makeCommand(endpointZeroVlan, egressEndpointZeroVlan, meterConfig);
        processMakeDefaultPortFlowMatchAndForwardMessage(command);
    }

    // --- service methods

    public void processMakeOuterVlanOnlyForwardMessage(OneSwitchFlowInstallCommand command) {
        OFFlowAdd expected = makeForwardingMessage(
                command, 0,
                OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEndpoint().getVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(),
                getTargetTableId());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeOuterVlanOnlyForwardMessage(getEffectiveMeterId(command.getMeterConfig())));
    }

    public void processMakeDefaultPortFlowMatchAndForwardMessage(OneSwitchFlowInstallCommand command) {
        OFFlowAdd expected = makeForwardingMessage(
                command, -1,
                of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(command.getEndpoint().getPortNumber()))
                        .build(),
                getTargetTableId());
        IngressFlowModFactory factory = makeFactory(command);
        verifyOfMessageEquals(
                expected, factory.makeDefaultPortFlowMatchAndForwardMessage(getEffectiveMeterId(
                        command.getMeterConfig())));
    }

    private OFFlowAdd makeForwardingMessage(
            OneSwitchFlowInstallCommand command, int priorityOffset, Match match, TableId tableId) {
        List<OFAction> applyActions = new ArrayList<>();
        List<OFInstruction> instructions = new ArrayList<>();
        if (command.getMeterConfig() != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, command.getMeterConfig().getId(), applyActions, instructions);
        }
        FlowEndpoint ingress = command.getEndpoint();
        FlowEndpoint egress = command.getEgressEndpoint();
        if (FlowEndpoint.isVlanIdSet(ingress.getVlanId()) && FlowEndpoint.isVlanIdSet(egress.getVlanId())) {
            applyActions.add(OfAdapter.INSTANCE.setVlanIdAction(of, egress.getVlanId()));
        } else if (FlowEndpoint.isVlanIdSet(ingress.getVlanId()) && ! FlowEndpoint.isVlanIdSet(egress.getVlanId())) {
            applyActions.add(of.actions().popVlan());
        } else if (!FlowEndpoint.isVlanIdSet(ingress.getVlanId()) && FlowEndpoint.isVlanIdSet(egress.getVlanId())) {
            applyActions.add(of.actions().pushVlan(EthType.VLAN_FRAME));
            applyActions.add(OfAdapter.INSTANCE.setVlanIdAction(of, egress.getVlanId()));
        }
        OFPort outPort = ingress.getPortNumber().equals(egress.getPortNumber())
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
                egressEndpoint, RulesContext.builder().build());
    }

    abstract FlowSegmentMetadata makeMetadata();

    abstract TableId getTargetTableId();

    abstract Optional<OFInstructionGotoTable> getGoToTableInstruction();

    abstract Optional<OFInstructionWriteMetadata> getWriteMetadataInstruction();
}
