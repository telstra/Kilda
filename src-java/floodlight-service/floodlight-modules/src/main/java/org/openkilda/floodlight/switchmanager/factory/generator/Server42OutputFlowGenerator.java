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
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.ROUND_TRIP_LATENCY_RULE_PRIORITY;
import static org.openkilda.model.Cookie.SERVER_42_OUTPUT_COOKIE;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;

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
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.List;

@Builder
public class Server42OutputFlowGenerator implements SwitchFlowGenerator {

    private FeatureDetectorService featureDetectorService;

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            return SwitchFlowTuple.EMPTY;
        }

        OFFactory ofFactory = sw.getOFFactory();
        Match match = buildMatch(sw.getId(), ofFactory);
        List<OFAction> actions = ImmutableList.of(
                actionAddTxTimestamp(sw),
                actionSendToController(sw.getOFFactory()));
        OFFlowMod flowMod = prepareFlowModBuilder(
                ofFactory, SERVER_42_OUTPUT_COOKIE, ROUND_TRIP_LATENCY_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setActions(actions)
                .build();
        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .build();
    }

    private Match buildMatch(DatapathId dpid, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_SRC, TransportPort.of(4702))
                .setExact(MatchField.UDP_DST, TransportPort.of(4703))
                .build();
    }

    private static OFAction actionAddTxTimestamp(final IOFSwitch sw) {
        OFOxms oxms = sw.getOFFactory().oxms();
        OFActions actions = sw.getOFFactory().actions();
        try {
            return actions.buildNoviflowCopyField()
                    .setNBits(64)
                    .setSrcOffset(0)
                    .setDstOffset(64 * 8)
                    .setOxmSrcHeader(oxms.buildNoviflowTxtimestamp().getTypeLen())
                    .setOxmDstHeader(oxms.buildNoviflowUpdPayload().getTypeLen())
                    .build();
        } catch (Exception e) {
            throw e;
        }
    }
}
