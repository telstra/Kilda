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

import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetUdpDstAction;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionSetUdpSrcAction;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.buildInstructionApplyActions;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.instructionGoToTable;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.prepareFlowModBuilder;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INPUT_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.NOVIFLOW_TIMESTAMP_SIZE_IN_BITS;
import static org.openkilda.floodlight.switchmanager.SwitchManager.PRE_INGRESS_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_FORWARD_UDP_PORT;
import static org.openkilda.floodlight.switchmanager.SwitchManager.SERVER_42_INPUT_PRIORITY;
import static org.openkilda.model.Cookie.encodeServer42InputInput;
import static org.openkilda.model.Metadata.METADATA_CUSTOMER_PORT_MASK;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;

import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.floodlight.switchmanager.factory.generator.SwitchFlowGenerator;
import org.openkilda.model.Metadata;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.List;

public class Server42InputFlowGenerator implements SwitchFlowGenerator {

    private static final int UDP_PORT_OFFSET = 5000;

    private FeatureDetectorService featureDetectorService;
    private int server42Port;
    private int customerPort;

    @Builder
    public Server42InputFlowGenerator(
            FeatureDetectorService featureDetectorService, int server42Port, int customerPort) {
        this.featureDetectorService = featureDetectorService;
        this.server42Port = server42Port;
        this.customerPort = customerPort;
    }

    @Override
    public SwitchFlowTuple generateFlow(IOFSwitch sw) {
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            return SwitchFlowTuple.EMPTY;
        }

        OFFactory ofFactory = sw.getOFFactory();
        Match match = buildMatch(ofFactory, server42Port, customerPort + UDP_PORT_OFFSET); // use free udp ports

        List<OFAction> actions = ImmutableList.of(
                actionSetUdpSrcAction(ofFactory, TransportPort.of(SERVER_42_FORWARD_UDP_PORT)),
                actionSetUdpDstAction(ofFactory, TransportPort.of(SERVER_42_FORWARD_UDP_PORT)),
                buildCopyTimestamp(ofFactory));

        List<OFInstruction> instructions = ImmutableList.of(
                buildInstructionApplyActions(ofFactory, actions),
                instructionWriteMetadata(ofFactory, customerPort),
                instructionGoToTable(ofFactory, TableId.of(PRE_INGRESS_TABLE_ID)));

        OFFlowMod flowMod = prepareFlowModBuilder(
                ofFactory, encodeServer42InputInput(customerPort), SERVER_42_INPUT_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(instructions)
                .build();
        return SwitchFlowTuple.builder()
                .sw(sw)
                .flow(flowMod)
                .build();
    }

    private static Match buildMatch(OFFactory ofFactory, int server42Port, int udpSrcPort) {
        return ofFactory.buildMatch()
                .setExact(MatchField.IN_PORT, OFPort.of(server42Port))
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_SRC, TransportPort.of(udpSrcPort))
                .build();
    }

    private static OFAction buildCopyTimestamp(OFFactory factory) {
        OFOxms oxms = factory.oxms();
        return factory.actions().buildNoviflowCopyField()
                .setNBits(NOVIFLOW_TIMESTAMP_SIZE_IN_BITS)
                .setSrcOffset(0)
                .setDstOffset(0)
                .setOxmSrcHeader(oxms.buildNoviflowTxtimestamp().getTypeLen())
                .setOxmDstHeader(oxms.buildNoviflowUpdPayload().getTypeLen())
                .build();
    }


    private static OFInstruction instructionWriteMetadata(OFFactory ofFactory, int customerPort) {
        return ofFactory.instructions().buildWriteMetadata()
                .setMetadata(U64.of(Metadata.encodeCustomerPort(customerPort)))
                .setMetadataMask(U64.of(METADATA_CUSTOMER_PORT_MASK)).build();
    }
}
