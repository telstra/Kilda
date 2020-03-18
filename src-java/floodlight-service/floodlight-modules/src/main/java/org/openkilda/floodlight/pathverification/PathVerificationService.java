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

package org.openkilda.floodlight.pathverification;

import static org.openkilda.floodlight.pathverification.DiscoveryPacket.CHASSIS_ID_LLDPTV_PACKET_TYPE;
import static org.openkilda.floodlight.pathverification.DiscoveryPacket.OPTIONAL_LLDPTV_PACKET_TYPE;
import static org.openkilda.floodlight.pathverification.DiscoveryPacket.PORT_ID_LLDPTV_PACKET_TYPE;
import static org.openkilda.floodlight.pathverification.DiscoveryPacket.TTL_LLDPTV_PACKET_TYPE;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.config.provider.FloodlightModuleConfigurationProvider;
import org.openkilda.floodlight.model.OfInput;
import org.openkilda.floodlight.pathverification.type.PathType;
import org.openkilda.floodlight.pathverification.web.PathVerificationServiceWebRoutable;
import org.openkilda.floodlight.service.CommandProcessorService;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.service.of.IInputTranslator;
import org.openkilda.floodlight.service.of.InputService;
import org.openkilda.floodlight.service.ping.PingService;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.IslOneWayLatency;
import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator.Builder;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
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
import net.floodlightcontroller.packet.LLDPTLV;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.util.OFMessageUtils;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.util.Arrays;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFPortDescProp;
import org.projectfloodlight.openflow.protocol.OFPortDescPropEthernet;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class PathVerificationService implements IFloodlightModule, IPathVerificationService, IInputTranslator {
    private static final Logger logger = LoggerFactory.getLogger(PathVerificationService.class);
    private static final Logger logIsl = LoggerFactory.getLogger(
            String.format("%s.ISL", PathVerificationService.class.getName()));

    public static final U64 OF_CATCH_RULE_COOKIE = U64.of(
            ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.VERIFICATION_BROADCAST_RULE_COOKIE).getValue());
    public static final U64 OF_ROUND_TRIP_RULE_COOKIE = U64.of(
            ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.ROUND_TRIP_LATENCY_RULE_COOKIE).getValue());

    public static final int DISCOVERY_PACKET_UDP_PORT = 61231;
    public static final int LATENCY_PACKET_UDP_PORT = 61232;
    public static final long TEN_TO_NINE = 1_000_000_000;
    public static final String DISCOVERY_PACKET_IP_DST = "192.168.0.255";
    public static final byte REMOTE_SWITCH_OPTIONAL_TYPE = 0x00;
    public static final byte TIMESTAMP_OPTIONAL_TYPE = 0x01;
    public static final byte PATH_ORDINAL_OPTIONAL_TYPE = 0x02;
    public static final byte TOKEN_OPTIONAL_TYPE = 0x03;
    public static final byte SWITCH_T0_OPTIONAL_TYPE = 0x04;
    public static final byte SWITCH_T1_OPTIONAL_TYPE = 0x05;
    public static final int ETHERNET_HEADER_SIZE = 112; // 48 dst mac, 48 src mac, 16 ether type
    public static final int IP_V4_HEADER_SIZE = 160; /*
                                                      * 4 version, 4 IHL, 8 Type of service, 16 length, 16 ID,
                                                      * 4 flags, 12 Fragment Offset, 8 TTL, 8 Protocol,
                                                      * 16 checksum, 32 src IP, 32 dst IP
                                                      */
    public static final int UDP_HEADER_SIZE = 64;                    // 16 src port, 16 dst port, 16 length, 16 checksum
    public static final int LLDP_TLV_CHASSIS_ID_TOTAL_SIZE = 72;     // 7 type, 9 length, 56 chassisId
    public static final int LLDP_TLV_PORT_ID_TOTAL_SIZE = 40;        // 7 type, 9 length, 24 port
    public static final int LLDP_TLV_TTL_TOTAL_SIZE = 32;            // 7 type, 9 length, 16 port
    public static final int ROUND_TRIP_LATENCY_TIMESTAMP_SIZE = 64;  // 24 bits OUI, 8 bits optional type
    public static final int LLDP_TLV_HEADER_SIZE = 16;               // 7 type, 9 length
    public static final int LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES = 4; // 24 bits OUI, 8 bits optional type

    public static final int ROUND_TRIP_LATENCY_T0_OFFSET = ETHERNET_HEADER_SIZE
                                                         + IP_V4_HEADER_SIZE
                                                         + UDP_HEADER_SIZE
                                                         + LLDP_TLV_CHASSIS_ID_TOTAL_SIZE
                                                         + LLDP_TLV_PORT_ID_TOTAL_SIZE
                                                         + LLDP_TLV_TTL_TOTAL_SIZE
                                                         + LLDP_TLV_HEADER_SIZE
                                                         + (LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES * 8);

    public static final int ROUND_TRIP_LATENCY_T1_OFFSET = ROUND_TRIP_LATENCY_T0_OFFSET
                                                         + ROUND_TRIP_LATENCY_TIMESTAMP_SIZE
                                                         + LLDP_TLV_HEADER_SIZE
                                                         + (LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES * 8);

    public static final byte[] ORGANIZATIONALLY_UNIQUE_IDENTIFIER = new byte[] {0x00, 0x26, (byte) 0xe1};

    private IKafkaProducerService producerService;
    private IOFSwitchService switchService;
    private FeatureDetectorService featureDetectorService;

    private PathVerificationServiceConfig config;

    private String topoDiscoTopic;
    private String islLatencyTopic;
    private String region;
    private double islBandwidthQuotient = 1.0;
    private Algorithm algorithm;
    private JWTVerifier verifier;

    /**
     * IFloodlightModule Methods.
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return ImmutableList.of(
                CommandProcessorService.class,
                InputService.class,
                IOFSwitchService.class,
                IRestApiService.class,
                KafkaUtilityService.class,
                IKafkaProducerService.class);
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableList.of(
                IPathVerificationService.class,
                PingService.class);
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.of(
                IPathVerificationService.class, this,
                PingService.class, new PingService());
    }

    @Override
    public PathVerificationServiceConfig getConfig() {
        return config;
    }

    @VisibleForTesting
    void setConfig(PathVerificationServiceConfig config) {
        this.config = config;
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        logger.debug("main pathverification service: " + this);

        initConfiguration(context);

        switchService = context.getServiceImpl(IOFSwitchService.class);
        producerService = context.getServiceImpl(IKafkaProducerService.class);
        featureDetectorService = context.getServiceImpl(FeatureDetectorService.class);
    }

    @VisibleForTesting
    void initConfiguration(FloodlightModuleContext moduleContext) throws FloodlightModuleException {
        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(moduleContext, this);
        config = provider.getConfiguration(PathVerificationServiceConfig.class);

        islBandwidthQuotient = config.getIslBandwidthQuotient();

        initAlgorithm(config.getHmac256Secret());
    }

    @VisibleForTesting
    void initAlgorithm(String secret) throws FloodlightModuleException {
        try {
            algorithm = Algorithm.HMAC256(secret);
            verifier = JWT.require(algorithm).build();
        } catch (UnsupportedEncodingException e) {
            logger.error("Ivalid secret", e);
            throw new FloodlightModuleException("Invalid secret for HMAC256");
        }
    }

    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
        logger.info("Stating {}", PathVerificationService.class.getCanonicalName());
        KafkaChannel kafkaChannel = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel();
        logger.info("region: {}", kafkaChannel.getRegion());
        topoDiscoTopic = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel().getTopoDiscoTopic();
        islLatencyTopic = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel().getIslLatencyTopic();
        region = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel().getRegion();
        InputService inputService = context.getServiceImpl(InputService.class);
        inputService.addTranslator(OFType.PACKET_IN, this);

        context.getServiceImpl(PingService.class).setup(context);
        context.getServiceImpl(IRestApiService.class)
                .addRestletRoutable(new PathVerificationServiceWebRoutable());
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

    /**
     * IPathVerificationService Methods.
     */

    protected List<OFAction> getDiscoveryActions(IOFSwitch sw, OFPort port) {
        // set actions
        List<OFAction> actions = new ArrayList<>();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            actions.add(actionAddTxTimestamp(sw, ROUND_TRIP_LATENCY_T0_OFFSET));
        } else {
            logger.debug("Switch {} does not support round trip latency", sw.getId());
        }
        actions.add(sw.getOFFactory().actions().buildOutput().setPort(port).build());
        return actions;
    }

    private OFAction actionAddTxTimestamp(final IOFSwitch sw, int offset) {
        OFOxms oxms = sw.getOFFactory().oxms();
        OFActions actions = sw.getOFFactory().actions();
        return actions.buildNoviflowCopyField()
                .setNBits(ROUND_TRIP_LATENCY_TIMESTAMP_SIZE)
                .setSrcOffset(0)
                .setDstOffset(offset)
                .setOxmSrcHeader(oxms.buildNoviflowTxtimestamp().getTypeLen())
                .setOxmDstHeader(oxms.buildNoviflowPacketOffset().getTypeLen())
                .build();
    }

    @Override
    public boolean sendDiscoveryMessage(DatapathId srcSwId, OFPort port, Long packetId) {
        boolean result = false;

        try {
            IOFSwitch srcSwitch = switchService.getSwitch(srcSwId);
            if (srcSwitch != null && srcSwitch.getPort(port) != null) {
                OFPacketOut ofPacketOut = generateDiscoveryPacket(srcSwitch, port, true, packetId);

                if (ofPacketOut != null) {
                    logger.debug("==> Sending discovery packet out {}/{} id {}: {}", srcSwitch.getId(),
                            port.getPortNumber(),
                            packetId,
                            Hex.encodeHexString(ofPacketOut.getData()));
                    result = srcSwitch.write(ofPacketOut);
                } else {
                    logger.error("<== Received null from generateDiscoveryPacket, inputs where: "
                            + "srcSwitch: {}, port: {} id: {}", srcSwitch, port, packetId);
                }

                if (result) {
                    logIsl.info("push discovery package via: {}-{} id:{} OF-xid:{}", srcSwitch.getId(),
                                port.getPortNumber(), packetId, ofPacketOut.getXid());
                } else {
                    logger.error(
                            "Failed to send PACKET_OUT(ISL discovery packet) via {}-{} id:{} OF-xid:{}",
                            srcSwitch.getId(), port.getPortNumber(), packetId, ofPacketOut.getXid());
                }
            }
        } catch (Exception exception) {
            logger.error(String.format("Unhandled exception in %s", getClass().getName()), exception);
        }

        return result;
    }

    private static LLDPTLV switchTimestampTlv(byte type) {
        byte[] timestampArray = ByteBuffer
                .allocate(ROUND_TRIP_LATENCY_TIMESTAMP_SIZE / 8 + LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES)
                .put(ORGANIZATIONALLY_UNIQUE_IDENTIFIER)
                .put(type)
                .putLong(0L) // placeholder for timestamp
                .array();

        return new LLDPTLV().setType(OPTIONAL_LLDPTV_PACKET_TYPE)
                .setLength((short) timestampArray.length).setValue(timestampArray);
    }

    /**
     * Return Discovery packet.
     *
     * @param srcSw source switch.
     * @param port port.
     * @param sign sign.
     * @param packetId id of the packet.
     * @return discovery packet.
     */
    OFPacketOut generateDiscoveryPacket(IOFSwitch srcSw, OFPort port, boolean sign, Long packetId) {
        try {
            byte[] dpidArray = new byte[8];
            ByteBuffer dpidBb = ByteBuffer.wrap(dpidArray);

            DatapathId dpid = srcSw.getId();
            dpidBb.putLong(dpid.getLong());
            byte[] chassisId = new byte[]{4, 0, 0, 0, 0, 0, 0};
            System.arraycopy(dpidArray, 2, chassisId, 1, 6);
            // Set the optionalTLV to the full SwitchID
            byte[] dpidTlvValue = Arrays.concatenate(
                    ORGANIZATIONALLY_UNIQUE_IDENTIFIER,
                    new byte[] {REMOTE_SWITCH_OPTIONAL_TYPE, 0, 0, 0, 0, 0, 0, 0, 0});
            System.arraycopy(dpidArray, 0, dpidTlvValue, LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES, 8);

            // Set src mac to be able to detect the origin of the packet.
            // NB: previously we set port's address instead of switch (some switches declare unique address per port)
            byte[] srcMac = new byte[6];
            System.arraycopy(dpidArray, 2, srcMac, 0, 6);

            byte[] portId = new byte[]{2, 0, 0};
            ByteBuffer portBb = ByteBuffer.wrap(portId, 1, 2);
            portBb.putShort(port.getShortPortNumber());

            byte[] ttlValue = new byte[]{0, 0x78};
            DiscoveryPacket dp = DiscoveryPacket.builder()
                    .chassisId(makeIdLldptvPacket(chassisId, CHASSIS_ID_LLDPTV_PACKET_TYPE))
                    .portId(makeIdLldptvPacket(portId, PORT_ID_LLDPTV_PACKET_TYPE))
                    .ttl(makeIdLldptvPacket(ttlValue, TTL_LLDPTV_PACKET_TYPE))
                    .build();

            // Add TLV for t0, this will be overwritten by the switch if it supports switch timestamps
            dp.getOptionalTlvList().add(switchTimestampTlv(SWITCH_T0_OPTIONAL_TYPE));

            // Add TLV for t1, this will be overwritten by the switch if it supports switch timestamps
            dp.getOptionalTlvList().add(switchTimestampTlv(SWITCH_T1_OPTIONAL_TYPE));

            LLDPTLV dpidTlv = makeIdLldptvPacket(dpidTlvValue, OPTIONAL_LLDPTV_PACKET_TYPE);
            dp.getOptionalTlvList().add(dpidTlv);

            // Add T0 based on format from Floodlight LLDP
            long time = System.currentTimeMillis();
            long swLatency = srcSw.getLatency().getValue();
            byte[] timestampTlvValue = ByteBuffer.allocate(Long.SIZE / 8 + LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES)
                    .put(ORGANIZATIONALLY_UNIQUE_IDENTIFIER)
                    .put(TIMESTAMP_OPTIONAL_TYPE) // 0x01 is what we'll use to differentiate DPID 0x00 from time 0x01
                    .putLong(time + swLatency /* account for our switch's one-way latency */)
                    .array();

            LLDPTLV timestampTlv = makeIdLldptvPacket(timestampTlvValue, OPTIONAL_LLDPTV_PACKET_TYPE);

            dp.getOptionalTlvList().add(timestampTlv);

            // Type
            byte[] typeTlvValue = ByteBuffer.allocate(Integer.SIZE / 8 + LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES)
                    .put(ORGANIZATIONALLY_UNIQUE_IDENTIFIER)
                    .put(PATH_ORDINAL_OPTIONAL_TYPE)
                    .putInt(PathType.ISL.ordinal()).array();
            LLDPTLV typeTlv = makeIdLldptvPacket(typeTlvValue, OPTIONAL_LLDPTV_PACKET_TYPE);
            dp.getOptionalTlvList().add(typeTlv);

            if (sign) {
                Builder builder = JWT.create()
                        .withClaim("dpid", dpid.getLong())
                        .withClaim("ts", time + swLatency);
                if (packetId != null) {
                    builder.withClaim("id", packetId);
                }
                String token = builder.sign(algorithm);

                byte[] tokenBytes = token.getBytes(Charset.forName("UTF-8"));

                byte[] tokenTlvValue = ByteBuffer.allocate(LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES + tokenBytes.length)
                        .put(ORGANIZATIONALLY_UNIQUE_IDENTIFIER)
                        .put(TOKEN_OPTIONAL_TYPE)
                        .put(tokenBytes).array();
                LLDPTLV tokenTlv = makeIdLldptvPacket(tokenTlvValue, OPTIONAL_LLDPTV_PACKET_TYPE);

                dp.getOptionalTlvList().add(tokenTlv);
            }

            MacAddress dstMac = MacAddress.of(config.getVerificationBcastPacketDst());
            IPv4Address dstIp = IPv4Address.of(DISCOVERY_PACKET_IP_DST);
            IPv4 l3 = new IPv4()
                    .setSourceAddress(
                            IPv4Address.of(((InetSocketAddress) srcSw.getInetAddress()).getAddress().getAddress()))
                    .setDestinationAddress(dstIp).setTtl((byte) 64).setProtocol(IpProtocol.UDP);

            UDP l4 = new UDP();
            l4.setSourcePort(TransportPort.of(DISCOVERY_PACKET_UDP_PORT));
            l4.setDestinationPort(TransportPort.of(DISCOVERY_PACKET_UDP_PORT));


            Ethernet l2 = new Ethernet().setSourceMACAddress(MacAddress.of(srcMac))
                    .setDestinationMACAddress(dstMac).setEtherType(EthType.IPv4);
            l2.setPayload(l3);
            l3.setPayload(l4);
            l4.setPayload(dp);

            byte[] data = l2.serialize();
            OFPacketOut.Builder pob = srcSw.getOFFactory().buildPacketOut()
                    .setBufferId(OFBufferId.NO_BUFFER).setActions(getDiscoveryActions(srcSw, port))
                    .setData(data);
            OFMessageUtils.setInPort(pob, OFPort.CONTROLLER);

            return pob.build();
        } catch (Exception e) {
            logger.error(String.format("error generating discovery packet: %s", e.getMessage()), e);
        }
        return null;
    }

    private LLDPTLV makeIdLldptvPacket(byte[] data, byte type) {
        return new LLDPTLV().setType(type).setLength((short) data.length).setValue(data);
    }

    @VisibleForTesting
    DiscoveryPacket deserialize(Ethernet eth) {
        if (eth.getPayload() instanceof IPv4) {
            IPv4 ip = (IPv4) eth.getPayload();

            if (ip.getPayload() instanceof UDP) {
                UDP udp = (UDP) ip.getPayload();

                if (isUdpHasSpecifiedSrdDstPort(udp, DISCOVERY_PACKET_UDP_PORT, DISCOVERY_PACKET_UDP_PORT)) {
                    return new DiscoveryPacket((Data) udp.getPayload(), false);
                } else if (isUdpHasSpecifiedSrdDstPort(udp, DISCOVERY_PACKET_UDP_PORT, LATENCY_PACKET_UDP_PORT)) {
                    return new DiscoveryPacket((Data) udp.getPayload(), true);
                }
            }
        }
        return null;
    }

    private boolean isUdpHasSpecifiedSrdDstPort(UDP udp, int srcPort, int dstPort) {
        return udp.getSourcePort().getPort() == srcPort && udp.getDestinationPort().getPort() == dstPort;
    }

    /**
     * Create a representation of a timestamp from a noviflow Switch software timestamp. The timestamp is presented
     * in 8 bytes with the first 4 bytes representing EPOCH in seconds and the second set of 4 bytes nanoseconds.
     *
     * @param timestamp Noviflow timestamp
     * @return timestamp in nanoseconds
     */
    @VisibleForTesting
    static long noviflowTimestamp(byte[] timestamp) {
        ByteBuffer buffer = ByteBuffer.wrap(timestamp);
        long seconds = buffer.getInt(0);
        long nanoseconds = buffer.getInt(4);

        return seconds * TEN_TO_NINE + nanoseconds;
    }

    /**
     * Calculate latency of the ISL.
     * @param switchId Switch ID of endpoint which received packet
     * @param port port of endpoint which received packet
     * @param switchT0 sent packet timestamp in nanoseconds
     * @param switchT1 receive packet timestamp in nanoseconds
     * @return long
     */
    @VisibleForTesting
    static long calculateRoundTripLatency(SwitchId switchId, int port, long switchT0, long switchT1) {
        if (switchT0 <= 0 || switchT1 <= 0) {
            logger.warn("Invalid round trip latency timestamps on endpoint {}_{}. t0 = '{}', t1 = '{}'.",
                    switchId, port, switchT0, switchT1);
            return -1;
        }
        return switchT1 - switchT0;
    }

    void handlePacketIn(OfInput input) {
        logger.debug("{} - {}", getClass().getCanonicalName(), input);

        if (input.packetInCookieMismatchAll(logger, OF_CATCH_RULE_COOKIE, OF_ROUND_TRIP_RULE_COOKIE)) {
            return;
        }

        DiscoveryPacket discoveryPacket;
        try {
            discoveryPacket = deserialize(input.getPacketInPayload());
            if (discoveryPacket == null) {
                logger.trace("Invalid discovery packet: {}", input.getPacketInPayload());
                return;
            }
        } catch (Exception exception) {
            logger.trace("Deserialization failure: {}, exception: {}", exception.getMessage(), exception);
            return;
        }

        try {
            IOFSwitch destSwitch = switchService.getSwitch(input.getDpId());
            if (destSwitch == null) {
                logger.error("Destination switch {} was not found", input.getDpId());
                return;
            }

            DiscoveryPacketData data = parseDiscoveryPacket(discoveryPacket, input.getLatency());

            if (!data.isSigned()) {
                logger.warn("discovery packet without sign");
                return;
            }

            if (discoveryPacket.isRoundTripLatency()) {
                handleRoundTripLatency(data, input);
            } else {
                handleDiscoveryPacket(input, destSwitch, data);
            }

        } catch (UnsupportedOperationException exception) {
            logger.error("could not parse packet_in message: {}", exception.getMessage(), exception);
        } catch (Exception exception) {
            logger.error(String.format("Unhandled exception %s", input), exception);
            throw exception;
        }
    }

    private void handleDiscoveryPacket(OfInput input, IOFSwitch destSwitch, DiscoveryPacketData data) {
        OFPort inPort = OFMessageUtils.getInPort((OFPacketIn) input.getMessage());
        long latency = measureLatency(input, data.getTimestamp());
        logIsl.info("link discovered: {}-{} ===( {} ns )===> {}-{} id:{} OF-xid:{}",
                    data.getRemoteSwitchId(), data.getRemotePort(), latency, input.getDpId(), inPort,
                    data.getPacketId(), input.getMessage().getXid());

        // this discovery packet was sent from remote switch/port to received switch/port
        // so the link direction is from remote switch/port to received switch/port
        PathNode source = new PathNode(new SwitchId(data.getRemoteSwitchId().getLong()),
                data.getRemotePort().getPortNumber(), 0);
        PathNode destination = new PathNode(new SwitchId(input.getDpId().getLong()), inPort.getPortNumber(), 1);
        long speed = getSwitchPortSpeed(destSwitch, inPort);

        IslInfoData path = IslInfoData.builder()
                .source(source)
                .destination(destination)
                .speed(speed)
                .state(IslChangeType.DISCOVERED)
                .availableBandwidth(getAvailableBandwidth(speed))
                .packetId(data.getPacketId())
                .build();

        Message message = new InfoMessage(path, System.currentTimeMillis(), CorrelationContext.getId(), null,
                region);

        producerService.sendMessageAndTrack(topoDiscoTopic, source.getSwitchId().toString(), message);
        logger.debug("packet_in processed for {}-{}", input.getDpId(), inPort);

        IslOneWayLatency islOneWayLatency = new IslOneWayLatency(
                source.getSwitchId(),
                source.getPortNo(),
                destination.getSwitchId(),
                destination.getPortNo(),
                latency,
                data.getPacketId());

        sendLatency(islOneWayLatency, source.getSwitchId());
    }

    private void handleRoundTripLatency(DiscoveryPacketData packetData, OfInput input) {
        SwitchId switchId = new SwitchId(input.getDpId().getLong());
        int port = OFMessageUtils.getInPort((OFPacketIn) input.getMessage()).getPortNumber();
        logIsl.debug("got round trip packet: {}_{}, T0: {}, T1: {}, id:{}",
                switchId, port, packetData.getSwitchT0(), packetData.getSwitchT1(), packetData.getPacketId());

        SwitchId switchIdFromPacket = new SwitchId(packetData.getRemoteSwitchId().getLong());
        int portFromPacket = packetData.getRemotePort().getPortNumber();

        if (!Objects.equals(switchId, switchIdFromPacket) || port != portFromPacket) {
            logger.warn("Endpoint from round trip latency package and endpoint from which the package was received "
                            + "are different. Endpoint from package: {}-{}. "
                            + "Endpoint from which package was received: {}-{}",
                    switchIdFromPacket, portFromPacket, switchId, port);
            return;
        }

        long roundTripLatency = calculateRoundTripLatency(
                switchId, port, packetData.getSwitchT0(), packetData.getSwitchT1());

        IslRoundTripLatency islRoundTripLatency = new IslRoundTripLatency(switchId, port, roundTripLatency,
                packetData.getPacketId());
        sendLatency(islRoundTripLatency, switchId);
        logger.debug("Round trip latency packet processed for endpoint {}_{}. "
                        + "t0 timestamp {}, t1 timestamp {}, latency {}.", switchId, port,
                packetData.getSwitchT0(), packetData.getSwitchT1(), roundTripLatency);
    }

    private void sendLatency(InfoData latencyData, SwitchId switchId) {
        InfoMessage infoMessage = new InfoMessage(
                latencyData, System.currentTimeMillis(), CorrelationContext.getId(), null, region);
        producerService.sendMessageAndTrack(islLatencyTopic, switchId.toString(), infoMessage);
    }

    @VisibleForTesting
    DiscoveryPacketData parseDiscoveryPacket(DiscoveryPacket discoveryPacket, long switchLatency) {
        ByteBuffer portBb = ByteBuffer.wrap(discoveryPacket.getPortId().getValue());
        portBb.position(1);
        OFPort remotePort = OFPort.of(portBb.getShort());

        DiscoveryPacketData.DiscoveryPacketDataBuilder builder = DiscoveryPacketData.builder();
        builder.remotePort(remotePort);
        builder.pathOrdinal(10);
        builder.switchT0(-1);
        builder.switchT1(-1);

        for (LLDPTLV lldptlv : discoveryPacket.getOptionalTlvList()) {
            if (matchOptionalLldptlv(lldptlv, REMOTE_SWITCH_OPTIONAL_TYPE, 12)) {
                ByteBuffer dpidBb = ByteBuffer.wrap(lldptlv.getValue());
                builder.remoteSwitchId(DatapathId.of(dpidBb.getLong(LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES)));
            } else if (matchOptionalLldptlv(lldptlv, TIMESTAMP_OPTIONAL_TYPE, 12)) {
                ByteBuffer tsBb = ByteBuffer.wrap(lldptlv.getValue()); // skip OpenFlow OUI (4 bytes above)
                long sendTime = tsBb.getLong(LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES);
                builder.timestamp(sendTime + switchLatency); // include the RX switch latency to "subtract" it
            } else if (matchOptionalLldptlv(lldptlv, PATH_ORDINAL_OPTIONAL_TYPE, 8)) {
                ByteBuffer typeBb = ByteBuffer.wrap(lldptlv.getValue());
                builder.pathOrdinal(typeBb.getInt(LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES));
            } else if (matchOptionalLldptlv(lldptlv, SWITCH_T0_OPTIONAL_TYPE, 12)) {
                builder.switchT0(noviflowTimestamp(Arrays.copyOfRange(
                        lldptlv.getValue(), LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES, lldptlv.getValue().length)));
            } else if (matchOptionalLldptlv(lldptlv, SWITCH_T1_OPTIONAL_TYPE, 12)) {
                builder.switchT1(noviflowTimestamp(Arrays.copyOfRange(
                        lldptlv.getValue(), LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES, lldptlv.getValue().length)));
            } else if (matchOptionalLldptlv(lldptlv, TOKEN_OPTIONAL_TYPE)) {
                ByteBuffer bb = ByteBuffer.wrap(lldptlv.getValue());
                bb.position(LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES);
                byte[] tokenArray = new byte[lldptlv.getLength() - LLDP_TLV_OPTIONAL_HEADER_SIZE_IN_BYTES];
                bb.get(tokenArray, 0, tokenArray.length);
                String token = new String(tokenArray);

                try {
                    DecodedJWT jwt = verifier.verify(token);
                    Claim idClaim = jwt.getClaim("id");
                    if (!idClaim.isNull()) {
                        builder.packetId(idClaim.asLong());
                    }
                    builder.signed(true);
                } catch (JWTVerificationException e) {
                    logger.error("Packet verification failed", e);
                    builder.signed(false);
                }
            }
        }

        return builder.build();
    }

    private boolean matchOptionalLldptlv(LLDPTLV lldpTlv, int type) {
        return lldpTlv.getType() == OPTIONAL_LLDPTV_PACKET_TYPE
                && lldpTlv.getValue()[0] == ORGANIZATIONALLY_UNIQUE_IDENTIFIER[0]
                && lldpTlv.getValue()[1] == ORGANIZATIONALLY_UNIQUE_IDENTIFIER[1]
                && lldpTlv.getValue()[2] == ORGANIZATIONALLY_UNIQUE_IDENTIFIER[2]
                && lldpTlv.getValue()[3] == type;
    }

    private boolean matchOptionalLldptlv(LLDPTLV lldptlv, int type, int length) {
        return matchOptionalLldptlv(lldptlv, type) && lldptlv.getLength() == length;
    }

    private long measureLatency(OfInput input, long sendTime) {
        long latency = -1;
        if (sendTime != 0) {
            latency = TimeUnit.MILLISECONDS.toNanos(input.getReceiveTime() - sendTime);
            if (latency < 0L) {
                latency = -1;
            }
        }
        return latency;
    }

    private long getSwitchPortSpeed(IOFSwitch sw, OFPort inPort) {
        OFPortDesc port = sw.getPort(inPort);
        long speed = -1;

        if (port.getVersion().compareTo(OFVersion.OF_13) > 0) {
            for (OFPortDescProp prop : port.getProperties()) {
                if (prop.getType() == 0) {
                    speed = ((OFPortDescPropEthernet) prop).getCurrSpeed();
                    break;
                }
            }
        }

        if (speed < 0) {
            speed = port.getCurrSpeed();
        }

        return speed;
    }

    private long getAvailableBandwidth(long speed) {
        return (long) (speed * islBandwidthQuotient);
    }
}
