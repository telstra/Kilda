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

package org.openkilda.wfm.topology.connecteddevices.service;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.info.event.ArpInfoData;
import org.openkilda.messaging.info.event.LldpInfoData;
import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.cookie.ServiceCookieSchema;
import org.openkilda.model.cookie.ServiceCookieSchema.ServiceCookieTag;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.wfm.topology.connecteddevices.service.PacketService.FlowRelatedData;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RunWith(JUnitParamsRunner.class)
public class PacketServiceTest extends Neo4jBasedTest {
    public static final String MAC_ADDRESS_1 = "00:00:00:00:00:01";
    public static final String MAC_ADDRESS_2 = "00:00:00:00:00:02";
    public static final String IP_ADDRESS_1 = "192.168.1.1";
    public static final String IP_ADDRESS_2 = "192.168.1.2";
    public static final String CHASSIS_ID_1 = "00:00:00:00:00:03";
    public static final String CHASSIS_ID_2 = "00:00:00:00:00:04";
    public static final String PORT_ID_1 = "00:00:00:00:00:05";
    public static final String PORT_ID_2 = "00:00:00:00:00:06";
    public static final String PORT_DESCRIPTION_1 = "some_port_1";
    public static final String PORT_DESCRIPTION_2 = "some_port_2";
    public static final String SYSTEM_NAME_1 = "ubuntu_1";
    public static final String SYSTEM_NAME_2 = "ubuntu_2";
    public static final String SYSTEM_DESCRIPTION_1 = "ubuntu 18.04";
    public static final String SYSTEM_DESCRIPTION_2 = "ubuntu 19.05";
    public static final String CAPABILITIES_1 = "cap_1";
    public static final String CAPABILITIES_2 = "cap_2";
    public static final String MANAGEMENT_ADDRESS_1 = "127.0.0.1";
    public static final String MANAGEMENT_ADDRESS_2 = "192.168.1.1";
    public static final String FLOW_ID = "flow1";
    public static final String PATH_ID = "path1";
    public static final SwitchId SWITCH_ID_1 = new SwitchId("01");
    public static final SwitchId SWITCH_ID_2 = new SwitchId("02");
    public static final int PORT_NUMBER_1 = 1;
    public static final int PORT_NUMBER_2 = 2;
    public static final int VLAN_0 = 0;
    public static final int VLAN_1 = 1;
    public static final int VLAN_2 = 2;
    public static final int VLAN_3 = 3;
    public static final int TTL_1 = 120;
    public static final int TTL_2 = 240;

    private static SwitchConnectedDeviceRepository switchConnectedDeviceRepository;
    private static SwitchRepository switchRepository;
    private static FlowRepository flowRepository;
    private static TransitVlanRepository transitVlanRepository;
    private static PacketService packetService;

    @BeforeClass
    public static void setUpOnce() {
        switchConnectedDeviceRepository = persistenceManager.getRepositoryFactory()
                .createSwitchConnectedDeviceRepository();
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        transitVlanRepository = persistenceManager.getRepositoryFactory().createTransitVlanRepository();
        packetService = new PacketService(persistenceManager);
    }

    @Before
    public void setUp() {
        switchRepository.createOrUpdate(Switch.builder().switchId(SWITCH_ID_1).build());
        switchRepository.createOrUpdate(Switch.builder().switchId(SWITCH_ID_2).build());
    }

    @Test
    public void testHandleLldpDataSameTimeOnCreate() {
        LldpInfoData data = createLldpInfoDataData();
        packetService.handleLldpData(data);
        Collection<SwitchConnectedDevice> devices = switchConnectedDeviceRepository.findAll();
        assertEquals(1, devices.size());
        assertEquals(devices.iterator().next().getTimeFirstSeen(), devices.iterator().next().getTimeLastSeen());
    }

    @Test
    public void testHandleArpDataSameTimeOnCreate() {
        packetService.handleArpData(createArpInfoData());
        Collection<SwitchConnectedDevice> devices = switchConnectedDeviceRepository.findAll();
        assertEquals(1, devices.size());
        assertEquals(devices.iterator().next().getTimeFirstSeen(), devices.iterator().next().getTimeLastSeen());
    }

    @Test
    public void testHandleLldpDataDifferentTimeOnUpdate() throws InterruptedException {
        // create
        packetService.handleLldpData(createLldpInfoDataData());

        Thread.sleep(10);
        // update
        packetService.handleLldpData(createLldpInfoDataData());

        Collection<SwitchConnectedDevice> devices = switchConnectedDeviceRepository.findAll();
        assertEquals(1, devices.size());
        assertNotEquals(devices.iterator().next().getTimeFirstSeen(), devices.iterator().next().getTimeLastSeen());
    }

    @Test
    public void testHandleArpDataDifferentTimeOnUpdate() throws InterruptedException {
        // create
        packetService.handleArpData(createArpInfoData());

        Thread.sleep(10);
        // update
        packetService.handleArpData(createArpInfoData());

        Collection<SwitchConnectedDevice> devices = switchConnectedDeviceRepository.findAll();
        assertEquals(1, devices.size());
        assertNotEquals(devices.iterator().next().getTimeFirstSeen(), devices.iterator().next().getTimeLastSeen());
    }

    @Test
    public void testHandleLldpDataNonExistentSwitch() {
        LldpInfoData data = createLldpInfoDataData();
        data.setSwitchId(new SwitchId("12345"));
        packetService.handleLldpData(data);
        assertTrue(switchConnectedDeviceRepository.findAll().isEmpty());
    }

    @Test
    public void testHandleArpDataNonExistentSwitch() {
        ArpInfoData data = createArpInfoData();
        data.setSwitchId(new SwitchId("12345"));
        packetService.handleArpData(data);
        assertTrue(switchConnectedDeviceRepository.findAll().isEmpty());
    }

    @Test
    public void testHandleLldpDataDifferentSwitchId() {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setSwitchId(SWITCH_ID_2);
        runHandleLldpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataDifferentPortNumber() {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setPortNumber(PORT_NUMBER_2);
        runHandleLldpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataDifferentVlan() {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setVlans(Collections.singletonList(VLAN_2));
        runHandleLldpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataDifferentMac() {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setMacAddress(MAC_ADDRESS_2);
        runHandleLldpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataDifferentPortId() {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setPortId(PORT_ID_2);
        runHandleLldpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataDifferentChassisId() {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setChassisId(CHASSIS_ID_2);
        runHandleLldpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataUpdateDifferentPortDescription() throws InterruptedException {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setPortDescription(PORT_DESCRIPTION_2);
        runHandleLldpDataWithUpdatedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataUpdateDifferentManagementAddress() throws InterruptedException {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setManagementAddress(MANAGEMENT_ADDRESS_2);
        runHandleLldpDataWithUpdatedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataUpdateDifferentSystemName() throws InterruptedException {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setSystemName(SYSTEM_NAME_2);
        runHandleLldpDataWithUpdatedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataUpdateDifferentSystemDescription() throws InterruptedException {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setSystemDescription(SYSTEM_DESCRIPTION_2);
        runHandleLldpDataWithUpdatedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataUpdateDifferentSystemCapabilities() throws InterruptedException {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setSystemCapabilities(CAPABILITIES_2);
        runHandleLldpDataWithUpdatedDevice(updatedData);
    }

    @Test
    public void testHandleLldpDataUpdateDifferentTtl() throws InterruptedException {
        LldpInfoData updatedData = createLldpInfoDataData();
        updatedData.setTtl(TTL_2);
        runHandleLldpDataWithUpdatedDevice(updatedData);
    }

    @Test
    public void testHandleArpDataDifferentSwitchId() {
        ArpInfoData updatedData = createArpInfoData();
        updatedData.setSwitchId(SWITCH_ID_2);
        runHandleArpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleArpDataDifferentPortNumber() {
        ArpInfoData updatedData = createArpInfoData();
        updatedData.setPortNumber(PORT_NUMBER_2);
        runHandleArpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleArpDataDifferentVlan() {
        ArpInfoData updatedData = createArpInfoData();
        updatedData.setVlans(Collections.singletonList(VLAN_2));
        runHandleArpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleArpDataDifferentMac() {
        ArpInfoData updatedData = createArpInfoData();
        updatedData.setMacAddress(MAC_ADDRESS_2);
        runHandleArpDataWithAddedDevice(updatedData);
    }

    @Test
    public void testHandleArpDataDifferentIpAddress() {
        ArpInfoData updatedData = createArpInfoData();
        updatedData.setIpAddress(IP_ADDRESS_2);
        runHandleArpDataWithAddedDevice(updatedData);
    }

    private Object[][] getOneSwitchOnePortFlowParameters() {
        return new Object[][] {
                // inVlan, srcVlan, dstVlan, vlansInPacket, sourceSwitch
                {VLAN_0, VLAN_0, VLAN_2, newArrayList(VLAN_2), true},
                {VLAN_1, VLAN_0, VLAN_2, newArrayList(VLAN_2, VLAN_1), true},
                {VLAN_1, VLAN_1, VLAN_2, newArrayList(VLAN_2), true},
                {VLAN_0, VLAN_2, VLAN_0, newArrayList(VLAN_2), false},
                {VLAN_1, VLAN_2, VLAN_0, newArrayList(VLAN_2, VLAN_1), false},
                {VLAN_1, VLAN_2, VLAN_1, newArrayList(VLAN_2), false}
        };
    }

    @Test
    @Parameters(method = "getOneSwitchOnePortFlowParameters")
    public void findFlowRelatedDataForOneSwitchOnePortFlowTest(
            int inVlan, int srcVlan, int dstVlan, List<Integer> vlansInPacket, boolean source) {
        createFlow(FLOW_ID, srcVlan, dstVlan, null, true, true);
        LldpInfoData data = createLldpInfoDataData(SWITCH_ID_1, vlansInPacket, PORT_NUMBER_1);
        FlowRelatedData flowRelatedData = packetService.findFlowRelatedDataForOneSwitchFlow(data);
        assertEquals(FLOW_ID, flowRelatedData.getFlowId());
        assertEquals(inVlan, flowRelatedData.getOriginalVlan());
        assertEquals(source, flowRelatedData.getSource());
    }

    private Object[][] getOneSwitchFlowParameters() {
        return new Object[][] {
                // inVlan, srcVlan, dstVlan, vlansInPacket, sourceSwitch
                {VLAN_0, VLAN_0, VLAN_0, newArrayList(), true},
                {VLAN_1, VLAN_0, VLAN_0, newArrayList(VLAN_1), true},
                {VLAN_0, VLAN_0, VLAN_2, newArrayList(VLAN_2), true},
                {VLAN_1, VLAN_0, VLAN_2, newArrayList(VLAN_2, VLAN_1), true},
                {VLAN_1, VLAN_1, VLAN_2, newArrayList(VLAN_2), true},
                {VLAN_0, VLAN_0, VLAN_0, newArrayList(), false},
                {VLAN_1, VLAN_0, VLAN_0, newArrayList(VLAN_1), false},
                {VLAN_0, VLAN_2, VLAN_0, newArrayList(VLAN_2), false},
                {VLAN_1, VLAN_2, VLAN_0, newArrayList(VLAN_2, VLAN_1), false},
                {VLAN_1, VLAN_2, VLAN_1, newArrayList(VLAN_2), false}
        };
    }

    @Test
    @Parameters(method = "getOneSwitchFlowParameters")
    public void findFlowRelatedDataForOneSwitchFlowTest(
            int inVlan, int srcVlan, int dstVlan, List<Integer> vlansInPacket, boolean source) {
        createFlow(FLOW_ID, srcVlan, dstVlan, null, true, false);
        LldpInfoData data = createLldpInfoDataData(
                SWITCH_ID_1, vlansInPacket, source ? PORT_NUMBER_1 : PORT_NUMBER_2);
        FlowRelatedData flowRelatedData = packetService.findFlowRelatedDataForOneSwitchFlow(data);
        assertEquals(FLOW_ID, flowRelatedData.getFlowId());
        assertEquals(inVlan, flowRelatedData.getOriginalVlan());
        assertEquals(source, flowRelatedData.getSource());
    }

    private Object[][] getVlanFlowParameters() {
        return new Object[][] {
                // inVlan, srcVlan, dstVlan, transitVlan, vlansInPacket, sourceSwitch
                {VLAN_0, VLAN_0, VLAN_3, VLAN_2, newArrayList(VLAN_2), true},
                {VLAN_1, VLAN_0, VLAN_3, VLAN_2, newArrayList(VLAN_2, VLAN_1), true},
                {VLAN_1, VLAN_1, VLAN_3, VLAN_2, newArrayList(VLAN_2), true},
                {VLAN_0, VLAN_3, VLAN_0, VLAN_2, newArrayList(VLAN_2), false},
                {VLAN_1, VLAN_3, VLAN_0, VLAN_2, newArrayList(VLAN_2, VLAN_1), false},
                {VLAN_1, VLAN_3, VLAN_1, VLAN_2, newArrayList(VLAN_2), false}
        };
    }

    @Test
    @Parameters(method = "getVlanFlowParameters")
    public void findFlowRelatedDataForVlanFlowTest(
            int inVlan, int srcVlan, int dstVlan, int transitVlan, List<Integer> vlansInPacket, boolean source) {
        createFlow(FLOW_ID, srcVlan, dstVlan, transitVlan, false, false);
        LldpInfoData data = createLldpInfoDataData(
                source ? SWITCH_ID_1 : SWITCH_ID_2, vlansInPacket, source ? PORT_NUMBER_1 : PORT_NUMBER_2);
        FlowRelatedData flowRelatedData = packetService.findFlowRelatedDataForVlanFlow(data);
        assertEquals(FLOW_ID, flowRelatedData.getFlowId());
        assertEquals(inVlan, flowRelatedData.getOriginalVlan());
        assertEquals(source, flowRelatedData.getSource());
    }

    private Object[][] getInOutVlanCombinationForVxlanParameters() {
        return new Object[][] {
                // inVlan, srcVlan, dstVlan, vlansInPacket, sourceSwitch
                {VLAN_0, VLAN_0, VLAN_0, newArrayList(), true},
                {VLAN_0, VLAN_0, VLAN_2, newArrayList(), true},
                {VLAN_1, VLAN_0, VLAN_2, newArrayList(VLAN_1), true},
                {VLAN_1, VLAN_1, VLAN_2, newArrayList(VLAN_1), true},
                {VLAN_0, VLAN_0, VLAN_0, newArrayList(), false},
                {VLAN_0, VLAN_2, VLAN_0, newArrayList(), false},
                {VLAN_1, VLAN_2, VLAN_0, newArrayList(VLAN_1), false},
                {VLAN_1, VLAN_2, VLAN_1, newArrayList(VLAN_1), false}
        };
    }

    @Test
    @Parameters(method = "getInOutVlanCombinationForVxlanParameters")
    public void findFlowRelatedDataForVxlanFlowTest(
            int inVlan, int srcVlan, int dstVlan, List<Integer> vlansInPacket, boolean source) {
        createFlow(FLOW_ID, srcVlan, dstVlan, null, false, false);
        LldpInfoData data = createLldpInfoDataData(
                source ? SWITCH_ID_1 : SWITCH_ID_2, vlansInPacket, source ? PORT_NUMBER_1 : PORT_NUMBER_2);
        FlowRelatedData flowRelatedData = packetService.findFlowRelatedDataForVxlanFlow(data);
        assertEquals(FLOW_ID, flowRelatedData.getFlowId());
        assertEquals(inVlan, flowRelatedData.getOriginalVlan());
        assertEquals(source, flowRelatedData.getSource());
    }

    private void runHandleLldpDataWithAddedDevice(LldpInfoData updatedData) {
        LldpInfoData data = createLldpInfoDataData();
        packetService.handleLldpData(data);
        assertEquals(1, switchConnectedDeviceRepository.findAll().size());
        assertLldpConnectedDeviceExistInDatabase(data);

        // we must add second device
        packetService.handleLldpData(updatedData);
        assertEquals(2, switchConnectedDeviceRepository.findAll().size());
        assertLldpConnectedDeviceExistInDatabase(data);
        assertLldpConnectedDeviceExistInDatabase(updatedData);
    }

    private void runHandleLldpDataWithUpdatedDevice(LldpInfoData updatedData) throws InterruptedException {
        LldpInfoData data = createLldpInfoDataData();
        packetService.handleLldpData(data);
        Collection<SwitchConnectedDevice> oldDevices = switchConnectedDeviceRepository.findAll();
        assertEquals(1, oldDevices.size());
        assertLldpInfoDataDataEqualsSwitchConnectedDevice(data, oldDevices.iterator().next());

        // Need to have a different timestamp in 'data' and 'updatedData' messages.
        // More info https://github.com/telstra/open-kilda/issues/3064
        updatedData.setTimestamp(data.getTimestamp() + 1000);

        // we must update old device
        packetService.handleLldpData(updatedData);
        Collection<SwitchConnectedDevice> newDevices = switchConnectedDeviceRepository.findAll();
        assertEquals(1, newDevices.size());
        assertLldpInfoDataDataEqualsSwitchConnectedDevice(updatedData, newDevices.iterator().next());

        // time must be updated
        assertNotEquals(oldDevices.iterator().next().getTimeLastSeen(), newDevices.iterator().next().getTimeLastSeen());
    }

    private void runHandleArpDataWithAddedDevice(ArpInfoData updatedData) {
        ArpInfoData data = createArpInfoData();
        packetService.handleArpData(data);
        assertEquals(1, switchConnectedDeviceRepository.findAll().size());
        assertArpConnectedDeviceExistInDatabase(data);

        // we must add second device
        packetService.handleArpData(updatedData);
        assertEquals(2, switchConnectedDeviceRepository.findAll().size());
        assertArpConnectedDeviceExistInDatabase(data);
        assertArpConnectedDeviceExistInDatabase(updatedData);
    }

    private void assertLldpConnectedDeviceExistInDatabase(LldpInfoData data) {
        Optional<SwitchConnectedDevice> switchConnectedDevice = switchConnectedDeviceRepository
                .findLldpByUniqueFieldCombination(data.getSwitchId(), data.getPortNumber(), data.getVlans().get(0),
                        data.getMacAddress(), data.getChassisId(), data.getPortId());
        assertTrue(switchConnectedDevice.isPresent());
        assertLldpInfoDataDataEqualsSwitchConnectedDevice(data, switchConnectedDevice.get());
    }

    private void assertArpConnectedDeviceExistInDatabase(ArpInfoData data) {
        Optional<SwitchConnectedDevice> switchConnectedDevice = switchConnectedDeviceRepository
                .findArpByUniqueFieldCombination(data.getSwitchId(), data.getPortNumber(), data.getVlans().get(0),
                        data.getMacAddress(), data.getIpAddress());
        assertTrue(switchConnectedDevice.isPresent());
        assertArpInfoDataEqualsSwitchConnectedDevice(data, switchConnectedDevice.get());
    }

    private void assertLldpInfoDataDataEqualsSwitchConnectedDevice(
            LldpInfoData data, SwitchConnectedDevice device) {
        assertEquals(data.getSwitchId(), device.getSwitchObj().getSwitchId());
        assertEquals(data.getPortNumber(), device.getPortNumber());
        assertEquals(data.getVlans().get(0).intValue(), device.getVlan());
        assertEquals(data.getMacAddress(), device.getMacAddress());
        assertEquals(data.getChassisId(), device.getChassisId());
        assertEquals(data.getPortId(), device.getPortId());
        assertEquals(data.getPortDescription(), device.getPortDescription());
        assertEquals(data.getManagementAddress(), device.getManagementAddress());
        assertEquals(data.getSystemCapabilities(), device.getSystemCapabilities());
        assertEquals(data.getSystemName(), device.getSystemName());
        assertEquals(data.getSystemDescription(), device.getSystemDescription());
        assertEquals(data.getTtl(), device.getTtl());
    }

    private void assertArpInfoDataEqualsSwitchConnectedDevice(ArpInfoData data, SwitchConnectedDevice device) {
        assertEquals(data.getSwitchId(), device.getSwitchObj().getSwitchId());
        assertEquals(data.getPortNumber(), device.getPortNumber());
        assertEquals(data.getVlans().get(0).intValue(), device.getVlan());
        assertEquals(data.getMacAddress(), device.getMacAddress());
        assertEquals(data.getIpAddress(), device.getIpAddress());
    }

    private void createFlow(
            String flowId, int srcVlan, int dstVlan, Integer transitVlan, boolean oneSwitchFlow, boolean onePort) {
        if (transitVlan != null) {
            transitVlanRepository.createOrUpdate(new TransitVlan(flowId, new PathId(PATH_ID), transitVlan));
        }
        Switch srcSwitch = switchRepository.findById(SWITCH_ID_1).get();
        Switch dstSwitch = oneSwitchFlow ? srcSwitch : switchRepository.findById(SWITCH_ID_2).get();
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcVlan(srcVlan)
                .srcPort(PORT_NUMBER_1)
                .destSwitch(dstSwitch)
                .destVlan(dstVlan)
                .destPort(onePort ? PORT_NUMBER_1 : PORT_NUMBER_2)
                .build();
        flowRepository.createOrUpdate(flow);
    }

    private LldpInfoData createLldpInfoDataData() {
        return createLldpInfoDataData(SWITCH_ID_1, newArrayList(VLAN_1), PORT_NUMBER_1);
    }

    private LldpInfoData createLldpInfoDataData(SwitchId switchId, List<Integer> vlans, int portNumber) {
        return new LldpInfoData(
                switchId, portNumber, vlans,
                ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.LLDP_INPUT_PRE_DROP_COOKIE).getValue(),
                MAC_ADDRESS_1, CHASSIS_ID_1, PORT_ID_1, TTL_1, PORT_DESCRIPTION_1, SYSTEM_NAME_1, SYSTEM_DESCRIPTION_1,
                CAPABILITIES_1, MANAGEMENT_ADDRESS_1);
    }

    private ArpInfoData createArpInfoData() {
        return new ArpInfoData(
                SWITCH_ID_1, PORT_NUMBER_1, newArrayList(VLAN_1),
                ServiceCookieSchema.INSTANCE.make(ServiceCookieTag.ARP_INPUT_PRE_DROP_COOKIE).getValue(),
                MAC_ADDRESS_1, IP_ADDRESS_1);
    }
}
