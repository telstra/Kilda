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

import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_FORWARD_UDP_PORT;
import static org.openkilda.floodlight.switchmanager.SwitchManager.STUB_VXLAN_UDP_SRC;
import static org.openkilda.floodlight.switchmanager.factory.generator.server42.Server42InputFlowGenerator.buildServer42CopyFirstTimestamp;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentCommand;
import org.openkilda.floodlight.error.NotImplementedEncapsulationException;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.floodlight.utils.metadata.AppsMetadata;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.SwitchFeature;

import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionNoviflowPushVxlanTunnel;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

abstract class IngressFlowSegmentInstallFlowModFactory extends IngressInstallFlowModFactory {
    private final IngressFlowSegmentCommand command;

    public IngressFlowSegmentInstallFlowModFactory(
            OfFlowModBuilderFactory flowModBuilderFactory, IngressFlowSegmentCommand command, IOFSwitch sw,
            Set<SwitchFeature> features) {
        super(flowModBuilderFactory, command, sw, features);
        this.command = command;
    }

    @Override
    protected List<OFAction> makeTransformActions(List<Integer> vlanStack) {
        List<OFAction> actions = new ArrayList<>();
        FlowTransitEncapsulation encapsulation = command.getEncapsulation();
        switch (encapsulation.getType()) {
            case TRANSIT_VLAN:
                actions.addAll(makeVlanEncapsulationTransformActions(vlanStack));
                break;
            case VXLAN:
                actions.addAll(makeVxLanEncapsulationTransformActions(vlanStack));
                break;
            default:
                throw new NotImplementedEncapsulationException(
                        getClass(), encapsulation.getType(), command.getSwitchId(), command.getMetadata().getFlowId());
        }
        return actions;
    }

    @Override
    protected List<OFAction> makeServer42IngressFlowTransformActions(List<Integer> vlanStack) {
        List<OFAction> actions = new ArrayList<>();
        FlowTransitEncapsulation encapsulation = command.getEncapsulation();
        switch (encapsulation.getType()) {
            case TRANSIT_VLAN:
                MacAddress ethSrc = MacAddress.of(sw.getId());
                MacAddress ethDst = MacAddress.of(command.getEgressSwitchId().toLong());

                actions.add(of.actions().setField(of.oxms().ethSrc(ethSrc)));
                actions.add(of.actions().setField(of.oxms().ethDst(ethDst)));

                if (!getCommand().getMetadata().isMultiTable()) {
                    actions.add(of.actions().setField(of.oxms().udpSrc(TransportPort.of(SERVER_42_FORWARD_UDP_PORT))));
                    actions.add(of.actions().setField(of.oxms().udpDst(TransportPort.of(SERVER_42_FORWARD_UDP_PORT))));
                    if (switchFeatures.contains(NOVIFLOW_COPY_FIELD)) {
                        actions.add(buildServer42CopyFirstTimestamp(of));
                    }
                }
                actions.addAll(makeVlanEncapsulationTransformActions(vlanStack));
                break;
            case VXLAN:
                if (vlanStack.isEmpty()) {
                    // Server 42 requires constant number of Vlans in packet.
                    // TRANSIT_VLAN encapsulation always keeps 1 Vlan in packet.
                    // VXLAN encapsulation for NON default port also keeps 1 Vlan in packet.
                    // VXLAN encapsulation for default port has no Vlans in packet so we will add one fake Vlan
                    actions.add(of.actions().pushVlan(EthType.VLAN_FRAME));
                }
                if (!getCommand().getMetadata().isMultiTable() && switchFeatures.contains(NOVIFLOW_COPY_FIELD)) {
                    actions.add(buildServer42CopyFirstTimestamp(of));
                }
                actions.add(pushVxlanAction(SERVER_42_FORWARD_UDP_PORT));
                break;
            default:
                throw new NotImplementedEncapsulationException(
                        getClass(), encapsulation.getType(), command.getSwitchId(), command.getMetadata().getFlowId());
        }
        return actions;
    }

    private List<OFAction> makeVlanEncapsulationTransformActions(List<Integer> vlanStack) {
        return OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, vlanStack, FlowEndpoint.makeVlanStack(command.getEncapsulation().getId()));
    }

    private List<OFAction> makeVxLanEncapsulationTransformActions(List<Integer> vlanStack) {
        // Remove any remaining vlan's, because egress switch will reject vlan manipulation on flow that have no
        // vlan header matches
        List<OFAction> actions = new ArrayList<>(
                OfAdapter.INSTANCE.makeVlanReplaceActions(of, vlanStack, Collections.emptyList()));

        actions.add(pushVxlanAction(STUB_VXLAN_UDP_SRC));

        return actions;
    }

    private OFActionNoviflowPushVxlanTunnel pushVxlanAction(int udpSrcPort) {
        return of.actions().buildNoviflowPushVxlanTunnel()
                .setVni(command.getEncapsulation().getId())
                .setEthSrc(MacAddress.of(sw.getId()))
                .setEthDst(MacAddress.of(command.getEgressSwitchId().toLong()))
                // nobody can explain why we use this constant
                .setUdpSrc(udpSrcPort)
                .setIpv4Src(IPv4Address.of("127.0.0.1"))
                .setIpv4Dst(IPv4Address.of("127.0.0.2"))
                // Set to 0x01 indicating tunnel data is present (i.e. we are passing l2 and l3 headers in this action)
                .setFlags((short) 0x01)
                .build();
    }

    @Override
    protected OFAction makeOutputAction() {
        return super.makeOutputAction(OFPort.of(command.getIslPort()));
    }

    @Override
    protected List<OFInstruction> makeMetadataInstructions() {
        if (command.getRulesContext().isMirrorPacketToApps()) {
            AppsMetadata metadata = AppsMetadata.builder()
                    .encapsulationId(command.getEncapsulation().getId()).isForward(true).build(switchFeatures);
            return Collections.singletonList(of.instructions().buildWriteMetadata()
                    .setMetadata(metadata.getValue())
                    .setMetadataMask(metadata.getMask())
                    .build());
        }
        return Collections.emptyList();
    }
}
