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

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.DROP_VERIFICATION_LOOP_RULE_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_12;

import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;

import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.MacAddress;

@Builder
public class DropLoopFlowGenerator implements SwitchFlowGenerator {

    private String verificationBcastPacketDst;

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        OFFactory ofFactory = sw.getOFFactory();
        if (ofFactory.getVersion() == OF_12) {
            return SwitchFlowTuple.EMPTY;
        }

        Match.Builder builder = ofFactory.buildMatch();
        builder.setExact(MatchField.ETH_DST, MacAddress.of(verificationBcastPacketDst));
        builder.setExact(MatchField.ETH_SRC, convertDpIdToMac(sw.getId()));
        Match match = builder.build();

        OFFlowMod flowMod = prepareFlowModBuilder(ofFactory,
                ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.DROP_VERIFICATION_LOOP_RULE_COOKIE).getValue(),
                DROP_VERIFICATION_LOOP_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .build();

        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .build();
    }
}
