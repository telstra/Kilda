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

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import org.openkilda.messaging.model.SwitchPropertiesDto;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IllegalSwitchPropertiesException;
import org.openkilda.wfm.error.SwitchNotFoundException;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class SwitchOperationsServiceTest extends InMemoryGraphBasedTest {
    public static final String TEST_FLOW_ID_1 = "test_flow_1";
    private static SwitchRepository switchRepository;
    private static SwitchPropertiesRepository switchPropertiesRepository;
    private static PortPropertiesRepository portPropertiesRepository;
    private static FlowRepository flowRepository;
    private static SwitchOperationsService switchOperationsService;

    private static final SwitchId TEST_SWITCH_ID = new SwitchId(1);
    private static final SwitchId TEST_SWITCH_ID_2 = new SwitchId(2);

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
        };
        switchOperationsService = new SwitchOperationsService(persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager(), carrier);
    }

    @Test
    public void shouldUpdateLinkUnderMaintenanceFlag() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.add(sw);

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
        switchRepository.add(sw);
        PortProperties portProperties = PortProperties.builder().switchObj(sw).port(7).discoveryEnabled(false).build();
        portPropertiesRepository.add(portProperties);

        switchOperationsService.deleteSwitch(TEST_SWITCH_ID, false);
        assertFalse(switchRepository.findById(TEST_SWITCH_ID).isPresent());
        assertTrue(portPropertiesRepository.findAll().isEmpty());
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateSupportedEncapsulationTypeWhenUpdatingSwitchProperties() {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.add(sw);
        createSwitchProperties(sw, Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN), false, false, false);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, new SwitchPropertiesDto());
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateMultiTableFlagWhenUpdatingSwitchProperties() {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.add(sw);
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
        switchRepository.add(firstSwitch);
        switchRepository.add(secondSwitch);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_1)
                .srcSwitch(firstSwitch)
                .destSwitch(secondSwitch)
                .detectConnectedDevices(new DetectConnectedDevices(
                        true, false, true, false, false, false, false, false))
                .build();
        flowRepository.add(flow);

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
        switchRepository.add(sw);
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
        switchRepository.add(firstSwitch);
        switchRepository.add(secondSwitch);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_1)
                .srcSwitch(firstSwitch)
                .destSwitch(secondSwitch)
                .detectConnectedDevices(
                        new DetectConnectedDevices(false, true, false, true, false, false, false, false))
                .build();
        flowRepository.add(flow);

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

    private void createSwitchProperties(Switch sw, Set<FlowEncapsulationType> transitEncapsulation, boolean multiTable,
                                        boolean switchLldp, boolean switchArp) {
        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(sw)
                .supportedTransitEncapsulation(transitEncapsulation)
                .multiTable(multiTable)
                .switchLldp(switchLldp)
                .switchArp(switchArp)
                .build();
        switchPropertiesRepository.add(switchProperties);
    }
}
