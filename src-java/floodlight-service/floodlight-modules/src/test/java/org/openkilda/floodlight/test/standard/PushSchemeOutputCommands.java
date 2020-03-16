/* Copyright 2017 Telstra Open Source
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

package org.openkilda.floodlight.test.standard;

import static java.util.Collections.singletonList;
import static org.openkilda.floodlight.switchmanager.SwitchManager.DEFAULT_FLOW_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.DEFAULT_FLOW_VXLAN_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.FLOW_COOKIE_MASK;
import static org.openkilda.floodlight.switchmanager.SwitchManager.FLOW_PRIORITY;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INTERNAL_ETH_SRC_OFFSET;
import static org.openkilda.floodlight.switchmanager.SwitchManager.MAC_ADDRESS_SIZE_IN_BITS;
import static org.openkilda.messaging.Utils.ETH_TYPE;

import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.model.FlowEncapsulationType;

import com.google.common.collect.ImmutableSet;
import net.floodlightcontroller.util.FlowModUtils;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.U64;

import java.util.Arrays;
import java.util.List;

/**
 * Represent OF commands.
 * Code duplication is for more clear commands representation.
 */
public class PushSchemeOutputCommands implements OutputCommands {

    @Override
    public OFFlowAdd ingressMatchVlanIdFlowMod(DatapathId dpid, int inputPort, int outputPort, int inputVlan,
                                               int tunnelId, long meterId, long cookie,
                                               FlowEncapsulationType encapsulationType, DatapathId egressSwitchDpId) {
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(FLOW_PRIORITY)
                .setMatch(matchFlow(null, inputPort, inputVlan, encapsulationType))
                .setInstructions(Arrays.asList(
                        ofFactory.instructions().buildMeter().setMeterId(meterId).build(),
                        ofFactory.instructions()
                                .applyActions(getPushActions(dpid, outputPort, tunnelId, encapsulationType,
                                        egressSwitchDpId))
                                .createBuilder()
                                .build()))
                .setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS))
                .setXid(0L)
                .build();

    }

    @Override
    public OFFlowAdd ingressNoMatchVlanIdFlowMod(DatapathId dpid, int inputPort, int outputPort, int tunnelId,
                                                 long meterId, long cookie, FlowEncapsulationType encapsulationType,
                                                 DatapathId egressSwitchDpId) {
        int priority = FlowEncapsulationType.VXLAN.equals(encapsulationType)
                ? DEFAULT_FLOW_VXLAN_PRIORITY : DEFAULT_FLOW_PRIORITY;
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(priority)
                .setMatch(ofFactory.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(inputPort))
                        .build())
                .setInstructions(Arrays.asList(
                        ofFactory.instructions().buildMeter().setMeterId(meterId).build(),
                        ofFactory.instructions()
                                .applyActions(getPushActions(dpid, outputPort, tunnelId, encapsulationType,
                                        egressSwitchDpId))
                                .createBuilder()
                                .build()))
                .setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS))
                .setXid(0L)
                .build();

    }

    @Override
    public OFFlowAdd egressPushFlowMod(DatapathId dpid, int inputPort, int outputPort, int tunnelId, int outputVlan,
                                       long cookie, FlowEncapsulationType encapsulationType) {
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(FLOW_PRIORITY)
                .setMatch(matchFlow(dpid, inputPort, tunnelId, encapsulationType))
                .setInstructions(singletonList(
                        ofFactory.instructions().applyActions(Arrays.asList(
                                ofFactory.actions().popVlan(),
                                ofFactory.actions().buildPushVlan()
                                        .setEthertype(EthType.of(ETH_TYPE))
                                        .build(),
                                ofFactory.actions().buildSetField()
                                        .setField(ofFactory.oxms().buildVlanVid()
                                                .setValue(OFVlanVidMatch.ofVlan(outputVlan))
                                                .build())
                                        .build(),
                                ofFactory.actions().buildOutput()
                                        .setMaxLen(0xFFFFFFFF)
                                        .setPort(OFPort.of(outputPort))
                                        .build()))
                                .createBuilder()
                                .build()))
                .setXid(0L)
                .build();
    }

    private List<OFAction> getPushActions(DatapathId dpid, int outputPort, int tunnelId,
                                          FlowEncapsulationType encapsulationType, DatapathId egressSwitchDpId) {
        switch (encapsulationType) {
            default:
            case TRANSIT_VLAN:
                return Arrays.asList(
                        ofFactory.actions().buildPushVlan()
                                .setEthertype(EthType.of(ETH_TYPE))
                                .build(),
                        ofFactory.actions().buildSetField()
                                .setField(ofFactory.oxms().buildVlanVid()
                                        .setValue(OFVlanVidMatch.ofVlan(tunnelId))
                                        .build())
                                .build(),
                        ofFactory.actions().buildOutput()
                                .setMaxLen(0xFFFFFFFF)
                                .setPort(OFPort.of(outputPort))
                                .build());
            case VXLAN:
                return getPushVxlanAction(dpid, outputPort, tunnelId, egressSwitchDpId);
        }
    }

    protected List<OFAction> getPushVxlanAction(DatapathId dpid, int outputPort, int tunnelId,
                                                DatapathId egressSwitchDpId) {
        OFOxms oxms = ofFactory.oxms();

        return Arrays.asList(
                ofFactory.actions().buildNoviflowPushVxlanTunnel()
                        .setFlags((short) 0x01)
                        .setEthSrc(MacAddress.of(Arrays.copyOfRange(dpid.getBytes(), 2, 8)))
                        .setEthDst(MacAddress.of(Arrays.copyOfRange(egressSwitchDpId.getBytes(), 2, 8)))
                        .setIpv4Src(SwitchManager.STUB_VXLAN_IPV4_SRC)
                        .setIpv4Dst(SwitchManager.STUB_VXLAN_IPV4_DST)
                        .setUdpSrc(SwitchManager.STUB_VXLAN_UDP_SRC)
                        .setVni(tunnelId)
                        .build(),
                ofFactory.actions().buildNoviflowCopyField().setNBits(MAC_ADDRESS_SIZE_IN_BITS)
                        .setSrcOffset(INTERNAL_ETH_SRC_OFFSET)
                        .setDstOffset(48)
                        .setOxmSrcHeader(oxms.buildNoviflowPacketOffset().getTypeLen())
                        .setOxmDstHeader(oxms.buildNoviflowPacketOffset().getTypeLen()).build(),
                ofFactory.actions().buildOutput()
                        .setMaxLen(0xFFFFFFFF)
                        .setPort(OFPort.of(outputPort))
                        .build());
    }

    private List<OFAction> getPopActions(int outputPort, FlowEncapsulationType encapsulationType) {
        switch (encapsulationType) {
            default:
            case TRANSIT_VLAN:
                return Arrays.asList(
                        ofFactory.actions().popVlan(),
                        ofFactory.actions().buildOutput()
                                .setMaxLen(0xFFFFFFFF)
                                .setPort(OFPort.of(outputPort))
                                .build());
            case VXLAN:
                return Arrays.asList(
                        ofFactory.actions().noviflowPopVxlanTunnel(),
                        ofFactory.actions().buildOutput()
                                .setMaxLen(0xFFFFFFFF)
                                .setPort(OFPort.of(outputPort))
                                .build());
        }
    }

    @Override
    public OFFlowAdd egressPopFlowMod(DatapathId dpid, int inputPort, int outputPort, int tunnelId, long cookie,
                                      FlowEncapsulationType encapsulationType) {
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(FLOW_PRIORITY)
                .setMatch(matchFlow(dpid, inputPort, tunnelId, encapsulationType))
                .setInstructions(singletonList(
                        ofFactory.instructions().applyActions(getPopActions(outputPort, encapsulationType))
                                .createBuilder()
                                .build()))
                .setXid(0L)
                .build();
    }

    @Override
    public OFFlowAdd egressFlowMod(DatapathId dpid, int inputPort, int outputPort, int tunnelId, long cookie,
                                   FlowEncapsulationType encapsulationType, OFInstructionApplyActions actions) {
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(FLOW_PRIORITY)
                .setMatch(matchFlow(dpid, inputPort, tunnelId, encapsulationType))
                .setInstructions(singletonList(actions))
                .setXid(0L)
                .build();
    }

    @Override
    public OFFlowAdd egressNoneFlowMod(DatapathId dpid, int inputPort, int outputPort, int tunnelId, long cookie,
                                       FlowEncapsulationType encapsulationType) {
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(FLOW_PRIORITY)
                .setMatch(matchFlow(dpid, inputPort, tunnelId, encapsulationType))
                .setInstructions(singletonList(
                        ofFactory.instructions().applyActions(getPopActions(outputPort, encapsulationType))
                                .createBuilder()
                                .build()))
                .setXid(0L)
                .build();
    }

    @Override
    public OFFlowAdd egressReplaceFlowMod(DatapathId dpid, int inputPort, int outputPort, int inputVlan, int outputVlan,
                                          long cookie, FlowEncapsulationType encapsulationType) {
        return ofFactory.buildFlowAdd()
                .setCookie(U64.of(cookie & FLOW_COOKIE_MASK))
                .setHardTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setPriority(FLOW_PRIORITY)
                .setMatch(ofFactory.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(inputPort))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(inputVlan))
                        .build())
                .setInstructions(singletonList(
                        ofFactory.instructions().applyActions(Arrays.asList(
                                ofFactory.actions().popVlan(),
                                ofFactory.actions().buildSetField()
                                        .setField(ofFactory.oxms().buildVlanVid()
                                                .setValue(OFVlanVidMatch.ofVlan(outputVlan))
                                                .build())
                                        .build(),
                                ofFactory.actions().buildOutput()
                                        .setMaxLen(0xFFFFFFFF)
                                        .setPort(OFPort.of(outputPort))
                                        .build()))
                                .createBuilder()
                                .build()))
                .setXid(0L)
                .build();
    }
}
