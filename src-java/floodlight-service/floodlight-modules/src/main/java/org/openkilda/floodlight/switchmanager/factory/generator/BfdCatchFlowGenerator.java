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

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.BDF_DEFAULT_PORT;
import static org.openkilda.floodlight.switchmanager.SwitchManager.CATCH_BFD_RULE_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;

import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.model.Cookie;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.Set;

@Builder
public class BfdCatchFlowGenerator implements SwitchFlowGenerator {

    private FeatureDetectorService featureDetectorService;

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        Set<SwitchFeature> features = featureDetectorService.detectSwitch(sw);
        if (!features.contains(SwitchFeature.BFD)) {
            return SwitchFlowTuple.EMPTY;
        }

        OFFactory ofFactory = sw.getOFFactory();

        Cookie cookie = ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.CATCH_BFD_RULE_COOKIE);
        Match match = catchRuleMatch(sw.getId(), ofFactory);
        OFFlowMod flowMod = prepareFlowModBuilder(ofFactory, cookie.getValue(),
                CATCH_BFD_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setActions(ImmutableList.of(
                        ofFactory.actions().buildOutput()
                                .setPort(OFPort.LOCAL)
                                .build()))
                .build();
        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .build();
    }

    private static Match catchRuleMatch(DatapathId dpid, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.ETH_DST, convertDpIdToMac(dpid))
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_DST, TransportPort.of(BDF_DEFAULT_PORT))
                .build();
    }
}
