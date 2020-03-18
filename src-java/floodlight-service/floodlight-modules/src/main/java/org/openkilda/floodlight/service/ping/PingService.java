/* Copyright 2018 Telstra Open Source
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

package org.openkilda.floodlight.service.ping;

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;

import org.openkilda.floodlight.KildaCore;
import org.openkilda.floodlight.KildaCoreConfig;
import org.openkilda.floodlight.error.InvalidSignatureConfigurationException;
import org.openkilda.floodlight.pathverification.PathVerificationService;
import org.openkilda.floodlight.service.IService;
import org.openkilda.floodlight.service.of.InputService;
import org.openkilda.floodlight.utils.DataSignature;
import org.openkilda.messaging.model.Ping;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.Map;

public class PingService implements IService {
    public static final U64 OF_CATCH_RULE_COOKIE = U64.of(
            ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.VERIFICATION_UNICAST_RULE_COOKIE).getValue());
    public static final U64 OF_CATCH_RULE_COOKIE_VXLAN = U64.of(
            ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue());
    private static final String NET_L3_ADDRESS = "127.0.0.2";
    private static final int NET_L3_PORT = PathVerificationService.DISCOVERY_PACKET_UDP_PORT + 1;
    private static final byte NET_L3_TTL = 96;

    private DataSignature signature = null;
    private MacAddress magicSourceMacAddress;

    /**
     * Initialize internal data structures. Called by module that own this service. Called after all dependencies have
     * been loaded.
     */
    @Override
    public void setup(FloodlightModuleContext moduleContext) throws FloodlightModuleException {
        // FIXME(surabujin): avoid usage foreign module configuration
        Map<String, String> config = moduleContext.getConfigParams(PathVerificationService.class);
        try {
            signature = new DataSignature(config.get("hmac256-secret"));
        } catch (InvalidSignatureConfigurationException e) {
            throw new FloodlightModuleException(String.format("Unable to initialize %s", getClass().getName()), e);
        }

        InputService inputService = moduleContext.getServiceImpl(InputService.class);
        inputService.addTranslator(OFType.PACKET_IN, new PingInputTranslator());

        KildaCoreConfig coreConfig = moduleContext.getServiceImpl(KildaCore.class).getConfig();
        magicSourceMacAddress = MacAddress.of(coreConfig.getFlowPingMagicSrcMacAddress());
    }

    /**
     * Wrap ping data into L2, l3 and L4 network packages.
     */
    public Ethernet wrapData(Ping ping, byte[] payload) {
        Data l7 = new Data(payload);

        UDP l4 = new UDP();
        l4.setPayload(l7);
        l4.setSourcePort(TransportPort.of(NET_L3_PORT));
        l4.setDestinationPort(TransportPort.of(NET_L3_PORT));

        IPv4 l3 = new IPv4();
        l3.setPayload(l4);
        l3.setSourceAddress(NET_L3_ADDRESS);
        l3.setDestinationAddress(NET_L3_ADDRESS);
        l3.setTtl(NET_L3_TTL);

        Ethernet l2 = new Ethernet();
        l2.setPayload(l3);
        l2.setEtherType(EthType.IPv4);

        l2.setSourceMACAddress(magicSourceMacAddress);
        l2.setDestinationMACAddress(ping.getDest().getDatapath().toMacAddress());
        if (null != ping.getSourceVlanId()) {
            l2.setVlanID(ping.getSourceVlanId());
        }

        return l2;
    }

    /**
     * Unpack network package.
     * Verify all particular qualities used during discovery package creation time. Return packet payload.
     */
    public byte[] unwrapData(DatapathId dpId, Ethernet packet) {
        MacAddress targetL2Address = convertDpIdToMac(dpId);
        if (!packet.getDestinationMACAddress().equals(targetL2Address)) {
            return null;
        }

        if (!(packet.getPayload() instanceof IPv4)) {
            return null;
        }
        IPv4 ip = (IPv4) packet.getPayload();

        if (!NET_L3_ADDRESS.equals(ip.getSourceAddress().toString())) {
            return null;
        }
        if (!NET_L3_ADDRESS.equals(ip.getDestinationAddress().toString())) {
            return null;
        }

        if (!(ip.getPayload() instanceof UDP)) {
            return null;
        }
        UDP udp = (UDP) ip.getPayload();

        if (udp.getSourcePort().getPort() != NET_L3_PORT) {
            return null;
        }
        if (udp.getDestinationPort().getPort() != NET_L3_PORT) {
            return null;
        }

        return ((Data) udp.getPayload()).getData();
    }

    public DataSignature getSignature() {
        return signature;
    }
}
