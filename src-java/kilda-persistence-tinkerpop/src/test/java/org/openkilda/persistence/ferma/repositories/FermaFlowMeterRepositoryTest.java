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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.openkilda.model.FlowMeter;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.repositories.FlowMeterRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Optional;

public class FermaFlowMeterRepositoryTest extends InMemoryGraphBasedTest {
    static final String TEST_FLOW_ID = "test_flow";
    static final String TEST_PATH_ID = "test_path";
    static final MeterId MIN_METER_ID = new MeterId(5);
    static final MeterId MAX_METER_ID = new MeterId(25);

    FlowMeterRepository flowMeterRepository;

    Switch theSwitch;

    @Before
    public void setUp() {
        flowMeterRepository = repositoryFactory.createFlowMeterRepository();

        theSwitch = createTestSwitch(1);
    }

    @Test
    public void shouldCreateFlowMeter() {
        createFlowMeter();

        Collection<FlowMeter> allMeters = flowMeterRepository.findAll();
        FlowMeter foundMeter = allMeters.iterator().next();

        assertEquals(theSwitch.getSwitchId(), foundMeter.getSwitchId());
        assertEquals(TEST_FLOW_ID, foundMeter.getFlowId());
    }

    @Test(expected = PersistenceException.class)
    public void shouldNotGetMoreThanOneMetersForPath() {
        createFlowMeter(1, new PathId(TEST_PATH_ID));
        createFlowMeter(2, new PathId(TEST_PATH_ID));
        flowMeterRepository.findByPathId(new PathId(TEST_PATH_ID));
    }

    @Test
    public void shouldGetZeroMetersForPath() {
        Optional<FlowMeter> meters = flowMeterRepository.findByPathId(new PathId(TEST_PATH_ID));
        assertFalse(meters.isPresent());
    }

    @Test
    public void shouldDeleteFlowMeter() {
        FlowMeter meter = createFlowMeter();

        flowMeterRepository.remove(meter);

        assertEquals(0, flowMeterRepository.findAll().size());
    }

    @Test
    public void shouldDeleteFoundFlowMeter() {
        createFlowMeter();

        Collection<FlowMeter> allMeters = flowMeterRepository.findAll();
        FlowMeter foundMeter = allMeters.iterator().next();
        flowMeterRepository.remove(foundMeter);

        assertEquals(0, flowMeterRepository.findAll().size());
    }

    @Test
    public void shouldSelectNextInOrderResourceWhenFindUnassignedMeter() {
        long first = findUnassignedMeterAndCreate("flow_1");
        assertEquals(5, first);

        long second = findUnassignedMeterAndCreate("flow_2");
        assertEquals(6, second);

        long third = findUnassignedMeterAndCreate("flow_3");
        assertEquals(7, third);

        flowMeterRepository.findAll().stream()
                .filter(flowMeter -> flowMeter.getMeterId().getValue() == second)
                .forEach(flowMeterRepository::remove);
        long fourth = findUnassignedMeterAndCreate("flow_4");
        assertEquals(6, fourth);

        long fifth = findUnassignedMeterAndCreate("flow_5");
        assertEquals(8, fifth);
    }

    @Test
    public void shouldAssignTwoMetersForOneFlow() {
        String flowId = "flow_1";
        long flowCookie = findUnassignedMeterAndCreate(flowId, TEST_PATH_ID + "_1");
        assertEquals(5, flowCookie);

        long secondCookie = findUnassignedMeterAndCreate(flowId, TEST_PATH_ID + "_2");
        assertEquals(6, secondCookie);
    }

    private long findUnassignedMeterAndCreate(String flowId) {
        return findUnassignedMeterAndCreate(flowId, TEST_PATH_ID);
    }

    private long findUnassignedMeterAndCreate(String flowId, String pathId) {
        MeterId availableMeterId = flowMeterRepository
                .findFirstUnassignedMeter(theSwitch.getSwitchId(), MIN_METER_ID);
        FlowMeter flowMeterId = FlowMeter.builder()
                .switchId(theSwitch.getSwitchId())
                .meterId(availableMeterId)
                .pathId(new PathId(flowId + "_" + pathId))
                .flowId(flowId)
                .build();
        flowMeterRepository.add(flowMeterId);
        return availableMeterId.getValue();
    }

    private FlowMeter createFlowMeter(int meterId, PathId pathId) {
        FlowMeter flowMeter = FlowMeter.builder()
                .switchId(theSwitch.getSwitchId())
                .meterId(new MeterId(meterId))
                .pathId(pathId)
                .flowId(TEST_FLOW_ID)
                .build();
        flowMeterRepository.add(flowMeter);
        return flowMeter;
    }

    private FlowMeter createFlowMeter() {
        return createFlowMeter(1, new PathId(TEST_PATH_ID));
    }
}
