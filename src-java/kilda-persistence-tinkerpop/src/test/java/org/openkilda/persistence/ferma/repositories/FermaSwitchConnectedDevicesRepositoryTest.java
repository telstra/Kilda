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

package org.openkilda.persistence.ferma.repositories;

import static com.google.common.collect.Sets.newHashSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.openkilda.model.ConnectedDeviceType.ARP;
import static org.openkilda.model.ConnectedDeviceType.LLDP;

import org.openkilda.model.Switch;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public class FermaSwitchConnectedDevicesRepositoryTest extends InMemoryGraphBasedTest {
    static final SwitchId FIRST_SWITCH_ID = new SwitchId("01");
    static final SwitchId SECOND_SWITCH_ID = new SwitchId("02");
    static final int FIRST_PORT_NUMBER = 1;
    static final int SECOND_PORT_NUMBER = 2;
    static final int FIRST_VLAN = 1;
    static final int SECOND_VLAN = 2;
    static final String FIRST_FLOW_ID = "first_flow";
    static final String SECOND_FLOW_ID = "second_flow";
    static final String MAC_ADDRESS_1 = "00:00:00:00:00:00:00:01";
    static final String MAC_ADDRESS_2 = "00:00:00:00:00:00:00:02";
    static final String IP_ADDRESS_1 = "192.168.1.1";
    static final String IP_ADDRESS_2 = "192.168.2.2";
    static final String CHASSIS_ID = "00:00:00:00:00:00:00:03";
    static final String PORT_ID = "123";
    static final int TTL = 120;
    static final String PORT = "some_port";
    static final String SYSTEM_NAME = "ubuntu";
    static final String SYSTEM_DESCRIPTION = "desc";
    static final String CAPABILITIES = "capabilities";
    static final String MANAGEMENT_ADDRESS = "127.0.0.1";
    static final Instant TIME_FIRST_SEEN = Instant.now().minusSeconds(10);
    static final Instant TIME_LAST_SEEN = Instant.now();

    Switch firstSwitch;
    Switch secondSwitch;
    SwitchConnectedDevice lldpConnectedDeviceA;
    SwitchConnectedDevice lldpConnectedDeviceB;
    SwitchConnectedDevice arpConnectedDeviceC;
    SwitchConnectedDevice arpConnectedDeviceD;

    SwitchRepository switchRepository;
    SwitchConnectedDeviceRepository connectedDeviceRepository;

    @Before
    public void setUp() {
        switchRepository = repositoryFactory.createSwitchRepository();
        connectedDeviceRepository = repositoryFactory.createSwitchConnectedDeviceRepository();

        firstSwitch = createTestSwitch(FIRST_SWITCH_ID.getId());
        secondSwitch = createTestSwitch(SECOND_SWITCH_ID.getId());
        lldpConnectedDeviceA = new SwitchConnectedDevice(
                firstSwitch, FIRST_PORT_NUMBER, FIRST_VLAN, FIRST_FLOW_ID, true, MAC_ADDRESS_1, LLDP, null, CHASSIS_ID,
                PORT_ID, TTL, PORT, SYSTEM_NAME, SYSTEM_DESCRIPTION, CAPABILITIES, MANAGEMENT_ADDRESS, TIME_FIRST_SEEN,
                TIME_LAST_SEEN);
        lldpConnectedDeviceB = new SwitchConnectedDevice(
                secondSwitch, FIRST_PORT_NUMBER, FIRST_VLAN, SECOND_FLOW_ID, false, MAC_ADDRESS_1, LLDP, null,
                CHASSIS_ID, PORT_ID, TTL, PORT, SYSTEM_NAME, SYSTEM_DESCRIPTION, CAPABILITIES, MANAGEMENT_ADDRESS,
                TIME_FIRST_SEEN, TIME_LAST_SEEN);
        arpConnectedDeviceC = new SwitchConnectedDevice(
                secondSwitch, SECOND_PORT_NUMBER, SECOND_VLAN, null, null, MAC_ADDRESS_2, ARP, IP_ADDRESS_1, null, null,
                TTL, PORT, SYSTEM_NAME, SYSTEM_DESCRIPTION, CAPABILITIES, MANAGEMENT_ADDRESS, TIME_FIRST_SEEN,
                TIME_LAST_SEEN);
        arpConnectedDeviceD = new SwitchConnectedDevice(
                secondSwitch, SECOND_PORT_NUMBER, SECOND_VLAN, SECOND_FLOW_ID, null, MAC_ADDRESS_2, ARP, IP_ADDRESS_2,
                null, null, TTL, PORT, SYSTEM_NAME, SYSTEM_DESCRIPTION, CAPABILITIES, MANAGEMENT_ADDRESS,
                TIME_FIRST_SEEN, TIME_LAST_SEEN);
    }

    @Test
    public void createConnectedDeviceTest() {
        connectedDeviceRepository.add(lldpConnectedDeviceA);
        Collection<SwitchConnectedDevice> devices = connectedDeviceRepository.findAll();
        assertEquals(lldpConnectedDeviceA, devices.iterator().next());
        assertNotNull(devices.iterator().next().getSwitchObj());
    }

    @Test
    public void deleteConnectedDeviceTest() {
        connectedDeviceRepository.add(lldpConnectedDeviceA);
        connectedDeviceRepository.add(lldpConnectedDeviceB);
        assertEquals(2, connectedDeviceRepository.findAll().size());

        connectedDeviceRepository.remove(lldpConnectedDeviceA);
        assertEquals(1, connectedDeviceRepository.findAll().size());
        assertEquals(lldpConnectedDeviceB, connectedDeviceRepository.findAll().iterator().next());

        connectedDeviceRepository.remove(lldpConnectedDeviceB);
        assertEquals(0, connectedDeviceRepository.findAll().size());
    }

    @Test
    public void findBySwitchIdTest() {
        connectedDeviceRepository.add(lldpConnectedDeviceA);
        connectedDeviceRepository.add(lldpConnectedDeviceB);
        connectedDeviceRepository.add(arpConnectedDeviceC);
        connectedDeviceRepository.add(arpConnectedDeviceD);

        Collection<SwitchConnectedDevice> firstSwitchDevices = connectedDeviceRepository
                .findBySwitchId(FIRST_SWITCH_ID);
        assertEquals(1, firstSwitchDevices.size());
        assertEquals(lldpConnectedDeviceA, firstSwitchDevices.iterator().next());

        Collection<SwitchConnectedDevice> secondFlowDevices = connectedDeviceRepository
                .findBySwitchId(SECOND_SWITCH_ID);
        assertEquals(3, secondFlowDevices.size());
        assertEquals(newHashSet(lldpConnectedDeviceB, arpConnectedDeviceC, arpConnectedDeviceD),
                newHashSet(secondFlowDevices));
    }

    @Test
    public void findByFlowIdTest() {
        connectedDeviceRepository.add(lldpConnectedDeviceA);
        connectedDeviceRepository.add(lldpConnectedDeviceB);
        connectedDeviceRepository.add(arpConnectedDeviceC);
        connectedDeviceRepository.add(arpConnectedDeviceD);

        Collection<SwitchConnectedDevice> firstDevice = connectedDeviceRepository.findByFlowId(FIRST_FLOW_ID);
        assertEquals(1, firstDevice.size());
        assertEquals(lldpConnectedDeviceA, firstDevice.iterator().next());

        Collection<SwitchConnectedDevice> secondDevices = connectedDeviceRepository.findByFlowId(SECOND_FLOW_ID);
        assertEquals(2, secondDevices.size());
        assertEquals(newHashSet(lldpConnectedDeviceB, arpConnectedDeviceD), newHashSet(secondDevices));
    }

    @Test
    public void findByLldpUniqueFields() {
        connectedDeviceRepository.add(lldpConnectedDeviceA);
        connectedDeviceRepository.add(lldpConnectedDeviceB);
        connectedDeviceRepository.add(arpConnectedDeviceC);

        runFindByLldpUniqueFields(lldpConnectedDeviceA);
        runFindByLldpUniqueFields(lldpConnectedDeviceB);
        runFindByLldpUniqueFields(arpConnectedDeviceC);

        assertFalse(connectedDeviceRepository.findLldpByUniqueFieldCombination(
                firstSwitch.getSwitchId(), 999, 999, "fake", CHASSIS_ID, PORT_ID).isPresent());
    }

    private void runFindByLldpUniqueFields(SwitchConnectedDevice device) {
        Optional<SwitchConnectedDevice> foundDevice = connectedDeviceRepository.findLldpByUniqueFieldCombination(
                device.getSwitchObj().getSwitchId(), device.getPortNumber(), device.getVlan(), device.getMacAddress(),
                device.getChassisId(), device.getPortId());

        if (LLDP.equals(device.getType())) {
            assertTrue(foundDevice.isPresent());
            assertEquals(device, foundDevice.get());
        } else {
            assertFalse(foundDevice.isPresent());
        }
    }

    @Test
    public void findByArpUniqueFields() {
        connectedDeviceRepository.add(lldpConnectedDeviceA);
        connectedDeviceRepository.add(arpConnectedDeviceC);
        connectedDeviceRepository.add(arpConnectedDeviceD);

        runFindByArpUniqueFields(lldpConnectedDeviceA);
        runFindByArpUniqueFields(arpConnectedDeviceC);
        runFindByArpUniqueFields(arpConnectedDeviceD);

        assertFalse(connectedDeviceRepository.findLldpByUniqueFieldCombination(
                firstSwitch.getSwitchId(), 999, 999, "fake", CHASSIS_ID, PORT_ID).isPresent());
    }

    private void runFindByArpUniqueFields(SwitchConnectedDevice device) {
        Optional<SwitchConnectedDevice> foundDevice = connectedDeviceRepository.findArpByUniqueFieldCombination(
                device.getSwitchObj().getSwitchId(), device.getPortNumber(), device.getVlan(), device.getMacAddress(),
                device.getIpAddress());

        if (ARP.equals(device.getType())) {
            assertTrue(foundDevice.isPresent());
            assertEquals(device, foundDevice.get());
        } else {
            assertFalse(foundDevice.isPresent());
        }
    }
}
