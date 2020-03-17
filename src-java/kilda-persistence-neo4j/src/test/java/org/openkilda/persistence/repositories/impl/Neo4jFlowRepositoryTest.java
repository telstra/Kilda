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

package org.openkilda.persistence.repositories.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class Neo4jFlowRepositoryTest extends Neo4jBasedTest {
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

    static FlowRepository flowRepository;
    static FlowPathRepository flowPathRepository;
    static SwitchRepository switchRepository;

    private Switch switchA;
    private Switch switchB;

    @BeforeClass
    public static void setUp() {
        flowRepository = new Neo4jFlowRepository(neo4jSessionFactory, txManager);
        flowPathRepository = new Neo4jFlowPathRepository(neo4jSessionFactory, txManager);
        switchRepository = new Neo4jSwitchRepository(neo4jSessionFactory, txManager);
    }

    @Before
    public void createSwitches() {
        switchA = buildTestSwitch(TEST_SWITCH_A_ID.getId());
        switchRepository.createOrUpdate(switchA);

        switchB = buildTestSwitch(TEST_SWITCH_B_ID.getId());
        switchRepository.createOrUpdate(switchB);

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldCreateFlow() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        Collection<Flow> allFlows = flowRepository.findAll();
        Flow foundFlow = allFlows.iterator().next();

        assertEquals(switchA.getSwitchId(), foundFlow.getSrcSwitch().getSwitchId());
        assertEquals(switchB.getSwitchId(), foundFlow.getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldDeleteFlow() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        flowRepository.delete(flow);

        assertEquals(0, flowRepository.findAll().size());
    }

    @Test
    public void shouldNotDeleteSwitchOnFlowDelete() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        flowRepository.delete(flow);

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldUpdateFlow() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setBandwidth(10000);
        flow.setEncapsulationType(FlowEncapsulationType.TRANSIT_VLAN);
        flowRepository.createOrUpdate(flow);

        flow.setBandwidth(100);
        flow.setDescription("test_description_updated");
        flowRepository.createOrUpdate(flow);

        Collection<Flow> allFlows = flowRepository.findAll();
        assertThat(allFlows, Matchers.hasSize(1));

        Flow foundFlow = allFlows.iterator().next();
        assertEquals(flow.getSrcSwitch().getSwitchId(), foundFlow.getSrcSwitch().getSwitchId());
        assertEquals(flow.getDestSwitch().getSwitchId(), foundFlow.getDestSwitch().getSwitchId());
        assertEquals(flow.getBandwidth(), foundFlow.getBandwidth());
        assertEquals(flow.getDescription(), foundFlow.getDescription());
    }

    @Test
    public void shouldDeleteFoundFlow() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        Flow foundFlow = flowRepository.findById(TEST_FLOW_ID).get();

        flowRepository.delete(foundFlow);

        assertEquals(0, flowRepository.findAll().size());
    }

    @Test
    public void shouldCheckForExistence() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        assertTrue(flowRepository.exists(TEST_FLOW_ID));
    }

    @Test
    public void shouldFindFlowById() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        Optional<Flow> foundFlow = flowRepository.findById(TEST_FLOW_ID);
        assertTrue(foundFlow.isPresent());
    }

    @Test
    public void shouldFind2SegmentFlowById() {
        Switch switchC = buildTestSwitch(TEST_SWITCH_C_ID.getId());
        switchRepository.createOrUpdate(switchC);

        Flow flow = buildTestFlowWithIntermediate(TEST_FLOW_ID, switchA, switchC, 100, switchB);
        flowRepository.createOrUpdate(flow);

        Optional<Flow> foundFlow = flowRepository.findById(TEST_FLOW_ID);
        assertTrue(foundFlow.isPresent());
    }

    @Test
    public void shouldFindFlowByGroupId() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);
        flowRepository.createOrUpdate(flow);

        List<Flow> foundFlow = Lists.newArrayList(flowRepository.findByGroupId(TEST_GROUP_ID));
        assertThat(foundFlow, Matchers.hasSize(1));
        assertEquals(Collections.singletonList(flow), foundFlow);
    }

    @Test
    public void shouldFindFlowsIdByGroupId() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);
        flowRepository.createOrUpdate(flow);

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
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        Collection<Flow> foundFlows = flowRepository.findByEndpoint(TEST_SWITCH_A_ID, 1);
        Set<String> foundFlowIds = foundFlows.stream().map(foundFlow -> flow.getFlowId()).collect(Collectors.toSet());
        assertThat(foundFlowIds, Matchers.hasSize(1));
    }

    @Test
    public void shouldFindFlowByEndpointAndVlan() {
        flowRepository.createOrUpdate(buildTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2));
        flowRepository.createOrUpdate(buildTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_2, switchB, PORT_2, 0));
        flowRepository.createOrUpdate(buildTestFlow(TEST_FLOW_ID_3, switchB, PORT_1, VLAN_1, switchB, PORT_3, VLAN_1));

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
                sourceExpected ? flow.get().getSrcSwitch().getSwitchId() : flow.get().getDestSwitch().getSwitchId());
        assertEquals(port, sourceExpected ? flow.get().getSrcPort() : flow.get().getDestPort());
        assertEquals(vlan, sourceExpected ? flow.get().getSrcVlan() : flow.get().getDestVlan());

        // depth of search by flowId and by endpointAndVlan is different
        assertTrue(flow.get().getForwardPath().getSegments().isEmpty());
        assertFalse(flowRepository.findById(flowId).get().getForwardPath().getSegments().isEmpty());
    }


    @Test
    public void shouldFindFlowBySwitchIdInPortAndOutVlan() {
        flowRepository.createOrUpdate(buildTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2));
        flowRepository.createOrUpdate(buildTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_2, switchB, PORT_2, 0));
        flowRepository.createOrUpdate(buildTestFlow(TEST_FLOW_ID_3, switchB, PORT_1, VLAN_1, switchB, PORT_3, VLAN_1));

        validateFindFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID, switchA.getSwitchId(), PORT_1, VLAN_2, true);
        validateFindFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_2, switchA.getSwitchId(), PORT_1, 0, true);
        validateFindFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_2, switchB.getSwitchId(), PORT_2, VLAN_2, false);
        validateFindFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_3, switchB.getSwitchId(), PORT_1, VLAN_1, true);
        validateFindFlowBySwitchIdInPortAndOutVlan(TEST_FLOW_ID_3, switchB.getSwitchId(), PORT_3, VLAN_1, false);
    }

    private void validateFindFlowBySwitchIdInPortAndOutVlan(
            String flowId, SwitchId switchId, int inPort, int outVlan, boolean sourceExpected) {
        Optional<Flow> flow = flowRepository.findBySwitchIdInPortAndOutVlan(switchId, inPort, outVlan);

        assertTrue(flow.isPresent());
        assertEquals(flowId, flow.get().getFlowId());
        assertEquals(switchId,
                sourceExpected ? flow.get().getSrcSwitch().getSwitchId() : flow.get().getDestSwitch().getSwitchId());
        assertEquals(inPort, sourceExpected ? flow.get().getSrcPort() : flow.get().getDestPort());
        assertEquals(outVlan, sourceExpected ? flow.get().getDestVlan() : flow.get().getSrcVlan());

        // depth of search by flowId and by endpoint, inPort and outVlan is different
        assertTrue(flow.get().getForwardPath().getSegments().isEmpty());
        assertFalse(flowRepository.findById(flowId).get().getForwardPath().getSegments().isEmpty());
    }

    @Test
    public void shouldFindIsByEndpointWithMultiTableSupport() {
        flowRepository.createOrUpdate(
                buildTestFlow(TEST_FLOW_ID, switchA, PORT_1, VLAN_1, switchB, PORT_2, VLAN_2, true));
        flowRepository.createOrUpdate(
                buildTestFlow(TEST_FLOW_ID_2, switchA, PORT_1, VLAN_2, switchB, PORT_2, 0, true));
        flowRepository.createOrUpdate(
                buildTestFlow(TEST_FLOW_ID_3, switchA, PORT_1, VLAN_3, switchB, PORT_2, 0, false));
        flowRepository.createOrUpdate(
                buildTestFlow(TEST_FLOW_ID_4, switchB, PORT_1, VLAN_1, switchB, PORT_3, VLAN_1, true));

        Collection<String> flowIds =
                flowRepository.findFlowsIdsByEndpointWithMultiTableSupport(switchA.getSwitchId(), PORT_1);
        assertEquals(2, flowIds.size());
        assertTrue(flowIds.contains(TEST_FLOW_ID));
        assertTrue(flowIds.contains(TEST_FLOW_ID_2));
    }

    @Test
    public void shouldFindFlowBySwitchEndpoint() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flowRepository.createOrUpdate(flow);

        Collection<Flow> foundFlows = flowRepository.findByEndpointSwitch(TEST_SWITCH_A_ID);
        Set<String> foundFlowIds = foundFlows.stream().map(foundFlow -> flow.getFlowId()).collect(Collectors.toSet());
        assertThat(foundFlowIds, Matchers.hasSize(1));
    }

    @Test
    public void shouldFindFlowBySwitchEndpointWithMultiTable() {
        Flow firstFlow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        firstFlow.setSrcWithMultiTable(true);
        flowRepository.createOrUpdate(firstFlow);

        Flow secondFlow = buildTestFlow(TEST_FLOW_ID_2, switchA, switchB);
        secondFlow.setSrcWithMultiTable(false);
        flowRepository.createOrUpdate(secondFlow);

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
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setStatus(FlowStatus.DOWN);
        flowRepository.createOrUpdate(flow);

        Collection<Flow> foundFlows = flowRepository.findDownFlows();
        assertThat(foundFlows, Matchers.hasSize(1));
    }

    @Test
    public void shouldCreateFlowGroupIdForFlow() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);
        flowRepository.createOrUpdate(flow);

        Optional<String> groupOptional = flowRepository.getOrCreateFlowGroupId(TEST_FLOW_ID);

        assertTrue(groupOptional.isPresent());
        assertNotNull(groupOptional.get());
        assertEquals(groupOptional.get(),
                flowRepository.findById(TEST_FLOW_ID).get().getGroupId());
    }

    @Test
    public void shouldGetFlowGroupIdForFlow() {
        Flow flow = buildTestFlow(TEST_FLOW_ID, switchA, switchB);
        flow.setGroupId(TEST_GROUP_ID);
        flowRepository.createOrUpdate(flow);

        Optional<String> groupOptional = flowRepository.getOrCreateFlowGroupId(TEST_FLOW_ID);

        assertTrue(groupOptional.isPresent());
        assertEquals(TEST_GROUP_ID, groupOptional.get());
    }

    private Flow buildTestFlow(String flowId, Switch srcSwitch, Switch destSwitch) {
        return buildTestFlow(flowId, srcSwitch, PORT_1, VLAN_1, destSwitch, PORT_2, VLAN_2);
    }

    private Flow buildTestFlow(String flowId, Switch srcSwitch, int srcPort, int srcVlan,
                               Switch destSwitch, int destPort, int destVlan) {
        return buildTestFlow(flowId, srcSwitch, srcPort, srcVlan, destSwitch, destPort, destVlan, false);
    }

    private Flow buildTestFlow(String flowId, Switch srcSwitch, int srcPort, int srcVlan,
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
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .srcWithMultiTable(multiTable)
                .destWithMultiTable(multiTable)
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_forward_path"))
                .flow(flow)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(1L, FlowPathDirection.FORWARD))
                .meterId(new MeterId(1))
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegment = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .build();
        forwardFlowPath.setSegments(Collections.singletonList(forwardSegment));

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_reverse_path"))
                .flow(flow)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(1L, FlowPathDirection.REVERSE))
                .meterId(new MeterId(2))
                .srcSwitch(destSwitch)
                .destSwitch(srcSwitch)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegment = PathSegment.builder()
                .srcSwitch(destSwitch)
                .srcPort(destPort)
                .destSwitch(srcSwitch)
                .destPort(srcPort)
                .build();
        reverseFlowPath.setSegments(Collections.singletonList(reverseSegment));

        return flow;
    }

    private Flow buildTestFlowWithIntermediate(String flowId, Switch srcSwitch,
                                               Switch intSwitch, int intPort, Switch destSwitch) {
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(PORT_1)
                .destSwitch(destSwitch)
                .destPort(PORT_2)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_forward_path"))
                .flow(flow)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(1L, FlowPathDirection.FORWARD))
                .meterId(new MeterId(1))
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegment = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(1)
                .destSwitch(intSwitch)
                .destPort(intPort)
                .build();
        forwardFlowPath.setSegments(Collections.singletonList(forwardSegment));

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(flowId + "_reverse_path"))
                .flow(flow)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(1L, FlowPathDirection.REVERSE))
                .meterId(new MeterId(2))
                .srcSwitch(destSwitch)
                .destSwitch(srcSwitch)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegment = PathSegment.builder()
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
        Flow flow = buildTestFlow(flowId, srcSwitch, dstSwitch);
        flow.getDetectConnectedDevices().setSrcLldp(srcLldp);
        flow.getDetectConnectedDevices().setDstLldp(dstLldp);
        flowRepository.createOrUpdate(flow);
    }

    private void createFlowWithArp(
            String flowId, Switch srcSwitch, boolean srcArp, Switch dstSwitch, boolean dstArp) {
        Flow flow = buildTestFlow(flowId, srcSwitch, dstSwitch);
        flow.getDetectConnectedDevices().setSrcArp(srcArp);
        flow.getDetectConnectedDevices().setDstArp(dstArp);
        flowRepository.createOrUpdate(flow);
    }
}
