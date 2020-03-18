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

import static org.openkilda.floodlight.pathverification.PathVerificationService.LATENCY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.pathverification.PathVerificationService.ROUND_TRIP_LATENCY_T1_OFFSET;
import static org.openkilda.floodlight.pathverification.PathVerificationService.ROUND_TRIP_LATENCY_TIMESTAMP_SIZE;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSendToController;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.ROUND_TRIP_LATENCY_RULE_PRIORITY;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.model.Cookie;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.List;

@Builder
public class RoundTripLatencyFlowGenerator implements SwitchFlowGenerator {

    private FeatureDetectorService featureDetectorService;
    private String verificationBcastPacketDst;

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            return SwitchFlowTuple.EMPTY;
        }

        OFFactory ofFactory = sw.getOFFactory();
        Cookie cookie = ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.ROUND_TRIP_LATENCY_RULE_COOKIE);
        Match match = roundTripLatencyRuleMatch(sw.getId(), ofFactory);
        List<OFAction> actions = ImmutableList.of(
                actionAddRxTimestamp(sw),
                actionSendToController(sw.getOFFactory()));
        OFFlowMod flowMod = prepareFlowModBuilder(
                ofFactory, cookie.getValue(), ROUND_TRIP_LATENCY_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setActions(actions)
                .build();
        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .build();
    }

    private Match roundTripLatencyRuleMatch(DatapathId dpid, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.ETH_SRC, convertDpIdToMac(dpid))
                .setExact(MatchField.ETH_DST, MacAddress.of(verificationBcastPacketDst))
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_DST, TransportPort.of(LATENCY_PACKET_UDP_PORT))
                .build();
    }

    private static OFAction actionAddRxTimestamp(final IOFSwitch sw) {
        OFOxms oxms = sw.getOFFactory().oxms();
        OFActions actions = sw.getOFFactory().actions();
        return actions.buildNoviflowCopyField()
                .setNBits(ROUND_TRIP_LATENCY_TIMESTAMP_SIZE)
                .setSrcOffset(0)
                .setDstOffset(ROUND_TRIP_LATENCY_T1_OFFSET)
                .setOxmSrcHeader(oxms.buildNoviflowRxtimestamp().getTypeLen())
                .setOxmDstHeader(oxms.buildNoviflowPacketOffset().getTypeLen())
                .build();
    }
}
