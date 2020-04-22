/* Copyright 2018 Telstra Open Source
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

package org.openkilda.floodlight.converter;

import static org.junit.Assert.assertEquals;

import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowCopyFieldAction;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;
import org.openkilda.messaging.info.rule.FlowSwapFieldAction;
import org.openkilda.messaging.info.stats.FlowStatsData;
import org.openkilda.messaging.info.stats.FlowStatsEntry;
import org.openkilda.model.SwitchId;

import com.google.common.collect.Lists;
import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.ver13.OFFactoryVer13;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OfFlowStatsMapperTest {

    public static final OFFactory factory = new OFFactoryVer13();
    public static final short tableId = 1;
    public static final long cookie = 2;
    public static final long packetCount = 3;
    public static final long byteCount = 4;
    public static final int durationSec = 5;
    public static final int durationNsec = 6;
    public static final int hardTimeout = 7;
    public static final int idleTimeout = 8;
    public static final int priority = 9;
    public static final OFVlanVidMatch vlanVid = OFVlanVidMatch.ofVlan(10);
    public static final MacAddress ethDst = MacAddress.of(11);
    public static final OFPort port = OFPort.of(12);
    public static final TransportPort udpDst = TransportPort.of(13);
    public static final TransportPort udpSrc = TransportPort.of(14);
    public static final SwitchId switchId = new SwitchId(15);
    public static final int xId = 16;
    public static final long meterId = 17;
    public static final IpProtocol ipProto = IpProtocol.UDP;
    public static final EthType ethType = EthType.IPv4;
    public static final OFFlowModFlags flag = OFFlowModFlags.SEND_FLOW_REM;
    public static final OFGroup group = OFGroup.of(18);
    public static final int bits = 19;
    public static final int srcOffset = 20;
    public static final int dstOffset = 21;
    public static final long oxmSrcHeader = 22;
    public static final long oxmDstHeader = 23;
    public static final TableId goToTable = TableId.of(24);
    private static final String MAC_ADDRESS_1 = "01:01:01:01:01:01";
    private static final String MAC_ADDRESS_2 = "02:02:02:02:02:02";

    @Test
    public void testToFlowStatsData() {
        OFFlowStatsEntry ofEntry = buildFlowStatsEntry();
        OFFlowStatsReply ofReply = factory.buildFlowStatsReply()
                .setXid(xId)
                .setEntries(Collections.singletonList(ofEntry))
                .build();

        FlowStatsData data = OfFlowStatsMapper.INSTANCE.toFlowStatsData(Collections.singletonList(ofReply), switchId);

        assertEquals(switchId, data.getSwitchId());

        assertEquals(1, data.getStats().size());
        FlowStatsEntry entry = data.getStats().get(0);

        assertEquals(tableId, entry.getTableId());
        assertEquals(cookie, entry.getCookie());
        assertEquals(packetCount, entry.getPacketCount());
        assertEquals(byteCount, entry.getByteCount());
    }

    @Test
    public void testFlowEntry() {
        OFFlowStatsEntry ofEntry = buildFlowStatsEntry();

        FlowEntry entry = OfFlowStatsMapper.INSTANCE.toFlowEntry(ofEntry);

        assertEquals(tableId, entry.getTableId());
        assertEquals(cookie, entry.getCookie());
        assertEquals(packetCount, entry.getPacketCount());
        assertEquals(byteCount, entry.getByteCount());
        assertEquals(durationSec, entry.getDurationSeconds());
        assertEquals(durationNsec, entry.getDurationNanoSeconds());
        assertEquals(hardTimeout, entry.getHardTimeout());
        assertEquals(idleTimeout, entry.getIdleTimeout());
        assertEquals(priority, entry.getPriority());

        assertEquals(String.valueOf(vlanVid.getVlan()), entry.getMatch().getVlanVid());
        assertEquals(ethType.toString(), entry.getMatch().getEthType());
        assertEquals(ethDst.toString(), entry.getMatch().getEthDst());
        assertEquals(port.toString(), entry.getMatch().getInPort());
        assertEquals(ipProto.toString(), entry.getMatch().getIpProto());
        assertEquals(udpSrc.toString(), entry.getMatch().getUdpSrc());
        assertEquals(udpDst.toString(), entry.getMatch().getUdpDst());

        FlowSetFieldAction flowSetEthSrcAction = new FlowSetFieldAction("eth_src", MAC_ADDRESS_1);
        FlowSetFieldAction flowSetEthDstAction = new FlowSetFieldAction("eth_dst", MAC_ADDRESS_2);
        FlowCopyFieldAction flowCopyFieldAction = FlowCopyFieldAction.builder()
                .bits(String.valueOf(bits))
                .srcOffset(String.valueOf(srcOffset))
                .dstOffset(String.valueOf(dstOffset))
                .srcOxm(String.valueOf(oxmSrcHeader))
                .dstOxm(String.valueOf(oxmDstHeader))
                .build();
        FlowSwapFieldAction flowSwapFieldAction = FlowSwapFieldAction.builder()
                .bits(String.valueOf(bits))
                .srcOffset(String.valueOf(srcOffset))
                .dstOffset(String.valueOf(dstOffset))
                .srcOxm(String.valueOf(oxmSrcHeader))
                .dstOxm(String.valueOf(oxmDstHeader))
                .build();
        FlowApplyActions applyActions = new FlowApplyActions(port.toString(),
                Lists.newArrayList(flowSetEthSrcAction, flowSetEthDstAction), ethType.toString(), null, null, null,
                group.toString(), flowCopyFieldAction, flowSwapFieldAction);
        FlowInstructions instructions = new FlowInstructions(applyActions, null, meterId, goToTable.getValue());
        assertEquals(instructions, entry.getInstructions());
    }

    private OFFlowStatsEntry buildFlowStatsEntry() {
        return factory.buildFlowStatsEntry()
                .setTableId(TableId.of(tableId))
                .setCookie(U64.of(cookie))
                .setPacketCount(U64.of(packetCount))
                .setByteCount(U64.of(byteCount))
                .setDurationSec(durationSec)
                .setDurationNsec(durationNsec)
                .setHardTimeout(hardTimeout)
                .setIdleTimeout(idleTimeout)
                .setPriority(priority)
                .setFlags(Collections.singleton(flag))
                .setMatch(buildMatch())
                .setInstructions(buildInstruction())
                .build();
    }

    private static Match buildMatch() {
        return factory.buildMatch()
                .setExact(MatchField.VLAN_VID, vlanVid)
                .setExact(MatchField.ETH_TYPE, ethType)
                .setExact(MatchField.ETH_DST, ethDst)
                .setExact(MatchField.IN_PORT, port)
                .setExact(MatchField.IP_PROTO, ipProto)
                .setExact(MatchField.UDP_DST, udpDst)
                .setExact(MatchField.UDP_SRC, udpSrc)
                .build();
    }

    private static List<OFInstruction> buildInstruction() {
        List<OFAction> actions = new ArrayList<>();

        actions.add(factory.actions().pushVlan(ethType));
        actions.add(factory.actions().output(port, 0));
        actions.add(factory.actions().setField(factory.oxms().ethSrc(MacAddress.of(MAC_ADDRESS_1))));
        actions.add(factory.actions().setField(factory.oxms().ethDst(MacAddress.of(MAC_ADDRESS_2))));
        actions.add(factory.actions().group(group));
        actions.add(factory.actions().buildNoviflowCopyField()
                .setNBits(bits)
                .setSrcOffset(srcOffset)
                .setDstOffset(dstOffset)
                .setOxmSrcHeader(oxmSrcHeader)
                .setOxmDstHeader(oxmDstHeader)
                .build());

        actions.add(factory.actions().buildNoviflowSwapField()
                .setNBits(bits)
                .setSrcOffset(srcOffset)
                .setDstOffset(dstOffset)
                .setOxmSrcHeader(oxmSrcHeader)
                .setOxmDstHeader(oxmDstHeader)
                .build());

        List<OFInstruction> instructions = new ArrayList<>();

        instructions.add(factory.instructions().applyActions(actions));
        instructions.add(factory.instructions().buildMeter().setMeterId(meterId).build());
        instructions.add(factory.instructions().gotoTable(goToTable));

        return instructions;
    }
}
