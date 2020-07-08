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

package org.openkilda.floodlight.service.connected;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.openkilda.floodlight.switchmanager.SwitchManager.VXLAN_UDP_DST;
import static org.openkilda.model.cookie.ServiceCookie.ServiceCookieTag.ARP_POST_INGRESS_COOKIE;
import static org.openkilda.model.cookie.ServiceCookie.ServiceCookieTag.ARP_POST_INGRESS_VXLAN_COOKIE;
import static org.openkilda.model.cookie.ServiceCookie.ServiceCookieTag.LLDP_POST_INGRESS_COOKIE;
import static org.openkilda.model.cookie.ServiceCookie.ServiceCookieTag.LLDP_POST_INGRESS_VXLAN_COOKIE;

import org.openkilda.floodlight.service.connected.ConnectedDevicesService.ArpPacketData;
import org.openkilda.floodlight.service.connected.ConnectedDevicesService.LldpPacketData;
import org.openkilda.floodlight.shared.packet.Vxlan;
import org.openkilda.floodlight.test.standard.PacketTestBase;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.ServiceCookie;

import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.PacketParsingException;
import net.floodlightcontroller.packet.UDP;
import org.junit.Test;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.MacAddress;

public class ConnectedDevicesServiceTest extends PacketTestBase {

    private static final SwitchId SWITCH_ID = new SwitchId(1);
    private static final MacAddress MAC_ADDRESS = MacAddress.of(2);
    private static final Integer VNI = 15;
    private static final ServiceCookie arpCookie = ServiceCookie.builder().serviceTag(ARP_POST_INGRESS_COOKIE).build();
    private static final ServiceCookie lldpCookie = ServiceCookie.builder()
            .serviceTag(LLDP_POST_INGRESS_COOKIE).build();
    private static final ServiceCookie arpVxlanCookie = ServiceCookie.builder()
            .serviceTag(ARP_POST_INGRESS_VXLAN_COOKIE).build();
    private static final ServiceCookie lldpVxlanCookie = ServiceCookie.builder()
            .serviceTag(LLDP_POST_INGRESS_VXLAN_COOKIE).build();

    private final byte[] srcAndDstMacAddresses = new byte[]{
            0x1, (byte) 0x80, (byte) 0xC2, 0x0, 0x0, 0xE,           // src mac address
            0x10, 0x4E, (byte) 0xF1, (byte) 0xF9, 0x6D, (byte) 0xFA // dst mac address
    };

    private final byte[] arpPacket = new byte[]{
            0x00, 0x01,                         // hardware type (Ethernet)
            0x08, 0x00,                         // protocol type (IPv4)
            0x06,                               // hardware length (6)
            0x04,                               // protocol length(4)
            0x00, 0x01,                         // request operation
            0x01, 0x04, 0x08, 0x0A, 0x0C, 0x0F, // sender mac address
            0x0A, 0x15, 0x01, 0x05,             // sender IP address
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // target mac address (blank)
            0x0B, 0x16, 0x02, 0x07              // target IP address
    };

    private final ConnectedDevicesService service = new ConnectedDevicesService();

    @Test
    public void deserializeLldpTest() {
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.LLDP), LldpPacketTest.packet);

        LldpPacketData data = service.deserializeLldp(ethernet, null, lldpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertTrue(data.getVlans().isEmpty());
        assertEquals(LldpPacketTest.buildLldpPacket(LldpPacketTest.packet), data.getLldpPacket());
    }

    @Test
    public void deserializeLldpWithVlanTest() {
        short vlan = 1234;
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(vlan),
                ethTypeToByteArray(EthType.LLDP), LldpPacketTest.packet);

        LldpPacketData data = service.deserializeLldp(ethernet, null, lldpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertEquals(1, data.getVlans().size());
        assertEquals(Integer.valueOf(vlan), data.getVlans().get(0));
        assertEquals(LldpPacketTest.buildLldpPacket(LldpPacketTest.packet), data.getLldpPacket());
    }

    @Test
    public void deserializeLldpWithTwoVlanTest() {
        short innerVlan = 1234;
        short outerVlan = 2345;
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(outerVlan),
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(innerVlan),
                ethTypeToByteArray(EthType.LLDP), LldpPacketTest.packet);

        LldpPacketData data = service.deserializeLldp(ethernet, null, lldpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertEquals(2, data.getVlans().size());
        assertEquals(Integer.valueOf(outerVlan), data.getVlans().get(0));
        assertEquals(Integer.valueOf(innerVlan), data.getVlans().get(1));
        assertEquals(LldpPacketTest.buildLldpPacket(LldpPacketTest.packet), data.getLldpPacket());
    }

    @Test
    public void deserializeLldpWithQinQVlansTest() {
        short vlan1 = 1234;
        short vlan2 = 2345;
        short vlan3 = 4000;
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.Q_IN_Q), shortToByteArray(vlan1),
                ethTypeToByteArray(EthType.BRIDGING), shortToByteArray(vlan2),
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(vlan3),
                ethTypeToByteArray(EthType.LLDP), LldpPacketTest.packet);

        LldpPacketData data = service.deserializeLldp(ethernet, null, lldpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertEquals(3, data.getVlans().size());
        assertEquals(Integer.valueOf(vlan1), data.getVlans().get(0));
        assertEquals(Integer.valueOf(vlan2), data.getVlans().get(1));
        assertEquals(Integer.valueOf(vlan3), data.getVlans().get(2));
        assertEquals(LldpPacketTest.buildLldpPacket(LldpPacketTest.packet), data.getLldpPacket());
    }

    @Test
    public void deserializeLldpWithVxlanTest() {
        short vlan = 1234;
        Ethernet innerEthernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(vlan),
                ethTypeToByteArray(EthType.LLDP), LldpPacketTest.packet);

        Ethernet outerEthernet = pushVxlanEncapsulation(innerEthernet, VNI);

        LldpPacketData data = service.deserializeLldp(outerEthernet, SWITCH_ID, lldpVxlanCookie);
        assertNotNull(data);
        assertEquals(1, data.getVlans().size());
        assertEquals(Integer.valueOf(vlan), data.getVlans().get(0));
        assertEquals(VNI, data.getVni());
        assertEquals(LldpPacketTest.buildLldpPacket(LldpPacketTest.packet), data.getLldpPacket());
    }

    @Test
    public void deserializeArpTest() throws PacketParsingException {
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses, ethTypeToByteArray(EthType.ARP), arpPacket);

        ArpPacketData data = service.deserializeArp(ethernet, null, arpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertTrue(data.getVlans().isEmpty());
        assertEquals(buildArpPacket(arpPacket), data.getArp());
    }

    @Test
    public void deserializeArpWithVlanTest() throws PacketParsingException {
        short vlan = 1234;
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(vlan),
                ethTypeToByteArray(EthType.ARP), arpPacket);

        ArpPacketData data = service.deserializeArp(ethernet, null, arpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertEquals(1, data.getVlans().size());
        assertEquals(Integer.valueOf(vlan), data.getVlans().get(0));
        assertEquals(buildArpPacket(arpPacket), data.getArp());
    }

    @Test
    public void deserializeArpWithTwoVlanTest() throws PacketParsingException {
        short innerVlan = 1234;
        short outerVlan = 2345;
        Ethernet ethernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(outerVlan),
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(innerVlan),
                ethTypeToByteArray(EthType.ARP), arpPacket);

        ArpPacketData data = service.deserializeArp(ethernet, null, arpCookie);
        assertNotNull(data);
        assertNull(data.getVni());
        assertEquals(2, data.getVlans().size());
        assertEquals(Integer.valueOf(outerVlan), data.getVlans().get(0));
        assertEquals(Integer.valueOf(innerVlan), data.getVlans().get(1));
        assertEquals(buildArpPacket(arpPacket), data.getArp());
    }

    @Test
    public void deserializeArpWithVxlanTest() throws PacketParsingException {
        short vlan = 1234;
        Ethernet innerEthernet = buildEthernet(srcAndDstMacAddresses,
                ethTypeToByteArray(EthType.VLAN_FRAME), shortToByteArray(vlan),
                ethTypeToByteArray(EthType.ARP), arpPacket);

        Ethernet outerEthernet = pushVxlanEncapsulation(innerEthernet, VNI);

        ArpPacketData data = service.deserializeArp(outerEthernet, SWITCH_ID, arpVxlanCookie);
        assertNotNull(data);
        assertEquals(1, data.getVlans().size());
        assertEquals(Integer.valueOf(vlan), data.getVlans().get(0));
        assertEquals(VNI, data.getVni());
        assertEquals(buildArpPacket(arpPacket), data.getArp());
    }

    private Ethernet pushVxlanEncapsulation(Ethernet innerEthernet, int vni) {
        Vxlan vxlan = new Vxlan();
        vxlan.setVni(vni);
        vxlan.setPayload(innerEthernet);

        UDP udp = new UDP();
        udp.setPayload(vxlan);
        udp.setSourcePort((short) 4500);
        udp.setDestinationPort((short) VXLAN_UDP_DST);

        IPv4 ipv4 = new IPv4();
        ipv4.setSourceAddress(1);
        ipv4.setDestinationAddress(2);
        ipv4.setPayload(udp);

        Ethernet outerEthernet = new Ethernet();
        outerEthernet.setPayload(ipv4);
        outerEthernet.setSourceMACAddress(SWITCH_ID.toMacAddress());
        outerEthernet.setDestinationMACAddress(MAC_ADDRESS);
        return outerEthernet;
    }

    private ARP buildArpPacket(byte[] data) throws PacketParsingException {
        ARP arp = new ARP();
        arp.deserialize(data, 0, data.length);
        return arp;
    }
}
