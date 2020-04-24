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
import static org.junit.Assert.assertTrue;

import org.openkilda.model.PathId;
import org.openkilda.model.TransitVlan;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.TransitVlanRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Optional;

public class FermaTransitVlanRepositoryTest extends InMemoryGraphBasedTest {
    static final String TEST_FLOW_ID = "test_flow";
    static final int VLAN = 1;
    static final int MIN_TRANSIT_VLAN = 5;
    static final int MAX_TRANSIT_VLAN = 25;

    TransitVlanRepository transitVlanRepository;

    @Before
    public void setUp() {
        transitVlanRepository = repositoryFactory.createTransitVlanRepository();
    }

    @Test
    public void shouldCreateTransitVlan() {
        TransitVlan vlan = createTransitVlan();

        Collection<TransitVlan> allVlans = transitVlanRepository.findAll();
        TransitVlan foundVlan = allVlans.iterator().next();

        assertEquals(vlan.getVlan(), foundVlan.getVlan());
        assertEquals(TEST_FLOW_ID, foundVlan.getFlowId());
    }

    @Test
    public void shouldFindTransitVlan() {
        TransitVlan vlan = createTransitVlan();
        vlan.setVlan(VLAN);

        Optional<TransitVlan> foundVlan = transitVlanRepository.findByVlan(VLAN);

        assertTrue(foundVlan.isPresent());
        assertEquals(vlan.getVlan(), foundVlan.get().getVlan());
        assertEquals(vlan.getFlowId(), foundVlan.get().getFlowId());
        assertEquals(vlan.getPathId(), foundVlan.get().getPathId());
    }

    @Test
    public void shouldDeleteTransitVlan() {
        TransitVlan vlan = createTransitVlan();

        transitVlanRepository.remove(vlan);

        assertEquals(0, transitVlanRepository.findAll().size());
    }

    @Test
    public void shouldDeleteFoundTransitVlan() {
        createTransitVlan();

        Collection<TransitVlan> allVlans = transitVlanRepository.findAll();
        TransitVlan foundVlan = allVlans.iterator().next();
        transitVlanRepository.remove(foundVlan);

        assertEquals(0, transitVlanRepository.findAll().size());
    }

    private TransitVlan createTransitVlan() {
        TransitVlan transitVlan = TransitVlan.builder()
                .vlan(1).pathId(new PathId(TEST_FLOW_ID + "_path")).flowId(TEST_FLOW_ID).build();
        transitVlanRepository.add(transitVlan);
        return transitVlan;
    }

    @Test
    public void shouldSelectNextInOrderResourceWhenFindUnassignedTransitVlan() {
        int first = findUnassignedTransitVlanAndCreate("flow_1");
        assertEquals(5, first);

        int second = findUnassignedTransitVlanAndCreate("flow_2");
        assertEquals(6, second);

        int third = findUnassignedTransitVlanAndCreate("flow_3");
        assertEquals(7, third);

        transitVlanRepository.findByVlan(second).ifPresent(transitVlanRepository::remove);
        int fourth = findUnassignedTransitVlanAndCreate("flow_4");
        assertEquals(6, fourth);

        int fifth = findUnassignedTransitVlanAndCreate("flow_5");
        assertEquals(8, fifth);
    }

    private int findUnassignedTransitVlanAndCreate(String flowId) {
        int availableVlan = transitVlanRepository.findFirstUnassignedVlan(MIN_TRANSIT_VLAN);
        TransitVlan transitVlan = TransitVlan.builder()
                .vlan(availableVlan)
                .pathId(new PathId(TEST_FLOW_ID + "_path"))
                .flowId(flowId)
                .build();
        transitVlanRepository.add(transitVlan);
        return availableVlan;
    }
}
