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

package org.openkilda.floodlight.switchmanager;

import static java.util.Collections.singletonList;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_13;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.util.FlowModUtils;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMeterFlags;
import org.projectfloodlight.openflow.protocol.OFMeterMod;
import org.projectfloodlight.openflow.protocol.OFMeterModCommand;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.meterband.OFMeterBandDrop;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Utils for switch flow generation.
 */
public final class SwitchFlowUtils {

    /**
     * OVS software switch manufacturer constant value.
     */
    public static final String OVS_MANUFACTURER = "Nicira, Inc.";
    private static final int MAX_LEN = 0xFFFFFFFF;

    private SwitchFlowUtils() {
    }

    /**
     * Create a MAC address based on the DPID.
     *
     * @param dpId switch object
     * @return {@link MacAddress}
     */
    public static MacAddress convertDpIdToMac(DatapathId dpId) {
        return MacAddress.of(Arrays.copyOfRange(dpId.getBytes(), 2, 8));
    }

    /**
     * Create sent to controller OpenFlow action.
     *
     * @param ofFactory OpenFlow factory
     * @return OpenFlow Action
     */
    public static OFAction actionSendToController(OFFactory ofFactory) {
        OFActions actions = ofFactory.actions();
        return actions.buildOutput().setMaxLen(MAX_LEN).setPort(OFPort.CONTROLLER)
                .build();
    }

    /**
     * Create an OFAction which sets the output port.
     *
     * @param ofFactory OF factory for the switch
     * @param outputPort port to set in the action
     * @return {@link OFAction}
     */
    public static OFAction actionSetOutputPort(final OFFactory ofFactory, final OFPort outputPort) {
        OFActions actions = ofFactory.actions();
        return actions.buildOutput().setMaxLen(MAX_LEN).setPort(outputPort).build();
    }

    /**
     * Create go to table OFInstruction.
     *
     * @param ofFactory OF factory for the switch
     * @param tableId tableId to go
     * @return {@link OFAction}
     */
    public static OFInstruction instructionGoToTable(final OFFactory ofFactory, final TableId tableId) {
        return ofFactory.instructions().gotoTable(tableId);
    }

    /**
     * Create an OFInstructionApplyActions which applies actions.
     *
     * @param ofFactory OF factory for the switch
     * @param actionList OFAction list to apply
     * @return {@link OFInstructionApplyActions}
     */
    public static OFInstructionApplyActions buildInstructionApplyActions(OFFactory ofFactory,
                                                                          List<OFAction> actionList) {
        return ofFactory.instructions().applyActions(actionList).createBuilder().build();
    }

    /**
     * Create set destination MAC address OpenFlow action.
     *
     * @param ofFactory OpenFlow factory
     * @param macAddress MAC address to set
     * @return OpenFlow Action
     */
    public static OFAction actionSetDstMac(OFFactory ofFactory, final MacAddress macAddress) {
        OFOxms oxms = ofFactory.oxms();
        OFActions actions = ofFactory.actions();
        return actions.buildSetField()
                .setField(oxms.buildEthDst().setValue(macAddress).build()).build();
    }

    /**
     * Create set source MAC address OpenFlow action.
     *
     * @param ofFactory OpenFlow factory
     * @param macAddress MAC address to set
     * @return OpenFlow Action
     */
    public static OFAction actionSetSrcMac(OFFactory ofFactory, final MacAddress macAddress) {
        return ofFactory.actions().buildSetField()
                .setField(ofFactory.oxms().ethSrc(macAddress)).build();
    }

    /**
     * Create set UDP source port OpenFlow action.
     *
     * @param ofFactory OpenFlow factory
     * @param srcPort UDP src port to set
     * @return OpenFlow Action
     */
    public static OFAction actionSetUdpSrcAction(OFFactory ofFactory, TransportPort srcPort) {
        OFOxms oxms = ofFactory.oxms();
        return ofFactory.actions().setField(oxms.udpSrc(srcPort));
    }

    /**
     * Create set UDP destination port OpenFlow action.
     *
     * @param ofFactory OpenFlow factory
     * @param dstPort UDP dst port to set
     * @return OpenFlow Action
     */
    public static OFAction actionSetUdpDstAction(OFFactory ofFactory, TransportPort dstPort) {
        OFOxms oxms = ofFactory.oxms();
        return ofFactory.actions().setField(oxms.udpDst(dstPort));
    }

    /**
     * Create OpenFlow flow modification command builder.
     *
     * @param ofFactory OpenFlow factory
     * @param cookie cookie
     * @param priority priority
     * @param tableId table id
     * @return OpenFlow command builder
     */
    public static OFFlowMod.Builder prepareFlowModBuilder(OFFactory ofFactory, long cookie, int priority,
                                                          int tableId) {
        OFFlowMod.Builder fmb = ofFactory.buildFlowAdd();
        fmb.setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT);
        fmb.setHardTimeout(FlowModUtils.INFINITE_TIMEOUT);
        fmb.setBufferId(OFBufferId.NO_BUFFER);
        fmb.setCookie(U64.of(cookie));
        fmb.setPriority(priority);
        fmb.setTableId(TableId.of(tableId));

        return fmb;
    }

    /**
     * Create OpenFlow meter modification command.
     *
     * @param ofFactory OpenFlow factory
     * @param bandwidth bandwidth
     * @param burstSize burst size
     * @param meterId meter id
     * @param flags flags
     * @return OpenFlow command
     */
    public static OFMeterMod buildMeterMod(OFFactory ofFactory, long bandwidth, long burstSize,
                                           long meterId, Set<OFMeterFlags> flags) {
        OFMeterBandDrop.Builder bandBuilder = ofFactory.meterBands()
                .buildDrop()
                .setRate(bandwidth)
                .setBurstSize(burstSize);

        OFMeterMod.Builder meterModBuilder = ofFactory.buildMeterMod()
                .setMeterId(meterId)
                .setCommand(OFMeterModCommand.ADD)
                .setFlags(flags);

        if (ofFactory.getVersion().compareTo(OF_13) > 0) {
            meterModBuilder.setBands(singletonList(bandBuilder.build()));
        } else {
            meterModBuilder.setMeters(singletonList(bandBuilder.build()));
        }

        return meterModBuilder.build();
    }

    /**
     * Check switch is OVS.
     *
     * @param sw switch
     * @return true if switch is OVS
     */
    public static boolean isOvs(IOFSwitch sw) {
        return OVS_MANUFACTURER.equals(sw.getSwitchDescription().getManufacturerDescription());
    }
}
