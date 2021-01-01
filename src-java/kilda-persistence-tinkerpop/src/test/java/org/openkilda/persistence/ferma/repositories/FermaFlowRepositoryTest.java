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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowFilter;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class FermaFlowRepositoryTest extends InMemoryGraphBasedTest {
    static final String TEST_FLOW_ID = "test_flow_1";
    static final String TEST_FLOW_ID_2 = "test_flow_2";
    static final String TEST_FLOW_ID_3 = "test_flow_3";
    static final String TEST_FLOW_ID_4 = "test_flow_4";
    static final String TEST_GROUP_ID = "test_group";
    static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);
    static final SwitchId TEST_SWITCH_C_ID = new SwitchId(3);
    static final int PORT_1 = 1;
    static final int PORT_2 = 2;
    static final int PORT_3 = 3;
    public static final int VLAN_1 = 3;
    public static final int VLAN_2 = 4;
    public static final int VLAN_3 = 5;

    FlowRepository flowRepository;
    FlowPathRepository flowPathRepository;
    SwitchRepository switchRepository;

    Switch switchA;
    Switch switchB;

    @Before
    public void setUp() {
        flowRepository = repositoryFactory.createFlowRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        switchRepository = repositoryFactory.createSwitchRepository();

        switchA = createTestSwitch(TEST_SWITCH_A_ID.getId());
        switchB = createTestSwitch(TEST_SWITCH_B_ID.getId());

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldCreateFlow() {
        createTestFlow(TEST_FLOW_ID, switchA, switchB);

        Collection<Flow> allFlows = flowRepository.findAll();
        Flow foundFlow = allFlows.iterator().next();

        assertEquals(switchA.getSwitchId(), foundFlow.getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), foundFlow.getDestSwitchId());
    }

    @Test
    public void shouldFindByIdWithEndpoints() {
        Flow firstFlow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        Flow secondFlow = createTestFlow(TEST_FLOW_ID_2, switchA, switchB);

        Collection<Flow> allFlows = flowRepository.findAll();
        Flow foundFlow = allFlows.iterator().next();

        assertNotNull(foundFlow.getForwardPath());
        assertNotNull(foundFlow.getReversePath());

        Optional<Flow> flowWithoutPaths = flowRepository.findById(firstFlow.getFlowId());

        assertTrue(flowWithoutPaths.isPresent());
        assertEquals(switchA.getSwitchId(), flowWithoutPaths.get().getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), flowWithoutPaths.get().getDestSwitchId());
    }

    @Test
    public void shouldNotFindByIdWithEndpoints() {
        assertFalse(flowRepository.findById("Non_existent").isPresent());
    }

    @Test
    public void shouldDeleteFlow() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);

        transactionManager.doInTransaction(() ->
                flowRepository.remove(flow));

        assertEquals(0, flowRepository.findAll().size());
    }

    @Test
    public void shouldNotDeleteSwitchOnFlowDelete() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);

        transactionManager.doInTransaction(() ->
                flowRepository.remove(flow));

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldUpdateFlow() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setBandwidth(10000);
        flow.setEncapsulationType(FlowEncapsulationType.TRANSIT_VLAN);

        flow = flowRepository.findById(TEST_FLOW_ID).get();
        flow.setBandwidth(100);
        flow.setDescription("test_description_updated");

        Collection<Flow> allFlows = flowRepository.findAll();
        assertThat(allFlows, Matchers.hasSize(1));

        Flow foundFlow = allFlows.iterator().next();
        assertEquals(flow.getSrcSwitchId(), foundFlow.getSrcSwitchId());
        assertEquals(flow.getDestSwitchId(), foundFlow.getDestSwitchId());
        assertEquals(flow.getBandwidth(), foundFlow.getBandwidth());
        assertEquals(flow.getDescription(), foundFlow.getDescription());
    }

    @Test
    public void shouldDeleteFoundFlow() {
        createTestFlow(TEST_FLOW_ID, switchA, switchB);

        transactionManager.doInTransaction(() -> {
            Flow foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
            flowRepository.remove(foundFlow);
        });

        assertEquals(0, flowRepository.findAll().size());
    }

    @Test
    public void shouldCheckForExistence() {
        createTestFlow(TEST_FLOW_ID, switchA, switchB);

        assertTrue(flowRepository.exists(TEST_FLOW_ID));
    }

    @Test
    public void shouldFindFlowById() {
        createTestFlow(TEST_FLOW_ID, switchA, switchB);

        Optional<Flow> foundFlow = flowRepository.findById(TEST_FLOW_ID);
        assertTrue(foundFlow.isPresent());
    }

    @Test
    public void shouldFind2SegmentFlowById() {
        Switch switchC = createTestSwitch(TEST_SWITCH_C_ID.getId());
        createTestFlowWithIntermediate(TEST_FLOW_ID, switchA, switchC, 100, switchB);

        Optional<Flow> foundFlow = flowRepository.findById(TEST_FLOW_ID);
        assertTrue(foundFlow.isPresent());
    }

    @Test
    public void shouldFindFlowByGroupId() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);

        List<Flow> foundFlow = Lists.newArrayList(flowRepository.findByGroupId(TEST_GROUP_ID));
        assertThat(foundFlow, Matchers.hasSize(1));
        assertEquals(Collections.singletonList(flow), foundFlow);
    }

    @Test
    public void shouldFindFlowsIdByGroupId() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);

        List<String> foundFlowId = Lists.newArrayList(flowRepository.findFlowsIdByGroupId(TEST_GROUP_ID));
        assertThat(foundFlowId, Matchers.hasSize(1));
        assertEquals(Collections.singletonList(TEST_FLOW_ID), foundFlowId);
    }

    @Test
    public void shouldFindFlowByEndpointSwitchWithEnabledArp() {
        createFlowWithArp(TEST_FLOW_ID, switchA, false, switchB, false);
        createFlowWithArp(TEST_FLOW_ID_2, switchA, true, switchB, false);
        createFlowWithArp(TEST_FLOW_ID_3, switchB, false, switchA, true);
        createFlowWithArp(TEST_FLOW_ID_4, switchA, true, switchA, true);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitchWithEnabledArp(TEST_SWITCH_A_ID);
        Set<String> foundFlowIds = foundFlows.stream()
                .map(Flow::getFlowId)
                .collect(Collectors.toSet());

        assertEquals(Sets.newHashSet(TEST_FLOW_ID_2, TEST_FLOW_ID_3, TEST_FLOW_ID_4), foundFlowIds);
    }

    @Test
    public void shouldFindOneFlowByEndpointSwitchWithEnabledArp() {
        // one switch flow with ARP on src and dst
        createFlowWithArp(TEST_FLOW_ID, switchA, true, switchA, true);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitchWithEnabledArp(TEST_SWITCH_A_ID);

        // only one Flow object must be returned
        assertEquals(1, foundFlows.size());
        assertEquals(TEST_FLOW_ID, foundFlows.iterator().next().getFlowId());
    }

    @Test
    public void shouldFindFlowByEndpoint() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);

        Collection<Flow> foundFlows = flowRepository.findByEndpoint(TEST_SWITCH_A_ID, 1);
        Set<String> foundFlowIds = foundFlows.stream().map(foundFlow -> flow.getFlowId()).collect(Collectors.toSet());
        assertThat(foundFlowIds, Matchers.hasSize(1));
    }

    @Test
    public void shouldNotFindFlowByEndpointAndVlan() {
        assertFalse(flowRepository.findByEndpointAndVlan(new SwitchId(1234), 999, 999).isPresent());
    }

    @Test
    public void shouldFindFlowByEndpointAndVlan() {
        createTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2);
        createTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_2, switchB, PORT_2, 0);
        createTestFlow(TEST_FLOW_ID_3, switchB, PORT_1, VLAN_1, switchB, PORT_3, VLAN_1);

        validateFindFlowByEndpointAndVlan(TEST_FLOW_ID, switchA.getSwitchId(), PORT_1, VLAN_1, true);
        validateFindFlowByEndpointAndVlan(TEST_FLOW_ID_2, switchA.getSwitchId(), PORT_1, VLAN_2, true);
        validateFindFlowByEndpointAndVlan(TEST_FLOW_ID_2, switchB.getSwitchId(), PORT_2, 0, false);
        validateFindFlowByEndpointAndVlan(TEST_FLOW_ID_3, switchB.getSwitchId(), PORT_1, VLAN_1, true);
        validateFindFlowByEndpointAndVlan(TEST_FLOW_ID_3, switchB.getSwitchId(), PORT_3, VLAN_1, false);
    }

    private void validateFindFlowByEndpointAndVlan(
            String flowId, SwitchId switchId, int port, int vlan, boolean sourceExpected) {
        Optional<Flow> flow = flowRepository.findByEndpointAndVlan(switchId, port, vlan);

        assertTrue(flow.isPresent());
        assertEquals(flowId, flow.get().getFlowId());
        assertEquals(switchId,
                sourceExpected ? flow.get().getSrcSwitchId() : flow.get().getDestSwitchId());
        assertEquals(port, sourceExpected ? flow.get().getSrcPort() : flow.get().getDestPort());
        assertEquals(vlan, sourceExpected ? flow.get().getSrcVlan() : flow.get().getDestVlan());
    }

    @Test
    public void shouldNotFindOneSwitchFlowBySwitchIdInPortAndOutVlanIfFlowNotExist() {
        assertFalse(flowRepository.findOneSwitchFlowBySwitchIdInPortAndOutVlan(
                new SwitchId(1234), 999, 999).isPresent());
    }

    @Test
    public void shouldNotFindNotOneSwitchFlowBySwitchIdInPortAndOutVlan() {
        createTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2);
        // not one switch flow
        Optional<Flow> flow = flowRepository.findOneSwitchFlowBySwitchIdInPortAndOutVlan(
                switchA.getSwitchId(), PORT_1, VLAN_2);
        assertFalse(flow.isPresent());
    }

    @Test
    public void shouldFindOnlyOneSwitchFlowBySwitchIdInPortAndOutVlan() {
        // one switch flow
        createTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchA, PORT_2, VLAN_2);
        // tho switch flow with same IN_PORT and OUT_VLAN
        createTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_3, switchB, PORT_2, VLAN_2);

        Optional<Flow> flow = flowRepository.findOneSwitchFlowBySwitchIdInPortAndOutVlan(
                switchA.getSwitchId(), PORT_1, VLAN_2);

        // found only first flow because second is NOT one switch flow
        assertTrue(flow.isPresent());
        assertEquals(TEST_FLOW_ID, flow.get().getFlowId());
    }


    @Test
    public void shouldFindOneSwitchFlowBySwitchIdInPortAndOutVlan() {
        createTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchA, PORT_2, VLAN_2);
        createTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_2, switchA, PORT_2, 0);
        createTestFlow(TEST_FLOW_ID_3, switchB, PORT_1, VLAN_1, switchB, PORT_3, VLAN_1);

        validateFindOneSwitchFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID, TEST_SWITCH_A_ID, PORT_1, VLAN_2, true);
        validateFindOneSwitchFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_2, TEST_SWITCH_A_ID, PORT_1, 0, true);
        validateFindOneSwitchFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_2, TEST_SWITCH_A_ID, PORT_2, VLAN_2, false);
        validateFindOneSwitchFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_3, TEST_SWITCH_B_ID, PORT_1, VLAN_1, true);
        validateFindOneSwitchFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_3, TEST_SWITCH_B_ID, PORT_3, VLAN_1, false);
    }

    private void validateFindOneSwitchFlowBySwitchIdInPortAndOutVlan(
            String flowId, SwitchId switchId, int inPort, int outVlan, boolean sourceExpected) {
        Optional<Flow> flow = flowRepository.findOneSwitchFlowBySwitchIdInPortAndOutVlan(switchId, inPort, outVlan);

        assertTrue(flow.isPresent());
        assertEquals(flowId, flow.get().getFlowId());
        assertEquals(switchId,
                sourceExpected ? flow.get().getSrcSwitchId() : flow.get().getDestSwitchId());
        assertEquals(inPort, sourceExpected ? flow.get().getSrcPort() : flow.get().getDestPort());
        assertEquals(outVlan, sourceExpected ? flow.get().getDestVlan() : flow.get().getSrcVlan());
    }

    @Test
    public void shouldFindIsByEndpointWithMultiTableSupport() {
        createTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2, true);
        createTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_2, switchB, PORT_2, 0, true);
        createTestFlow(TEST_FLOW_ID_3, switchA, PORT_1, VLAN_3, switchB, PORT_2, 0, false);
        createTestFlow(TEST_FLOW_ID_4, switchB, PORT_1, VLAN_1, switchB, PORT_3, VLAN_1, true);

        Collection<String> flowIds =
                flowRepository.findFlowsIdsByEndpointWithMultiTableSupport(switchA.getSwitchId(), PORT_1);
        assertEquals(2, flowIds.size());
        assertTrue(flowIds.contains(TEST_FLOW_ID));
        assertTrue(flowIds.contains(TEST_FLOW_ID_2));
    }

    @Test
    public void shouldFindFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport() {
        createTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2, true);
        createTestFlow(TEST_FLOW_ID_2, switchA, PORT_2, VLAN_2, switchB, PORT_2, 0, true);
        createTestFlow(TEST_FLOW_ID_3, switchA, PORT_1, VLAN_3, switchB, PORT_2, 0, false);
        createTestFlow(TEST_FLOW_ID_4, switchA, PORT_1, VLAN_1, switchA, PORT_3, VLAN_1, true);

        Collection<String> flowIds = flowRepository.findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(
                switchA.getSwitchId(), PORT_1);
        assertEquals(1, flowIds.size());
        assertEquals(TEST_FLOW_ID, flowIds.iterator().next());
    }

    @Test
    public void shouldFindFlowBySwitchEndpoint() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitch(TEST_SWITCH_A_ID);
        Set<String> foundFlowIds = foundFlows.stream().map(foundFlow -> flow.getFlowId()).collect(Collectors.toSet());
        assertThat(foundFlowIds, Matchers.hasSize(1));
    }

    @Test
    public void shouldFindFlowBySwitchEndpointWithMultiTable() {
        Flow firstFlow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        firstFlow.setSrcWithMultiTable(true);

        Flow secondFlow = createTestFlow(TEST_FLOW_ID_2, switchA, switchB);
        secondFlow.setSrcWithMultiTable(false);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitchWithMultiTableSupport(TEST_SWITCH_A_ID);
        Set<String> foundFlowIds = foundFlows.stream().map(Flow::getFlowId).collect(Collectors.toSet());
        assertEquals(Collections.singleton(firstFlow.getFlowId()), foundFlowIds);
    }

    @Test
    public void shouldFindFlowByEndpointSwitchWithEnabledLldp() {
        createFlowWithLldp(TEST_FLOW_ID, switchA, false, switchB, false);
        createFlowWithLldp(TEST_FLOW_ID_2, switchA, true, switchB, false);
        createFlowWithLldp(TEST_FLOW_ID_3, switchB, false, switchA, true);
        createFlowWithLldp(TEST_FLOW_ID_4, switchA, true, switchA, true);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitchWithEnabledLldp(TEST_SWITCH_A_ID);
        Set<String> foundFlowIds = foundFlows.stream()
                .map(Flow::getFlowId)
                .collect(Collectors.toSet());

        assertEquals(Sets.newHashSet(TEST_FLOW_ID_2, TEST_FLOW_ID_3, TEST_FLOW_ID_4), foundFlowIds);
    }

    @Test
    public void shouldFindOneFlowByEndpoint() {
        // one switch flow with LLDP on src and dst
        Flow flow1 = createTestFlow(TEST_FLOW_ID, switchA, switchA);
        flow1.setSrcPort(1);
        flow1.setDestPort(2);

        Flow flow2 = createTestFlow(TEST_FLOW_ID_2, switchB, switchB);
        flow2.setSrcPort(1);
        flow2.setDestPort(2);

        createTestFlow(TEST_FLOW_ID_3, switchA, switchB);

        Collection<Flow> foundFlows = flowRepository.findOneSwitchFlows(switchA.getSwitchId());

        assertEquals(1, foundFlows.size());
        assertEquals(TEST_FLOW_ID, foundFlows.iterator().next().getFlowId());
    }

    @Test
    public void shouldFindOneFlowByEndpointSwitchWithEnabledLldp() {
        // one switch flow with LLDP on src and dst
        createFlowWithLldp(TEST_FLOW_ID, switchA, true, switchA, true);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitchWithEnabledLldp(TEST_SWITCH_A_ID);

        // only one Flow object must be returned
        assertEquals(1, foundFlows.size());
        assertEquals(TEST_FLOW_ID, foundFlows.iterator().next().getFlowId());
    }

    @Test
    public void shouldFindDownFlowIdsByEndpoint() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setStatus(FlowStatus.DOWN);

        Collection<Flow> foundFlows = flowRepository.findInactiveFlows();
        assertThat(foundFlows, Matchers.hasSize(1));
    }

    @Test
    public void shouldCreateFlowGroupIdForFlow() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);

        Optional<String> groupOptional = flowRepository.getOrCreateFlowGroupId(TEST_FLOW_ID);

        assertTrue(groupOptional.isPresent());
        assertNotNull(groupOptional.get());
        assertEquals(groupOptional.get(),
                flowRepository.findById(TEST_FLOW_ID).get().getGroupId());
    }

    @Test
    public void shouldGetFlowGroupIdForFlow() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);

        Optional<String> groupOptional = flowRepository.getOrCreateFlowGroupId(TEST_FLOW_ID);

        assertTrue(groupOptional.isPresent());
        assertEquals(TEST_GROUP_ID, groupOptional.get());
    }

    @Test
    public void shouldComputeSumOfFlowsBandwidth() {
        long firstFlowBandwidth = 100000L;
        long secondFlowBandwidth = 500000L;
        Flow firstFlow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        firstFlow.setBandwidth(firstFlowBandwidth);
        Flow secondFlow = createTestFlow(TEST_FLOW_ID_2, switchA, switchB);
        secondFlow.setBandwidth(secondFlowBandwidth);

        long foundBandwidth = flowRepository.computeFlowsBandwidthSum(Sets.newHashSet(TEST_FLOW_ID, TEST_FLOW_ID_2));

        assertEquals(firstFlowBandwidth + secondFlowBandwidth, foundBandwidth);
    }

    @Test
    public void shouldUpdateFlowStatusAndFlowStatusInfo() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setStatus(FlowStatus.UP);
        flow.setStatusInfo("status_info");

        String newStatusInfo = "updated_status_info";
        flowRepository.updateStatus(flow.getFlowId(), FlowStatus.DOWN, newStatusInfo);

        Flow updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertEquals(FlowStatus.DOWN, updatedFlow.getStatus());
        assertEquals(newStatusInfo, updatedFlow.getStatusInfo());

        flowRepository.updateStatus(flow.getFlowId(), FlowStatus.DOWN, null);

        updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertNull(updatedFlow.getStatusInfo());
    }

    @Test
    public void shouldUpdateFlowStatusInfo() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setStatusInfo("status_info");

        String newStatusInfo = "updated_status_info";
        flowRepository.updateStatusInfo(TEST_FLOW_ID, newStatusInfo);

        Flow updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertEquals(newStatusInfo, updatedFlow.getStatusInfo());

        flowRepository.updateStatusInfo(flow.getFlowId(), null);

        updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertNull(updatedFlow.getStatusInfo());
    }

    @Test
    public void shouldUpdateFlowStatusSafe() {
        transactionManager.doInTransaction(() -> {
            Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
            flow.setStatus(FlowStatus.UP);
            flow.setStatusInfo("status_info");

            String newStatusInfo = "updated_status_info";
            flowRepository.updateStatusSafe(flow, FlowStatus.DOWN, newStatusInfo);

            Flow updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
            assertEquals(FlowStatus.DOWN, updatedFlow.getStatus());
            assertEquals(newStatusInfo, updatedFlow.getStatusInfo());

            flowRepository.updateStatusSafe(flow, FlowStatus.DOWN, null);

            updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
            assertNull(updatedFlow.getStatusInfo());
        });
    }

    @Test
    public void shouldNotUpdateFlowStatusSafeWhenFlowIsInProgressState() {
        Flow flow = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setStatus(FlowStatus.IN_PROGRESS);
        String statusInfo = "status_info";
        flow.setStatusInfo(statusInfo);

        transactionManager.doInTransaction(() ->
                flowRepository.updateStatusSafe(flow, FlowStatus.DOWN, "updated_status_info"));

        Flow updatedFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertEquals(FlowStatus.IN_PROGRESS, updatedFlow.getStatus());
        assertEquals(statusInfo, updatedFlow.getStatusInfo());
    }

    @Test
    public void shouldGetAllDownFlows() {
        Flow flowA = createTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowA.setStatus(FlowStatus.DOWN);
        Flow flowB = createTestFlow(TEST_FLOW_ID_2, switchA, switchB);
        flowB.setStatus(FlowStatus.DOWN);
        createTestFlow(TEST_FLOW_ID_3, switchA, switchB);

        Collection<String> foundDownFlows =
                flowRepository.findByFlowFilter(FlowFilter.builder().flowStatus(FlowStatus.DOWN).build()).stream()
                        .map(Flow::getFlowId)
                        .collect(Collectors.toList());
        assertEquals(2, foundDownFlows.size());
        assertTrue(foundDownFlows.contains(TEST_FLOW_ID));
        assertTrue(foundDownFlows.contains(TEST_FLOW_ID_2));

        Collection<String> foundFlows =
                flowRepository.findByFlowFilter(FlowFilter.builder().build()).stream()
                        .map(Flow::getFlowId)
                        .collect(Collectors.toList());
        assertEquals(3, foundFlows.size());
    }

    private Flow createTestFlow(String flowId, Switch srcSwitch, Switch destSwitch) {
        return createTestFlow(flowId, srcSwitch, PORT_1, VLAN_1, destSwitch, PORT_2, VLAN_2);
    }

    private Flow createTestFlow(String flowId, Switch srcSwitch, int srcPort, int srcVlan,
                                Switch destSwitch, int destPort, int destVlan) {
        return createTestFlow(flowId, srcSwitch, srcPort, srcVlan, destSwitch, destPort, destVlan, false);
    }

    private Flow createTestFlow(String flowId, Switch srcSwitch, int srcPort, int srcVlan,
                                Switch destSwitch, int destPort, int destVlan, boolean multiTable) {
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .srcVlan(srcVlan)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .destVlan(destVlan)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .srcWithMultiTable(multiTable)
                .destWithMultiTable(multiTable)
                .build();
        flowRepository.add(flow);

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_forward_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1L))
                .meterId(new MeterId(1))
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .status(FlowPathStatus.ACTIVE)
                .build();

        PathSegment forwardSegment = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .build();
        forwardFlowPath.setSegments(Collections.singletonList(forwardSegment));

        flowPathRepository.add(forwardFlowPath);
        flow.setForwardPath(forwardFlowPath);

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_reverse_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 1L))
                .meterId(new MeterId(2))
                .srcSwitch(destSwitch)
                .destSwitch(srcSwitch)
                .status(FlowPathStatus.ACTIVE)
                .build();

        PathSegment reverseSegment = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(destSwitch)
                .srcPort(destPort)
                .destSwitch(srcSwitch)
                .destPort(srcPort)
                .build();
        reverseFlowPath.setSegments(Collections.singletonList(reverseSegment));

        flowPathRepository.add(reverseFlowPath);
        flow.setReversePath(reverseFlowPath);

        return flow;
    }

    private Flow createTestFlowWithIntermediate(String flowId, Switch srcSwitch,
                                                Switch intSwitch, int intPort, Switch destSwitch) {
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(PORT_1)
                .destSwitch(destSwitch)
                .destPort(PORT_2)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .build();
        flowRepository.add(flow);

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_forward_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1L))
                .meterId(new MeterId(1))
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flowPathRepository.add(forwardFlowPath);
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegment = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(srcSwitch)
                .srcPort(1)
                .destSwitch(intSwitch)
                .destPort(intPort)
                .build();
        forwardFlowPath.setSegments(Collections.singletonList(forwardSegment));

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_reverse_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 1L))
                .meterId(new MeterId(2))
                .srcSwitch(destSwitch)
                .destSwitch(srcSwitch)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flowPathRepository.add(reverseFlowPath);
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegment = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(intSwitch)
                .srcPort(100)
                .destSwitch(srcSwitch)
                .destPort(1)
                .build();
        reverseFlowPath.setSegments(Collections.singletonList(reverseSegment));

        return flow;
    }

    private void createFlowWithLldp(
            String flowId, Switch srcSwitch, boolean srcLldp, Switch dstSwitch, boolean dstLldp) {
        Flow flow = createTestFlow(flowId, srcSwitch, dstSwitch);
        flow.setDetectConnectedDevices(flow.getDetectConnectedDevices().toBuilder()
                .srcLldp(srcLldp).dstLldp(dstLldp).build());
    }

    private void createFlowWithArp(
            String flowId, Switch srcSwitch, boolean srcArp, Switch dstSwitch, boolean dstArp) {
        Flow flow = createTestFlow(flowId, srcSwitch, dstSwitch);
        flow.setDetectConnectedDevices(flow.getDetectConnectedDevices().toBuilder()
                .srcArp(srcArp).dstArp(dstArp).build());
    }
}
