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

package org.openkilda.floodlight.switchmanager.factory.generator;

import static org.openkilda.floodlight.pathverification.PathVerificationService.DISCOVERY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.pathverification.PathVerificationService.LATENCY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSendToController;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetDstMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.ROUND_TRIP_LATENCY_GROUP_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.VERIFICATION_RULE_PRIORITY;
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule;
import static org.openkilda.model.SwitchFeature.MATCH_UDP_PORT;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_12;

import org.openkilda.floodlight.KildaCore;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.SwitchManagerConfig;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFBucket;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFGroupAdd;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFMeterMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionMeter;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.ArrayList;
import java.util.List;

public class VerificationFlowGenerator extends MeteredFlowGenerator {

    private boolean broadcast;
    private KildaCore kildaCore;
    private String verificationBcastPacketDst;

    @Builder
    public VerificationFlowGenerator(FeatureDetectorService featureDetectorService, boolean broadcast,
                                     KildaCore kildaCore, SwitchManagerConfig config,
                                     String verificationBcastPacketDst) {
        super(featureDetectorService, config);
        this.broadcast = broadcast;
        this.kildaCore = kildaCore;
        this.verificationBcastPacketDst = verificationBcastPacketDst;
    }

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        long cookie = ServiceCookieSchema.INSTANCE.make(
                broadcast
                        ? ServiceCookieTag.VERIFICATION_BROADCAST_RULE_COOKIE
                        : ServiceCookieTag.VERIFICATION_UNICAST_RULE_COOKIE)
                .getValue();
        long meterId = createMeterIdForDefaultRule(cookie).getValue();
        long meterRate = broadcast ? config.getBroadcastRateLimit() : config.getUnicastRateLimit();
        OFMeterMod meter = generateMeterForDefaultRule(sw, meterId, meterRate,
                config.getSystemMeterBurstSizeInPackets(), config.getDiscoPacketSize());
        OFInstructionMeter ofInstructionMeter = buildMeterInstruction(meterId, sw, actionList);

        OFGroupAdd group = null;
        if (broadcast && featureDetectorService.detectSwitch(sw)
                .contains(SwitchFeature.GROUP_PACKET_OUT_CONTROLLER)) {
            group = getInstallRoundTripLatencyGroupInstruction(sw);
            actionList.add(sw.getOFFactory().actions().group(group.getGroup()));
        } else {
            addStandardDiscoveryActions(sw, actionList);
        }

        OFFlowMod flowMod = buildVerificationRule(sw, broadcast, cookie, ofInstructionMeter, actionList);

        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .meter(meter)
                .group(group)
                .build();
    }

    private static void addStandardDiscoveryActions(IOFSwitch sw, ArrayList<OFAction> actionList) {
        actionList.add(actionSendToController(sw.getOFFactory()));
        actionList.add(actionSetDstMac(sw.getOFFactory(), convertDpIdToMac(sw.getId())));
    }

    private OFFlowMod buildVerificationRule(IOFSwitch sw, boolean isBroadcast, long cookie,
                                            OFInstructionMeter meter, ArrayList<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        if (!isBroadcast && ofFactory.getVersion().compareTo(OF_12) <= 0) {
            return null;
        }

        OFInstructionApplyActions actions = ofFactory.instructions()
                .applyActions(actionList).createBuilder().build();

        Match match = matchVerification(sw, isBroadcast);
        return prepareFlowModBuilder(ofFactory, cookie, VERIFICATION_RULE_PRIORITY, INPUT_TABLE_ID)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .setMatch(match)
                .build();
    }

    private Match matchVerification(final IOFSwitch sw, final boolean isBroadcast) {
        MacAddress dstMac = isBroadcast ? MacAddress.of(verificationBcastPacketDst) : convertDpIdToMac(sw.getId());
        Match.Builder builder = sw.getOFFactory().buildMatch();
        if (isBroadcast) {
            builder.setMasked(MatchField.ETH_DST, dstMac, MacAddress.NO_MASK);
            if (featureDetectorService.detectSwitch(sw).contains(MATCH_UDP_PORT)) {
                builder.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
                builder.setExact(MatchField.ETH_TYPE, EthType.IPv4);
                builder.setExact(MatchField.UDP_DST, TransportPort.of(DISCOVERY_PACKET_UDP_PORT));
            }
        } else {
            MacAddress srcMac = MacAddress.of(kildaCore.getConfig().getFlowPingMagicSrcMacAddress());
            builder.setMasked(MatchField.ETH_SRC, srcMac, MacAddress.NO_MASK);
            builder.setMasked(MatchField.ETH_DST, dstMac, MacAddress.NO_MASK);
        }
        return builder.build();
    }

    private static OFGroupAdd getInstallRoundTripLatencyGroupInstruction(IOFSwitch sw) {
        OFFactory ofFactory = sw.getOFFactory();
        List<OFBucket> bucketList = new ArrayList<>();
        bucketList.add(ofFactory
                .buildBucket()
                .setActions(Lists.newArrayList(
                        actionSetDstMac(sw.getOFFactory(), convertDpIdToMac(sw.getId())),
                        actionSendToController(sw.getOFFactory())))
                .setWatchGroup(OFGroup.ANY)
                .build());

        TransportPort udpPort = TransportPort.of(LATENCY_PACKET_UDP_PORT);
        List<OFAction> latencyActions = ImmutableList.of(
                ofFactory.actions().setField(ofFactory.oxms().udpDst(udpPort)),
                actionSetOutputPort(ofFactory));

        bucketList.add(ofFactory
                .buildBucket()
                .setActions(latencyActions)
                .setWatchGroup(OFGroup.ANY)
                .build());

        return ofFactory.buildGroupAdd()
                .setGroup(OFGroup.of(ROUND_TRIP_LATENCY_GROUP_ID))
                .setGroupType(OFGroupType.ALL)
                .setBuckets(bucketList)
                .build();
    }

    private static OFAction actionSetOutputPort(final OFFactory ofFactory) {
        OFActions actions = ofFactory.actions();
        return actions.buildOutput().setMaxLen(0xFFFFFFFF).setPort(OFPort.IN_PORT).build();
    }
}
