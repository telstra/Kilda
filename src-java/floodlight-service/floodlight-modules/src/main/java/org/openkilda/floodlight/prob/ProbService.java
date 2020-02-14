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

package org.openkilda.floodlight.prob;

import org.openkilda.floodlight.prob.web.ArpPacketData;
import org.openkilda.floodlight.prob.web.PacketData;
import org.openkilda.floodlight.prob.web.ProbServiceWebRoutable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.restserver.IRestApiService;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.types.ArpOpcode;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ProbService implements IProbService, IFloodlightModule {

    private IOFSwitchService switchService;

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableList.of(
                IProbService.class);
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.of(
                IProbService.class, this);
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return ImmutableList.of(
                IOFSwitchService.class,
                IRestApiService.class);
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        switchService = context.getServiceImpl(IOFSwitchService.class);

    }

    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
        context.getServiceImpl(IRestApiService.class)
                .addRestletRoutable(new ProbServiceWebRoutable());
    }

    @Override
    public void sendPacketProb(PacketData packetData) {
        MacAddress srcMac = MacAddress.of(packetData.getSrcSwitch());
        DatapathId dpid = DatapathId.of(srcMac);
        final IOFSwitch ofSwitch = switchService.getSwitch(dpid);



        UDP l4 = new UDP();
        l4.setSourcePort(TransportPort.of(packetData.getL4SrcPort()));
        l4.setDestinationPort(TransportPort.of(packetData.getL4DstPort()));



        Ethernet l2 = new Ethernet().setSourceMACAddress(srcMac)
                .setDestinationMACAddress(MacAddress.of(packetData.getDstSwitch())).setEtherType(EthType.IPv4);
        if (packetData.getVlan() > 0) {
            l2.setVlanID((short) packetData.getVlan());
        }

        IPv4Address srcIp = IPv4Address.of(packetData.getSrcIpv4());
        IPv4Address dstIp = IPv4Address.of(packetData.getDstIpv4());

        IPv4 l3 = new IPv4()
                .setSourceAddress(srcIp)
                .setDestinationAddress(dstIp).setTtl((byte) 64)
                .setProtocol(IpProtocol.of((short) packetData.getIpProto()));


        l2.setPayload(l3);
        l3.setPayload(l4);

        byte[] buff = new byte[120];
        buff[11] = 1;
        Data dp = new Data(buff);
        l4.setPayload(dp);

        byte[] data = l2.serialize();
        List<OFAction> actions = Collections.singletonList(ofSwitch.getOFFactory().actions().buildOutput()
                .setPort(OFPort.TABLE)
                .build());
        OFPacketOut pob = ofSwitch.getOFFactory().buildPacketOut()
                .setInPort(OFPort.of(packetData.getOutPort()))
                .setActions(actions)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setData(data).build();
        if (!ofSwitch.write(pob)) {
            throw new IllegalStateException("Failed to send packet out");
        }

    }

    @Override
    public void sendArpPacket(ArpPacketData packetData) {
        MacAddress srcMac = MacAddress.of(packetData.getSrcMac());
        DatapathId dpid = DatapathId.of(packetData.getSwitchId());
        IPv4Address srcIp = IPv4Address.of(packetData.getSrcIpv4());
        IPv4Address dstIp = IPv4Address.of(packetData.getDstIpv4());

        final IOFSwitch ofSwitch = switchService.getSwitch(dpid);

        Ethernet l2 = new Ethernet()
                .setSourceMACAddress(srcMac)
                .setDestinationMACAddress(MacAddress.BROADCAST)
                .setEtherType(EthType.ARP);

        if (packetData.getVlan() > 0) {
            l2.setVlanID((short) packetData.getVlan());
        }

        ARP l3 = new ARP()
                .setSenderHardwareAddress(srcMac)
                .setSenderProtocolAddress(srcIp)
                .setTargetProtocolAddress(dstIp)
                .setHardwareType((short) 1)
                .setProtocolType(Ethernet.TYPE_IPv4)
                .setHardwareAddressLength((byte) 6)
                .setProtocolAddressLength((byte) 4)
                .setOpCode(ArpOpcode.REQUEST);

        l2.setPayload(l3);

        byte[] data = l2.serialize();
        List<OFAction> actions = Collections.singletonList(ofSwitch.getOFFactory().actions().buildOutput()
                .setPort(OFPort.TABLE)
                .build());
        OFPacketOut pob = ofSwitch.getOFFactory().buildPacketOut()
                .setInPort(OFPort.of(packetData.getOutPort()))
                .setActions(actions)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setData(data).build();
        if (!ofSwitch.write(pob)) {
            throw new IllegalStateException("Failed to send packet out");
        }
    }
}
