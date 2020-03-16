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

package org.openkilda.floodlight.switchmanager.factory.generator.lldp;

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSendToController;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.LLDP_POST_INGRESS_VXLAN_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.LLDP_VXLAN_UDP_SRC;
import static org.openkilda.floodlight.switchmanager.SwitchManager.POST_INGRESS_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.VXLAN_UDP_DST;
import static org.openkilda.model.Cookie.LLDP_POST_INGRESS_VXLAN_COOKIE;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN;

import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.SwitchManagerConfig;
import org.openkilda.model.SwitchFeature;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionMeter;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.TransportPort;

import java.util.List;
import java.util.Set;

public class LldpPostIngressVxlanFlowGenerator extends LldpFlowGenerator {

    @Builder
    public LldpPostIngressVxlanFlowGenerator(FeatureDetectorService featureDetectorService,
                                             SwitchManagerConfig config) {
        super(featureDetectorService, config);
    }

    @Override
    OFFlowMod getLldpFlowMod(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Set<SwitchFeature> features = featureDetectorService.detectSwitch(sw);
        if (!(features.contains(NOVIFLOW_PUSH_POP_VXLAN) && features.contains(NOVIFLOW_COPY_FIELD))) {
            return null;
        }

        // TODO(snikitin): add match by metadata after fixing of https://github.com/telstra/open-kilda/issues/3199
        Match match = ofFactory.buildMatch()
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_SRC, TransportPort.of(LLDP_VXLAN_UDP_SRC))
                .setExact(MatchField.UDP_DST, TransportPort.of(VXLAN_UDP_DST))
                .build();

        actionList.add(ofFactory.actions().noviflowPopVxlanTunnel());
        actionList.add(actionSendToController(sw.getOFFactory()));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();

        return prepareFlowModBuilder(ofFactory, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_VXLAN_PRIORITY, POST_INGRESS_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    long getCookie() {
        return LLDP_POST_INGRESS_VXLAN_COOKIE;
    }
}
