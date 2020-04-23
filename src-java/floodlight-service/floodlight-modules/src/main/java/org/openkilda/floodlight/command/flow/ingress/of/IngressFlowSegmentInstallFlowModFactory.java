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

import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentCommand;
import org.openkilda.floodlight.error.NotImplementedEncapsulationException;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.SwitchFeature;

import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;

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
    protected List<OFAction> makeTransformActions() {
        List<OFAction> actions = new ArrayList<>();
        FlowTransitEncapsulation encapsulation = command.getEncapsulation();
        switch (encapsulation.getType()) {
            case TRANSIT_VLAN:
                actions.addAll(makeVlanEncapsulationTransformActions());
                break;
            case VXLAN:
                actions.addAll(makeVxLanEncapsulationTransformActions(STUB_VXLAN_UDP_SRC, true));
                break;
            default:
                throw new NotImplementedEncapsulationException(
                        getClass(), encapsulation.getType(), command.getSwitchId(), command.getMetadata().getFlowId());
        }
        return actions;
    }

    @Override
    protected List<OFAction> makeServer42FlowRttTransformActions() {
        List<OFAction> actions = new ArrayList<>();
        FlowTransitEncapsulation encapsulation = command.getEncapsulation();
        switch (encapsulation.getType()) {
            case TRANSIT_VLAN:
                actions.addAll(makeVlanEncapsulationServer42TransformActions());
                break;
            case VXLAN:
                actions.addAll(makeVxLanEncapsulationTransformActions(SERVER_42_FORWARD_UDP_PORT, false));
                break;
            default:
                throw new NotImplementedEncapsulationException(
                        getClass(), encapsulation.getType(), command.getSwitchId(), command.getMetadata().getFlowId());
        }
        return actions;
    }

    private List<OFAction> makeVlanEncapsulationTransformActions() {
        List<OFAction> actions = new ArrayList<>();
        if (! FlowEndpoint.isVlanIdSet(command.getEndpoint().getVlanId())) {
            actions.add(of.actions().pushVlan(EthType.VLAN_FRAME));
        }
        actions.add(OfAdapter.INSTANCE.setVlanIdAction(of, command.getEncapsulation().getId()));
        return actions;
    }

    private List<OFAction> makeVlanEncapsulationServer42TransformActions() {
        List<OFAction> actions = new ArrayList<>();
        if (!FlowEndpoint.isVlanIdSet(command.getEndpoint().getVlanId())) {
            actions.add(of.actions().pushVlan(EthType.VLAN_FRAME));
        }
        actions.add(OfAdapter.INSTANCE.setVlanIdAction(of, command.getEncapsulation().getId()));

        MacAddress ethSrc = MacAddress.of(sw.getId());
        MacAddress ethDst = MacAddress.of(command.getEgressSwitchId().toLong());

        actions.add(of.actions().setField(of.oxms().ethSrc(ethSrc)));
        actions.add(of.actions().setField(of.oxms().ethDst(ethDst)));
        return actions;
    }

    private List<OFAction> makeVxLanEncapsulationTransformActions(int udpSrcPort, boolean copyEthSrc) {
        List<OFAction> actions = new ArrayList<>();

        MacAddress l2src = MacAddress.of(sw.getId());
        actions.add(of.actions().buildNoviflowPushVxlanTunnel()
                .setVni(command.getEncapsulation().getId())
                .setEthSrc(l2src)
                .setEthDst(MacAddress.of(command.getEgressSwitchId().toLong()))
                // nobody can explain why we use this constant
                .setUdpSrc(udpSrcPort)
                .setIpv4Src(IPv4Address.of("127.0.0.1"))
                .setIpv4Dst(IPv4Address.of("127.0.0.2"))
                // Set to 0x01 indicating tunnel data is present (i.e. we are passing l2 and l3 headers in this action)
                .setFlags((short) 0x01)
                .build());

        if (copyEthSrc) {
            // copy original L2 source address
            actions.add(of.actions().buildNoviflowCopyField()
                    .setNBits(l2src.getLength() * 8)
                    // 14 ethernet header (without vlan)
                    // 20 IPV4 header
                    //  8 UDP header
                    //  8 VXLAN header
                    //  6 offset into original ethernet header
                    // 14 + 20 + 8 + 8 + 6 => 56 bytes total offset
                    .setSrcOffset(56 * 8)
                    .setDstOffset(l2src.getLength() * 8)
                    .setOxmSrcHeader(of.oxms().buildNoviflowPacketOffset().getTypeLen())
                    .setOxmDstHeader(of.oxms().buildNoviflowPacketOffset().getTypeLen())
                    .build());
        }

        return actions;
    }

    @Override
    protected OFAction makeOutputAction() {
        return super.makeOutputAction(OFPort.of(command.getIslPort()));
    }

    @Override
    protected List<OFInstruction> makeMetadataInstructions() {
        return Collections.emptyList();
    }
}
