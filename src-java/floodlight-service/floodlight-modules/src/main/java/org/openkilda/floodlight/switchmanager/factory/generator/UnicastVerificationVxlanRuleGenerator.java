/* Copyright 2020 Telstra Open Source
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

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSendToController;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetDstMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.STUB_VXLAN_UDP_SRC;
import static org.openkilda.floodlight.switchmanager.SwitchManager.VERIFICATION_RULE_VXLAN_PRIORITY;
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.KildaCore;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.SwitchManagerConfig;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.model.Cookie;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMeterMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionMeter;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.ArrayList;
import java.util.List;

public class UnicastVerificationVxlanRuleGenerator extends MeteredFlowGenerator {

    private KildaCore kildaCore;

    @Builder
    public UnicastVerificationVxlanRuleGenerator(FeatureDetectorService featureDetectorService,
                                                 SwitchManagerConfig config, KildaCore kildaCore) {
        super(featureDetectorService, config);
        this.kildaCore = kildaCore;
    }

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        // NOTE(tdurakov): reusing copy field feature here, since only switches with it supports pop/push vxlan's
        // should be replaced with fair feature detection based on ActionId's during handshake
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            return SwitchFlowTuple.EMPTY;
        }

        ArrayList<OFAction> actionList = new ArrayList<>();
        Cookie cookie = ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE);
        long meterId = createMeterIdForDefaultRule(cookie.getValue()).getValue();
        long meterRate = config.getUnicastRateLimit();
        OFMeterMod meter = generateMeterForDefaultRule(sw, meterId, meterRate,
                config.getSystemMeterBurstSizeInPackets(), config.getDiscoPacketSize());
        OFInstructionMeter ofInstructionMeter = buildMeterInstruction(meterId, sw, actionList);

        OFFlowMod flowMod = buildUnicastVerificationRuleVxlan(sw, cookie.getValue(), ofInstructionMeter, actionList);

        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .meter(meter)
                .build();
    }

    private OFFlowMod buildUnicastVerificationRuleVxlan(IOFSwitch sw, long cookie, OFInstructionMeter meter,
                                                        ArrayList<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        actionList.add(ofFactory.actions().noviflowPopVxlanTunnel());
        actionList.add(actionSendToController(sw.getOFFactory()));

        MacAddress macAddress = convertDpIdToMac(sw.getId());
        actionList.add(actionSetDstMac(sw.getOFFactory(), macAddress));
        List<OFInstruction> instructions = new ArrayList<>(2);
        if (meter != null) {
            instructions.add(meter);
        }
        instructions.add(ofFactory.instructions().applyActions(actionList));

        MacAddress srcMac = MacAddress.of(kildaCore.getConfig().getFlowPingMagicSrcMacAddress());
        Match.Builder builder = sw.getOFFactory().buildMatch();
        builder.setMasked(MatchField.ETH_SRC, srcMac, MacAddress.NO_MASK);
        builder.setMasked(MatchField.ETH_DST, macAddress, MacAddress.NO_MASK);
        builder.setExact(MatchField.ETH_TYPE, EthType.IPv4);
        builder.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
        builder.setExact(MatchField.UDP_SRC, TransportPort.of(STUB_VXLAN_UDP_SRC));
        return prepareFlowModBuilder(ofFactory, cookie, VERIFICATION_RULE_VXLAN_PRIORITY, INPUT_TABLE_ID)
                .setInstructions(instructions)
                .setMatch(builder.build())
                .build();
    }
}
