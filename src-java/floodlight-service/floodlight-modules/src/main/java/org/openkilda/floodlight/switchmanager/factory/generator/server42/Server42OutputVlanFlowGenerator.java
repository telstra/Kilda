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

package org.openkilda.floodlight.switchmanager.factory.generator.server42;

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetDstMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetOutputPort;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetSrcMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.buildInstructionApplyActions;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.NOVIFLOW_TIMESTAMP_SIZE_IN_BITS;
import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_FORWARD_UDP_PORT;
import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_OUTPUT_VLAN_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_REVERSE_UDP_PORT;
import static org.openkilda.model.Cookie.SERVER_42_OUTPUT_VLAN_COOKIE;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.floodlight.switchmanager.factory.generator.SwitchFlowGenerator;
import org.openkilda.model.MacAddress;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.List;

public class Server42OutputVlanFlowGenerator implements SwitchFlowGenerator {

    private FeatureDetectorService featureDetectorService;
    private int server42Port;
    private MacAddress server42MacAddress;

    @Builder
    public Server42OutputVlanFlowGenerator(
            FeatureDetectorService featureDetectorService, int server42Port, MacAddress server42MacAddress) {
        this.featureDetectorService = featureDetectorService;
        this.server42Port = server42Port;
        this.server42MacAddress = server42MacAddress;
    }

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            return SwitchFlowTuple.EMPTY;
        }

        OFFactory ofFactory = sw.getOFFactory();
        Match match = buildMatch(sw.getId(), ofFactory);

        List<OFAction> actions = ImmutableList.of(
                buildCopyTimestamp(ofFactory),
                actionSetSrcMac(ofFactory, convertDpIdToMac(sw.getId())),
                actionSetDstMac(ofFactory, org.projectfloodlight.openflow.types.MacAddress.of(
                        server42MacAddress.getAddress())),
                actionSetOutputPort(ofFactory, OFPort.of(server42Port)));

        OFFlowMod flowMod = prepareFlowModBuilder(
                ofFactory, SERVER_42_OUTPUT_VLAN_COOKIE, SERVER_42_OUTPUT_VLAN_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(buildInstructionApplyActions(ofFactory, actions)))
                .build();
        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .build();
    }

    private static Match buildMatch(DatapathId dpid, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.ETH_DST, convertDpIdToMac(dpid))
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_SRC, TransportPort.of(SERVER_42_REVERSE_UDP_PORT))
                .setExact(MatchField.UDP_DST, TransportPort.of(SERVER_42_FORWARD_UDP_PORT))
                .build();
    }

    private static OFAction buildCopyTimestamp(OFFactory factory) {
        OFOxms oxms = factory.oxms();
        return factory.actions().buildNoviflowCopyField()
                .setNBits(NOVIFLOW_TIMESTAMP_SIZE_IN_BITS)
                .setSrcOffset(0)
                .setDstOffset(NOVIFLOW_TIMESTAMP_SIZE_IN_BITS)
                .setOxmSrcHeader(oxms.buildNoviflowTxtimestamp().getTypeLen())
                .setOxmDstHeader(oxms.buildNoviflowUpdPayload().getTypeLen())
                .build();
    }
}
