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

package org.openkilda.wfm.topology.nbworker.services;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import org.openkilda.messaging.model.SwitchLocation;
import org.openkilda.messaging.model.SwitchPatch;
import org.openkilda.messaging.model.SwitchPropertiesDto;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.MacAddress;
import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IllegalSwitchPropertiesException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.model.Endpoint;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class SwitchOperationsServiceTest extends Neo4jBasedTest {
    public static final String TEST_FLOW_ID_1 = "test_flow_1";
    private static SwitchRepository switchRepository;
    private static SwitchPropertiesRepository switchPropertiesRepository;
    private static PortPropertiesRepository portPropertiesRepository;
    private static FlowRepository flowRepository;
    private static SwitchOperationsService switchOperationsService;

    private static final SwitchId TEST_SWITCH_ID = new SwitchId(1);
    private static final SwitchId TEST_SWITCH_ID_2 = new SwitchId(2);
    private static final Integer SERVER_42_PORT_1 = 1;
    private static final Integer SERVER_42_PORT_2 = 2;
    private static final Integer SERVER_42_VLAN_1 = 3;
    private static final Integer SERVER_42_VLAN_2 = 4;
    private static final MacAddress SERVER_42_MAC_ADDRESS_1 = new MacAddress("42:42:42:42:42:42");
    private static final MacAddress SERVER_42_MAC_ADDRESS_2 = new MacAddress("45:45:45:45:45:45");

    @BeforeClass
    public static void setUpOnce() {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        portPropertiesRepository = repositoryFactory.createPortPropertiesRepository();
        flowRepository = repositoryFactory.createFlowRepository();

        SwitchOperationsServiceCarrier carrier = new SwitchOperationsServiceCarrier() {
            @Override
            public void requestSwitchSync(SwitchId switchId) {
            }

            @Override
            public void enableServer42FlowRttOnSwitch(SwitchId switchId) {
            }

            @Override
            public void disableServer42FlowRttOnSwitch(SwitchId switchId) {
            }
        };
        ILinkOperationsServiceCarrier linkCarrier = new ILinkOperationsServiceCarrier() {
            @Override
            public void islBfdPropertiesChanged(Endpoint source, Endpoint destination) {
            }
        };
        switchOperationsService = new SwitchOperationsService(persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager(), carrier, linkCarrier);
    }

    @Test
    public void shouldUpdateLinkUnderMaintenanceFlag() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);

        switchOperationsService.updateSwitchUnderMaintenanceFlag(TEST_SWITCH_ID, true);
        sw = switchRepository.findById(TEST_SWITCH_ID).get();
        assertTrue(sw.isUnderMaintenance());

        switchOperationsService.updateSwitchUnderMaintenanceFlag(TEST_SWITCH_ID, false);
        sw = switchRepository.findById(TEST_SWITCH_ID).get();
        assertFalse(sw.isUnderMaintenance());
    }

    @Test
    public void shouldDeletePortPropertiesWhenDeletingSwitch() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);
        PortProperties portProperties = PortProperties.builder().switchObj(sw).port(7).discoveryEnabled(false).build();
        portPropertiesRepository.createOrUpdate(portProperties);

        switchOperationsService.deleteSwitch(TEST_SWITCH_ID, false);
        assertFalse(switchRepository.findById(TEST_SWITCH_ID).isPresent());
        assertTrue(portPropertiesRepository.findAll().isEmpty());
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateSupportedEncapsulationTypeWhenUpdatingSwitchProperties() {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);
        createSwitchProperties(sw, Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN), false, false, false);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, new SwitchPropertiesDto());
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateMultiTableFlagWhenUpdatingSwitchProperties() {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);
        createSwitchProperties(sw, Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN), true, true, false);

        // user can't disable multiTable without disabling LLDP
        SwitchPropertiesDto update = new SwitchPropertiesDto();
        update.setSupportedTransitEncapsulation(
                Collections.singleton(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN));
        update.setMultiTable(false);
        update.setSwitchLldp(true);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, update);
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateFlowWithLldpFlagWhenUpdatingSwitchProperties() {
        Switch firstSwitch = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        Switch secondSwitch = Switch.builder().switchId(TEST_SWITCH_ID_2).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(firstSwitch);
        switchRepository.createOrUpdate(secondSwitch);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_1)
                .srcSwitch(firstSwitch)
                .destSwitch(secondSwitch)
                .detectConnectedDevices(new DetectConnectedDevices(
                        true, false, true, false, false, false, false, false))
                .build();
        flowRepository.createOrUpdate(flow);

        createSwitchProperties(
                firstSwitch, Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN), true, false, false);

        // user can't disable multiTable if some flows has enabled detect connected devices via LLDP
        SwitchPropertiesDto update = new SwitchPropertiesDto();
        update.setSupportedTransitEncapsulation(
                Collections.singleton(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN));
        update.setMultiTable(false);
        update.setSwitchLldp(false);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, update);
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateMultiTableFlagWhenUpdatingSwitchPropertiesWithArp() {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);
        createSwitchProperties(sw, Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN), true, false, true);

        // user can't disable multiTable without disabling ARP
        SwitchPropertiesDto update = new SwitchPropertiesDto();
        update.setSupportedTransitEncapsulation(
                Collections.singleton(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN));
        update.setMultiTable(false);
        update.setSwitchArp(true);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, update);
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateFlowWithArpFlagWhenUpdatingSwitchProperties() {
        Switch firstSwitch = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        Switch secondSwitch = Switch.builder().switchId(TEST_SWITCH_ID_2).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(firstSwitch);
        switchRepository.createOrUpdate(secondSwitch);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_1)
                .srcSwitch(firstSwitch)
                .destSwitch(secondSwitch)
                .detectConnectedDevices(
                        new DetectConnectedDevices(false, true, false, true, false, false, false, false))
                .build();
        flowRepository.createOrUpdate(flow);

        createSwitchProperties(firstSwitch,
                Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN), true, false, false);

        // user can't disable multiTable if some flows has enabled detect connected devices via ARP
        SwitchPropertiesDto update = new SwitchPropertiesDto();
        update.setSupportedTransitEncapsulation(
                Collections.singleton(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN));
        update.setMultiTable(false);
        update.setSwitchArp(false);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, update);
    }

    @Test
    public void shouldUpdateServer42SwitchProperties() {
        Switch sw = Switch.builder()
                .switchId(TEST_SWITCH_ID)
                .status(SwitchStatus.ACTIVE)
                .features(Collections.singleton(SwitchFeature.MULTI_TABLE))
                .build();
        switchRepository.createOrUpdate(sw);
        createServer42SwitchProperties(sw, false, SERVER_42_PORT_1, SERVER_42_VLAN_1, SERVER_42_MAC_ADDRESS_1);

        SwitchPropertiesDto update = new SwitchPropertiesDto();
        update.setSupportedTransitEncapsulation(
                Collections.singleton(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN));
        update.setMultiTable(true);
        update.setServer42FlowRtt(true);
        update.setServer42Port(SERVER_42_PORT_2);
        update.setServer42Vlan(SERVER_42_VLAN_2);
        update.setServer42MacAddress(SERVER_42_MAC_ADDRESS_2);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, update);
        Optional<SwitchProperties> updated = switchPropertiesRepository.findBySwitchId(TEST_SWITCH_ID);

        assertTrue(updated.isPresent());
        assertTrue(updated.get().isServer42FlowRtt());
        assertEquals(SERVER_42_PORT_2, updated.get().getServer42Port());
        assertEquals(SERVER_42_VLAN_2, updated.get().getServer42Vlan());
        assertEquals(SERVER_42_MAC_ADDRESS_2, updated.get().getServer42MacAddress());
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateServer42VlanSwitchProperties() {
        // user can't enable server42FlowRtt and do not specify server42Vlan
        SwitchPropertiesDto properties = new SwitchPropertiesDto();
        properties.setServer42FlowRtt(true);
        properties.setServer42Port(SERVER_42_PORT_2);
        properties.setServer42Vlan(null);
        properties.setServer42MacAddress(SERVER_42_MAC_ADDRESS_2);
        runInvalidServer42PropsTest(properties);
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateServer42PortSwitchProperties() {
        // user can't enable server42FlowRtt and do not specify server42Port
        SwitchPropertiesDto properties = new SwitchPropertiesDto();
        properties.setServer42FlowRtt(true);
        properties.setServer42Port(null);
        properties.setServer42Vlan(SERVER_42_VLAN_2);
        properties.setServer42MacAddress(SERVER_42_MAC_ADDRESS_2);
        runInvalidServer42PropsTest(properties);
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateServer42MacAddressSwitchProperties() {
        // user can't enable server42FlowRtt and do not specify server42MacAddress
        SwitchPropertiesDto properties = new SwitchPropertiesDto();
        properties.setServer42FlowRtt(true);
        properties.setServer42Port(SERVER_42_PORT_2);
        properties.setServer42Vlan(SERVER_42_VLAN_2);
        properties.setServer42MacAddress(null);
        runInvalidServer42PropsTest(properties);
    }

    @Test
    public void shouldPatchSwitch() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);

        SwitchPatch switchPatch =
                new SwitchPatch("pop", new SwitchLocation(48.860611, 2.337633, "street", "city", "country"));
        switchOperationsService.patchSwitch(TEST_SWITCH_ID, switchPatch);

        Switch updatedSwitch = switchRepository.findById(TEST_SWITCH_ID).get();
        assertEquals(switchPatch.getPop(), updatedSwitch.getPop());
        assertEquals(switchPatch.getLocation().getLatitude(), updatedSwitch.getLatitude());
        assertEquals(switchPatch.getLocation().getLongitude(), updatedSwitch.getLongitude());
        assertEquals(switchPatch.getLocation().getStreet(), updatedSwitch.getStreet());
        assertEquals(switchPatch.getLocation().getCity(), updatedSwitch.getCity());
        assertEquals(switchPatch.getLocation().getCountry(), updatedSwitch.getCountry());
    }

    @Test
    public void shouldSetNullPopWhenPopIsEmptyString() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);

        SwitchPatch switchPatch = new SwitchPatch("", null);
        switchOperationsService.patchSwitch(TEST_SWITCH_ID, switchPatch);

        Switch updatedSwitch = switchRepository.findById(TEST_SWITCH_ID).get();
        assertNull(updatedSwitch.getPop());
    }

    private void runInvalidServer42PropsTest(SwitchPropertiesDto invalidProperties) {
        invalidProperties.setMultiTable(true);
        invalidProperties.setSupportedTransitEncapsulation(Collections.singleton(
                org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN));

        Switch sw = Switch.builder()
                .switchId(TEST_SWITCH_ID)
                .status(SwitchStatus.ACTIVE)
                .features(Collections.singleton(SwitchFeature.MULTI_TABLE))
                .build();
        switchRepository.createOrUpdate(sw);
        createServer42SwitchProperties(sw, false, SERVER_42_PORT_1, SERVER_42_VLAN_1, SERVER_42_MAC_ADDRESS_1);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, invalidProperties);
    }

    private void createServer42SwitchProperties(
            Switch sw, boolean sever42FlowRtt, Integer port, Integer vlan, MacAddress macAddress) {
        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(sw)
                .supportedTransitEncapsulation(Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN))
                .multiTable(true)
                .server42FlowRtt(sever42FlowRtt)
                .server42Port(port)
                .server42Vlan(vlan)
                .server42MacAddress(macAddress)
                .build();
        switchPropertiesRepository.createOrUpdate(switchProperties);
    }

    private void createSwitchProperties(Switch sw, Set<FlowEncapsulationType> transitEncapsulation, boolean multiTable,
                                        boolean switchLldp, boolean switchArp) {
        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(sw)
                .supportedTransitEncapsulation(transitEncapsulation)
                .multiTable(multiTable)
                .switchLldp(switchLldp)
                .switchArp(switchArp)
                .build();
        switchPropertiesRepository.createOrUpdate(switchProperties);
    }
}
