/* Copyright 2010 Telstra Open Source
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

import org.openkilda.floodlight.prob.web.ProbServiceWebRoutable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.restserver.IRestApiService;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
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
    public void sendPacketProb(DatapathId srcDpid, int srcPort, short srcVlan, int udpDst) {
        final IOFSwitch ofSwitch = switchService.getSwitch(srcDpid);



        UDP l4 = new UDP();
        l4.setSourcePort(TransportPort.of(11));
        l4.setDestinationPort(TransportPort.of(udpDst));



        Ethernet l2 = new Ethernet().setSourceMACAddress(MacAddress.of("55:44:33:22:11:00"))
                .setDestinationMACAddress(MacAddress.of("00:11:22:33:44:55")).setEtherType(EthType.IPv4);
        l2.setVlanID(srcVlan);

        IPv4Address srcIp = IPv4Address.of("192.168.1.2");
        IPv4Address dstIp = IPv4Address.of("192.168.1.3");

        IPv4 l3 = new IPv4()
                .setSourceAddress(srcIp)
                .setDestinationAddress(dstIp).setTtl((byte) 64).setProtocol(IpProtocol.UDP);


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
                .setInPort(OFPort.of(srcPort))
                .setActions(actions)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setData(data).build();
        ofSwitch.write(pob);

    }
}
