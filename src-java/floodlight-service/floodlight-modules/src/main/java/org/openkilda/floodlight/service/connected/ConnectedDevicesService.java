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

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.model.OfInput;
import org.openkilda.floodlight.service.IService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.service.of.IInputTranslator;
import org.openkilda.floodlight.service.of.InputService;
import org.openkilda.floodlight.shared.packet.VlanTag;
import org.openkilda.floodlight.shared.packet.Vxlan;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.floodlight.utils.EthernetPacketToolbox;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.ArpInfoData;
import org.openkilda.messaging.info.event.LldpInfoData;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.Cookie;
import org.openkilda.model.cookie.ServiceCookie;
import org.openkilda.model.cookie.ServiceCookie.ServiceCookieTag;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import lombok.Value;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.LLDP;
import net.floodlightcontroller.packet.UDP;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ConnectedDevicesService implements IService, IInputTranslator {
    private static final Logger logger = LoggerFactory.getLogger(ConnectedDevicesService.class);

    private static final Set<ServiceCookieTag> lldpServiceTags = ImmutableSet.of(
            ServiceCookieTag.LLDP_INPUT_PRE_DROP_COOKIE,
            ServiceCookieTag.LLDP_TRANSIT_COOKIE,
            ServiceCookieTag.LLDP_INGRESS_COOKIE,
            ServiceCookieTag.LLDP_POST_INGRESS_COOKIE,
            ServiceCookieTag.LLDP_POST_INGRESS_VXLAN_COOKIE,
            ServiceCookieTag.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE);

    private static final Set<ServiceCookieTag> arpServiceTags = ImmutableSet.of(
            ServiceCookieTag.ARP_INPUT_PRE_DROP_COOKIE,
            ServiceCookieTag.ARP_TRANSIT_COOKIE,
            ServiceCookieTag.ARP_INGRESS_COOKIE,
            ServiceCookieTag.ARP_POST_INGRESS_COOKIE,
            ServiceCookieTag.ARP_POST_INGRESS_VXLAN_COOKIE,
            ServiceCookieTag.ARP_POST_INGRESS_ONE_SWITCH_COOKIE);

    private IKafkaProducerService producerService;
    private String topic;
    private String region;

    static {
        try {
            logger.info("Force loading of {}", Class.forName(VlanTag.class.getName()));
        } catch (ClassNotFoundException e) {
            logger.error(String.format("Couldn't load class VlanTag %s", e.getMessage()), e);
        }
    }

    @Override
    public Command makeCommand(CommandContext context, OfInput input) {
        return new Command(context) {
            @Override
            public Command call() {
                handlePacketIn(input);
                return null;
            }
        };
    }

    @VisibleForTesting
    VxlanData popVxlanEncapsulation(Ethernet eth, SwitchId switchId, ServiceCookie cookie) {
        if (!eth.getSourceMACAddress().toString().equals(switchId.toMacAddress())) {
            logger.info("Couldn't pop VXLAN encapsulation from packet on switch {}. Ethernet src mac address {} "
                    + "is not equal to switch mac address. Cookie {}. Data: {}.",
                    switchId, eth.getSourceMACAddress(), cookie, eth);
            return null;
        }
        IPv4 ipPacket;
        if (eth.getPayload() instanceof IPv4) {
            ipPacket = (IPv4) eth.getPayload();
        } else {
            logger.info("Couldn't pop VXLAN encapsulation from packet on switch {}. "
                    + "Unknown payload of Ethernet frame {}. Cookie {}", switchId, eth.getPayload(), cookie);
            return null;
        }

        UDP udpPacket;
        if (ipPacket.getPayload() instanceof UDP) {
            udpPacket = (UDP) ipPacket.getPayload();
        } else {
            logger.info("Couldn't pop VXLAN encapsulation from packet on switch {}. "
                    + "Unknown payload of IPv4 packet {}. Cookie {}", switchId, ipPacket.getPayload(), cookie);
            return null;
        }

        Vxlan vxlan;
        if (udpPacket.getPayload() instanceof Vxlan) {
            vxlan = (Vxlan) udpPacket.getPayload();
        } else {
            logger.info("Couldn't pop VXLAN encapsulation from packet on switch {}. "
                    + "Unknown payload of UDP packet {}. Cookie {}", switchId, udpPacket.getPayload(), cookie);
            return null;
        }

        if (vxlan.getPayload() instanceof Ethernet) {
            return new VxlanData((Ethernet) vxlan.getPayload(), vxlan.getVni());
        } else {
            logger.info("Couldn't pop VXLAN encapsulation from packet on switch {}. "
                    + "Unknown payload of VXLAN packet {}. Cookie {}", switchId, vxlan.getPayload(), cookie);
            return null;
        }
    }

    @VisibleForTesting
    LldpPacketData deserializeLldp(Ethernet eth, SwitchId switchId, ServiceCookie cookie) {
        try {
            Integer vni = null;
            if (cookie.getServiceTag() == ServiceCookieTag.LLDP_POST_INGRESS_VXLAN_COOKIE) {
                VxlanData vxlanData = popVxlanEncapsulation(eth, switchId, cookie);
                if (vxlanData == null) {
                    return null;
                }
                eth = vxlanData.ethernet;
                vni = vxlanData.vni;
            }

            List<Integer> vlans = new ArrayList<>();
            IPacket payload = EthernetPacketToolbox.extractPayload(eth, vlans);

            if (payload instanceof LLDP) {
                LldpPacket lldpPacket = new LldpPacket((LLDP) payload);
                return new LldpPacketData(lldpPacket, vlans, vni);
            }
        }  catch (Exception exception) {
            logger.info("Could not deserialize LLDP packet {} on switch {}. Cookie {}. Deserialization failure: {}",
                    eth, switchId, cookie, exception.getMessage(), exception);
            return null;
        }
        logger.info("Got invalid lldp packet: {} on switch {}. Cookie {}", eth, switchId, cookie);
        return null;
    }

    @VisibleForTesting
    ArpPacketData deserializeArp(Ethernet eth, SwitchId switchId, ServiceCookie cookie) {
        try {
            Integer vni = null;
            if (cookie.getServiceTag() == ServiceCookieTag.ARP_POST_INGRESS_VXLAN_COOKIE) {
                VxlanData vxlanData = popVxlanEncapsulation(eth, switchId, cookie);
                if (vxlanData == null) {
                    return null;
                }
                eth = vxlanData.ethernet;
                vni = vxlanData.vni;
            }

            List<Integer> vlans = new ArrayList<>();
            IPacket payload = EthernetPacketToolbox.extractPayload(eth, vlans);

            if (payload instanceof ARP) {
                return new ArpPacketData((ARP) payload, vlans, vni);
            }
        }  catch (Exception exception) {
            logger.info("Could not deserialize ARP packet {} on switch {}. Cookie {}. Deserialization failure: {}",
                    eth, switchId, cookie, exception.getMessage(), exception);
            return null;
        }
        logger.info("Got invalid ARP packet: {} on switch {}. Cookie {}", eth, switchId, cookie);
        return null;
    }

    private void handlePacketIn(OfInput input) {
        U64 rawCookie = input.packetInCookie();

        if (rawCookie == null) {
            return;
        }

        ServiceCookie cookie = new ServiceCookie(rawCookie.getValue());
        SwitchId switchId = new SwitchId(input.getDpId().getLong());

        final ServiceCookieTag serviceTag = cookie.getServiceTag();
        if (lldpServiceTags.contains(serviceTag)) {
            logger.debug("Receive connected device LLDP packet from {} OF-xid:{}, cookie: {}",
                    input.getDpId(), input.getMessage().getXid(), cookie);
            handleSwitchLldp(input, switchId, cookie);
        } else if (arpServiceTags.contains(serviceTag)) {
            logger.debug("Receive connected device ARP packet from {} OF-xid:{}, cookie: {}",
                    input.getDpId(), input.getMessage().getXid(), cookie);
            handleArp(input, switchId, cookie);
        }
    }

    private void handleSwitchLldp(OfInput input, SwitchId switchId, ServiceCookie cookie) {
        Ethernet ethernet = input.getPacketInPayload();
        LldpPacketData packetData = deserializeLldp(ethernet, switchId, cookie);
        if (packetData == null) {
            return;
        }

        InfoMessage message = createSwitchLldpMessage(
                switchId, cookie.getValue(), input, packetData.lldpPacket, packetData.vlans, packetData.vni);
        producerService.sendMessageAndTrack(topic, switchId.toString(), message);
    }

    private InfoMessage createSwitchLldpMessage(
            SwitchId switchId, long cookie, OfInput input, LldpPacket lldpPacket, List<Integer> vlans, Integer vni) {
        LldpInfoData lldpInfoData = new LldpInfoData(
                switchId,
                input.getPort().getPortNumber(),
                vlans,
                vni,
                cookie,
                input.getPacketInPayload().getSourceMACAddress().toString(),
                lldpPacket.getParsedChassisId(),
                lldpPacket.getParsedPortId(),
                lldpPacket.getParsedTtl(),
                lldpPacket.getParsedPortDescription(),
                lldpPacket.getParsedSystemName(),
                lldpPacket.getParsedSystemDescription(),
                lldpPacket.getParsedSystemCapabilities(),
                lldpPacket.getParsedManagementAddress());

        return new InfoMessage(lldpInfoData, System.currentTimeMillis(), CorrelationContext.getId(), region);
    }

    private void handleArp(OfInput input, SwitchId switchId, ServiceCookie cookie) {
        Ethernet ethernet = input.getPacketInPayload();
        ArpPacketData data = deserializeArp(ethernet, switchId, cookie);
        if (data == null) {
            return;
        }

        ArpInfoData arpInfoData = new ArpInfoData(
                switchId,
                input.getPort().getPortNumber(),
                data.vlans,
                data.vni,
                cookie.getValue(),
                data.arp.getSenderHardwareAddress().toString(),
                data.arp.getSenderProtocolAddress().toString()
        );

        InfoMessage message = new InfoMessage(
                arpInfoData, System.currentTimeMillis(), CorrelationContext.getId(), region);
        producerService.sendMessageAndTrack(topic, switchId.toString(), message);
    }

    @Override
    public void setup(FloodlightModuleContext context) {
        logger.info("Stating {}", ConnectedDevicesService.class.getCanonicalName());
        KafkaChannel kafkaChannel = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel();
        logger.info("region: {}", kafkaChannel.getRegion());

        region = kafkaChannel.getRegion();
        producerService = context.getServiceImpl(IKafkaProducerService.class);
        topic = kafkaChannel.getConnectedDevicesTopic();

        InputService inputService = context.getServiceImpl(InputService.class);
        inputService.addTranslator(OFType.PACKET_IN, this);
    }

    @Value
    static class VxlanData {
        Ethernet ethernet;
        int vni;
    }

    @Value
    static class LldpPacketData {
        LldpPacket lldpPacket;
        List<Integer> vlans;
        Integer vni;
    }

    @Value
    static class ArpPacketData {
        ARP arp;
        List<Integer> vlans;
        Integer vni;
    }
}
