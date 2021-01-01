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

package org.openkilda.wfm.topology.nbworker.services;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.model.FlowPatch;
import org.openkilda.messaging.model.PatchEndpoint;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.flow.TestFlowBuilder;
import org.openkilda.wfm.topology.nbworker.bolts.FlowOperationsCarrier;
import org.openkilda.wfm.topology.nbworker.services.FlowOperationsService.UpdateFlowResult;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.stream.Collectors;

public class FlowOperationsServiceTest extends InMemoryGraphBasedTest {
    public static final String FLOW_ID_1 = "flow_1";
    public static final String FLOW_ID_2 = "flow_2";
    public static final String FLOW_ID_3 = "flow_3";
    public static final PathId FORWARD_PATH_1 = new PathId("forward_path_1");
    public static final PathId FORWARD_PATH_2 = new PathId("forward_path_2");
    public static final PathId FORWARD_PATH_3 = new PathId("forward_path_3");
    public static final PathId REVERSE_PATH_1 = new PathId("reverse_path_1");
    public static final PathId REVERSE_PATH_2 = new PathId("reverse_path_2");
    public static final PathId REVERSE_PATH_3 = new PathId("reverse_path_3");
    public static final long UNMASKED_COOKIE = 123;
    public static final SwitchId SWITCH_ID_1 = new SwitchId(1);
    public static final SwitchId SWITCH_ID_2 = new SwitchId(2);
    public static final SwitchId SWITCH_ID_3 = new SwitchId(3);
    public static final SwitchId SWITCH_ID_4 = new SwitchId(4);

    private static FlowOperationsService flowOperationsService;
    private static FlowRepository flowRepository;
    private static FlowPathRepository flowPathRepository;
    private static PathSegmentRepository pathSegmentRepository;
    private static SwitchRepository switchRepository;

    @BeforeClass
    public static void setUpOnce() {
        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        pathSegmentRepository = persistenceManager.getRepositoryFactory().createPathSegmentRepository();
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        flowOperationsService = new FlowOperationsService(persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager());
    }

    @Test
    public void shouldUpdateMaxLatencyPriorityAndPinnedFlowFields() throws FlowNotFoundException {
        String testFlowId = "flow_id";
        Long maxLatency = 555L;
        Integer priority = 777;
        PathComputationStrategy pathComputationStrategy = PathComputationStrategy.LATENCY;
        String description = "new_description";

        Flow flow = new TestFlowBuilder()
                .flowId(testFlowId)
                .srcSwitch(createSwitch(SWITCH_ID_1))
                .srcPort(1)
                .srcVlan(10)
                .destSwitch(createSwitch(SWITCH_ID_2))
                .destPort(2)
                .destVlan(11)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .pathComputationStrategy(PathComputationStrategy.COST)
                .description("description")
                .status(FlowStatus.UP)
                .build();
        flowRepository.add(flow);

        FlowPatch receivedFlow = FlowPatch.builder()
                .flowId(testFlowId)
                .maxLatency(maxLatency)
                .priority(priority)
                .pinned(true)
                .targetPathComputationStrategy(pathComputationStrategy)
                .description("new_description")
                .build();

        Flow updatedFlow = flowOperationsService.updateFlow(new FlowCarrierImpl(), receivedFlow);

        assertEquals(maxLatency, updatedFlow.getMaxLatency());
        assertEquals(priority, updatedFlow.getPriority());
        assertEquals(pathComputationStrategy, updatedFlow.getTargetPathComputationStrategy());
        assertEquals(description, updatedFlow.getDescription());
        assertTrue(updatedFlow.isPinned());

        receivedFlow = FlowPatch.builder()
                .flowId(testFlowId)
                .build();
        updatedFlow = flowOperationsService.updateFlow(new FlowCarrierImpl(), receivedFlow);

        assertEquals(maxLatency, updatedFlow.getMaxLatency());
        assertEquals(priority, updatedFlow.getPriority());
        assertEquals(pathComputationStrategy, updatedFlow.getTargetPathComputationStrategy());
        assertEquals(description, updatedFlow.getDescription());
        assertTrue(updatedFlow.isPinned());
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithChangedStrategy() {
        // given: FlowPatch with COST strategy and Flow with MAX_LATENCY strategy
        String flowId = "test_flow_id";
        FlowPatch flowDto = FlowPatch.builder()
                .flowId(flowId)
                .maxLatency(100L)
                .pathComputationStrategy(PathComputationStrategy.COST)
                .build();
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();

        // when: compare this flows
        UpdateFlowResult result = flowOperationsService.prepareFlowUpdateResult(flowDto, flow).build();

        // then: needUpdateFlow flag set to true
        assertTrue(result.isNeedUpdateFlow());
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithChangedMaxLatencyFirstCase() {
        // given: FlowPatch with max latency and no strategy and Flow with MAX_LATENCY strategy and no max latency
        String flowId = "test_flow_id";
        FlowPatch flowDto = FlowPatch.builder()
                .flowId(flowId)
                .maxLatency(100L)
                .build();
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();

        // when: compare this flows
        UpdateFlowResult result = flowOperationsService.prepareFlowUpdateResult(flowDto, flow).build();

        // then: needRerouteFlow flag set to true
        assertTrue(result.isNeedUpdateFlow());
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithChangedMaxLatencySecondCase() {
        // given: FlowPatch with max latency and MAX_LATENCY strategy
        //        and Flow with MAX_LATENCY strategy and no max latency
        String flowId = "test_flow_id";
        FlowPatch flowDto = FlowPatch.builder()
                .flowId(flowId)
                .maxLatency(100L)
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();

        // when: compare this flows
        UpdateFlowResult result = flowOperationsService.prepareFlowUpdateResult(flowDto, flow).build();

        // then: needRerouteFlow flag set to true
        assertTrue(result.isNeedUpdateFlow());
    }

    @Test
    public void shouldPrepareFlowUpdateResultShouldNotUpdateFirstCase() {
        // given: FlowPatch with max latency and MAX_LATENCY strategy
        //        and Flow with MAX_LATENCY strategy and same max latency
        String flowId = "test_flow_id";
        FlowPatch flowDto = FlowPatch.builder()
                .flowId(flowId)
                .maxLatency(100L)
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .maxLatency(100L)
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();

        // when: compare this flows
        UpdateFlowResult result = flowOperationsService.prepareFlowUpdateResult(flowDto, flow).build();

        // then: needRerouteFlow flag set to false
        assertFalse(result.isNeedUpdateFlow());
    }

    @Test
    public void shouldPrepareFlowUpdateResultShouldNotUpdateSecondCase() {
        // given: FlowPatch with no max latency and no strategy
        //        and Flow with MAX_LATENCY strategy and max latency
        String flowId = "test_flow_id";
        FlowPatch flowDto = FlowPatch.builder()
                .flowId(flowId)
                .build();
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .maxLatency(100L)
                .pathComputationStrategy(PathComputationStrategy.MAX_LATENCY)
                .build();

        UpdateFlowResult result = flowOperationsService.prepareFlowUpdateResult(flowDto, flow).build();

        assertFalse(result.isNeedUpdateFlow());
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithNeedUpdateFlag() {
        String flowId = "test_flow_id";
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .srcPort(2)
                .srcVlan(3)
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .destPort(4)
                .destVlan(5)
                .bandwidth(1000)
                .allocateProtectedPath(true)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .pathComputationStrategy(PathComputationStrategy.COST)
                .build();

        // new src switch
        FlowPatch flowPatch = FlowPatch.builder()
                .source(PatchEndpoint.builder().switchId(new SwitchId(3)).build())
                .build();
        UpdateFlowResult result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        //new src port
        flowPatch = FlowPatch.builder().source(PatchEndpoint.builder().portNumber(9).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new src vlan
        flowPatch = FlowPatch.builder().source(PatchEndpoint.builder().vlanId(9).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new src inner vlan
        flowPatch = FlowPatch.builder().source(PatchEndpoint.builder().innerVlanId(9).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new src LLDP flag
        flowPatch = FlowPatch.builder().source(PatchEndpoint.builder().trackLldpConnectedDevices(true).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new src ARP flag
        flowPatch = FlowPatch.builder().source(PatchEndpoint.builder().trackArpConnectedDevices(true).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new dst switch
        flowPatch = FlowPatch.builder().destination(PatchEndpoint.builder().switchId(new SwitchId(3)).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        //new dst port
        flowPatch = FlowPatch.builder().destination(PatchEndpoint.builder().portNumber(9).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new dst vlan
        flowPatch = FlowPatch.builder().destination(PatchEndpoint.builder().vlanId(9).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new dst inner vlan
        flowPatch = FlowPatch.builder().destination(PatchEndpoint.builder().innerVlanId(9).build()).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new dst LLDP flag
        flowPatch = FlowPatch.builder()
                .destination(PatchEndpoint.builder().trackLldpConnectedDevices(true).build())
                .build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new dst ARP flag
        flowPatch = FlowPatch.builder()
                .destination(PatchEndpoint.builder().trackArpConnectedDevices(true).build())
                .build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new maximum bandwidth
        flowPatch = FlowPatch.builder().bandwidth(9000L).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new flag allocate protected path
        flowPatch = FlowPatch.builder().allocateProtectedPath(false).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // add diverse flow id
        flowPatch = FlowPatch.builder().diverseFlowId("diverse_flow_id").build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new ignore bandwidth flag
        flowPatch = FlowPatch.builder().ignoreBandwidth(true).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new encapsulation type
        flowPatch = FlowPatch.builder().encapsulationType(FlowEncapsulationType.VXLAN).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());

        // new path computation strategy
        flowPatch = FlowPatch.builder().pathComputationStrategy(PathComputationStrategy.LATENCY).build();
        result = flowOperationsService.prepareFlowUpdateResult(flowPatch, flow).build();
        assertTrue(result.isNeedUpdateFlow());
    }

    @Test
    public void getFlowsForEndpointNotReturnFlowsForOrphanedPaths() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        Switch switchB = createSwitch(SWITCH_ID_2);
        Switch switchC = createSwitch(SWITCH_ID_3);
        Switch switchD = createSwitch(SWITCH_ID_4);
        Flow flow = createFlow(FLOW_ID_1, switchA, 1, switchC, 2, FORWARD_PATH_1, REVERSE_PATH_1, switchB);
        createOrphanFlowPaths(flow, switchA, 1, switchC, 2, FORWARD_PATH_3, REVERSE_PATH_3, switchD);
        assertEquals(0, flowOperationsService.getFlowsForEndpoint(switchD.getSwitchId(), null).size());
    }

    @Test
    public void getFlowsForEndpointOneSwitchFlowNoPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        createFlow(FLOW_ID_1, switchA, 1, switchA, 2, FORWARD_PATH_1, REVERSE_PATH_1, null);

        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, null), FLOW_ID_1);

        createFlow(FLOW_ID_2, switchA, 3, switchA, 4, FORWARD_PATH_2, REVERSE_PATH_2, null);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, null), FLOW_ID_1, FLOW_ID_2);
    }

    @Test
    public void getFlowsForEndpointMultiSwitchFlowNoPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        Switch switchB = createSwitch(SWITCH_ID_2);
        createFlow(FLOW_ID_1, switchA, 1, switchB, 2, FORWARD_PATH_1, REVERSE_PATH_1, null);

        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, null), FLOW_ID_1);

        createFlow(FLOW_ID_2, switchB, 3, switchA, 4, FORWARD_PATH_2, REVERSE_PATH_2, null);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, null), FLOW_ID_1, FLOW_ID_2);
    }

    @Test
    public void getFlowsForEndpointTransitSwitchFlowNoPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        Switch switchB = createSwitch(SWITCH_ID_2);
        Switch switchC = createSwitch(SWITCH_ID_3);
        createFlow(FLOW_ID_1, switchA, 1, switchC, 2, FORWARD_PATH_1, REVERSE_PATH_1, switchB);

        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, null), FLOW_ID_1);

        createFlow(FLOW_ID_2, switchC, 3, switchA, 4, FORWARD_PATH_2, REVERSE_PATH_2, switchB);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, null), FLOW_ID_1, FLOW_ID_2);
    }

    @Test
    public void getFlowsForEndpointSeveralFlowNoPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        Switch switchB = createSwitch(SWITCH_ID_2);
        Switch switchC = createSwitch(SWITCH_ID_3);
        // one switch flow
        createFlow(FLOW_ID_1, switchB, 1, switchB, 2, FORWARD_PATH_1, REVERSE_PATH_1, null);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, null), FLOW_ID_1);

        // two switches flow
        createFlow(FLOW_ID_2, switchA, 3, switchB, 4, FORWARD_PATH_2, REVERSE_PATH_2, null);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, null), FLOW_ID_1, FLOW_ID_2);

        // three switches flow
        createFlow(FLOW_ID_3, switchA, 5, switchC, 6, FORWARD_PATH_3, REVERSE_PATH_3, switchB);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, null), FLOW_ID_1, FLOW_ID_2, FLOW_ID_3);
    }

    @Test
    public void getFlowsForEndpointOneSwitchFlowWithPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        createFlow(FLOW_ID_1, switchA, 1, switchA, 2, FORWARD_PATH_1, REVERSE_PATH_1, null);

        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, 1), FLOW_ID_1);

        // flow on different port
        createFlow(FLOW_ID_2, switchA, 3, switchA, 4, FORWARD_PATH_2, REVERSE_PATH_2, null);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, 1), FLOW_ID_1);
    }

    @Test
    public void getFlowsForEndpointMultiSwitchFlowWithPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        Switch switchB = createSwitch(SWITCH_ID_2);
        createFlow(FLOW_ID_1, switchA, 1, switchB, 2, FORWARD_PATH_1, REVERSE_PATH_1, null);

        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, 1), FLOW_ID_1);

        createFlow(FLOW_ID_2, switchB, 3, switchA, 1, FORWARD_PATH_2, REVERSE_PATH_2, null);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_1, 1), FLOW_ID_1, FLOW_ID_2);
    }

    @Test
    public void getFlowsForEndpointTransitSwitchFlowWithPortTest() throws SwitchNotFoundException {
        Switch switchA = createSwitch(SWITCH_ID_1);
        Switch switchB = createSwitch(SWITCH_ID_2);
        Switch switchC = createSwitch(SWITCH_ID_3);
        createFlow(FLOW_ID_1, switchA, 1, switchC, 2, FORWARD_PATH_1, REVERSE_PATH_1, switchB);

        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, 2), FLOW_ID_1);

        createFlow(FLOW_ID_2, switchC, 2, switchA, 4, FORWARD_PATH_2, REVERSE_PATH_2, switchB);
        assertFlows(flowOperationsService.getFlowsForEndpoint(SWITCH_ID_2, 2), FLOW_ID_1, FLOW_ID_2);
    }

    private void assertFlows(Collection<Flow> actualFlows, String... expectedFlowIds) {
        assertEquals(expectedFlowIds.length, actualFlows.size());
        assertEquals(new HashSet<>(Arrays.asList(expectedFlowIds)),
                actualFlows.stream().map(Flow::getFlowId).collect(Collectors.toSet()));
    }

    private Switch createSwitch(SwitchId switchId) {
        Switch sw = Switch.builder().switchId(switchId).status(SwitchStatus.ACTIVE).build();
        switchRepository.add(sw);
        return sw;
    }

    private void createOrphanFlowPaths(Flow flow, Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort,
                                       PathId forwardPartId, PathId reversePathId, Switch transitSwitch) {
        FlowPath forwardPath = FlowPath.builder()
                .pathId(forwardPartId)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, UNMASKED_COOKIE))
                .build();

        FlowPath reversePath = FlowPath.builder()
                .pathId(reversePathId)
                .srcSwitch(dstSwitch)
                .destSwitch(srcSwitch)
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, UNMASKED_COOKIE))
                .build();

        if (!srcSwitch.getSwitchId().equals(dstSwitch.getSwitchId())) {
            if (transitSwitch == null) {
                // direct paths between src and dst switches
                forwardPath.setSegments(newArrayList(createPathSegment(forwardPath.getPathId(),
                        srcSwitch, srcPort, dstSwitch, dstPort)));
                reversePath.setSegments(newArrayList(createPathSegment(reversePath.getPathId(),
                        dstSwitch, dstPort, srcSwitch, srcPort)));
            } else {
                // src switch ==> transit switch ==> dst switch
                forwardPath.setSegments(newArrayList(
                        createPathSegment(forwardPath.getPathId(), srcSwitch, srcPort, transitSwitch, srcPort),
                        createPathSegment(forwardPath.getPathId(), transitSwitch, dstPort, dstSwitch, dstPort)));
                reversePath.setSegments(newArrayList(
                        createPathSegment(reversePath.getPathId(), dstSwitch, dstPort, transitSwitch, dstPort),
                        createPathSegment(reversePath.getPathId(), transitSwitch, srcPort, srcSwitch, srcPort)));

            }
        }

        flowPathRepository.add(forwardPath);
        flowPathRepository.add(reversePath);

        flow.addPaths(forwardPath, reversePath);
    }

    private Flow createFlow(String flowId, Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort,
                            PathId forwardPartId, PathId reversePathId, Switch transitSwitch) {

        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .status(FlowStatus.UP)
                .build();


        FlowPath forwardPath = FlowPath.builder()
                .pathId(forwardPartId)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, UNMASKED_COOKIE))
                .build();

        FlowPath reversePath = FlowPath.builder()
                .pathId(reversePathId)
                .srcSwitch(dstSwitch)
                .destSwitch(srcSwitch)
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, UNMASKED_COOKIE))
                .build();

        if (!srcSwitch.getSwitchId().equals(dstSwitch.getSwitchId())) {
            if (transitSwitch == null) {
                // direct paths between src and dst switches
                forwardPath.setSegments(newArrayList(createPathSegment(forwardPath.getPathId(),
                        srcSwitch, srcPort, dstSwitch, dstPort)));
                reversePath.setSegments(newArrayList(createPathSegment(reversePath.getPathId(),
                        dstSwitch, dstPort, srcSwitch, srcPort)));
            } else {
                // src switch ==> transit switch ==> dst switch
                forwardPath.setSegments(newArrayList(
                        createPathSegment(forwardPath.getPathId(), srcSwitch, srcPort, transitSwitch, srcPort),
                        createPathSegment(forwardPath.getPathId(), transitSwitch, dstPort, dstSwitch, dstPort)));
                reversePath.setSegments(newArrayList(
                        createPathSegment(reversePath.getPathId(), dstSwitch, dstPort, transitSwitch, dstPort),
                        createPathSegment(reversePath.getPathId(), transitSwitch, srcPort, srcSwitch, srcPort)));

            }
        }

        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);
        flowRepository.add(flow);
        return flow;
    }

    private PathSegment createPathSegment(PathId pathId, Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort) {
        PathSegment pathSegment = PathSegment.builder()
                .pathId(pathId)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .build();
        pathSegmentRepository.add(pathSegment);
        return pathSegment;
    }

    private class FlowCarrierImpl implements FlowOperationsCarrier {
        @Override
        public void emitPeriodicPingUpdate(String flowId, boolean enabled) {

        }

        @Override
        public void sendUpdateRequest(FlowRequest request) {

        }

        @Override
        public void sendNorthboundResponse(InfoData data) {

        }
    }
}
