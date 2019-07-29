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

package org.openkilda.wfm.topology.nbworker.services;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.model.FlowDto;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.flow.TestFlowBuilder;
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
    public void shouldUpdateMaxLatencyAndPriorityFlowFields() throws FlowNotFoundException {
        String testFlowId = "flow_id";
        Long maxLatency = 555L;
        Integer priority = 777;
        PathComputationStrategy pathComputationStrategy = PathComputationStrategy.LATENCY;

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
                .status(FlowStatus.UP)
                .build();
        flowRepository.add(flow);

        FlowDto receivedFlow = FlowDto.builder()
                .flowId(testFlowId)
                .maxLatency(maxLatency)
                .priority(priority)
                .targetPathComputationStrategy(pathComputationStrategy)
                .build();

        Flow updatedFlow = flowOperationsService.updateFlow(null, receivedFlow);

        assertEquals(maxLatency, updatedFlow.getMaxLatency());
        assertEquals(priority, updatedFlow.getPriority());
        assertEquals(pathComputationStrategy, updatedFlow.getTargetPathComputationStrategy());

        receivedFlow = FlowDto.builder()
                .flowId(testFlowId)
                .build();
        updatedFlow = flowOperationsService.updateFlow(null, receivedFlow);

        assertEquals(maxLatency, updatedFlow.getMaxLatency());
        assertEquals(priority, updatedFlow.getPriority());
        assertEquals(pathComputationStrategy, updatedFlow.getTargetPathComputationStrategy());
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithChangedStrategyReason() {
        // given: FlowDto with COST strategy and Flow with MAX_LATENCY strategy
        String flowId = "test_flow_id";
        FlowDto flowDto = FlowDto.builder()
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

        // then: needRerouteFlow flag set to true and rerouteReason is "path computation strategy was changed"
        assertTrue(result.isNeedRerouteFlow());
        assertTrue(result.getRerouteReason().contains("path computation strategy was changed"));
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithChangedMaxLatencyReasonFirstCase() {
        // given: FlowDto with max latency and no strategy and Flow with MAX_LATENCY strategy and no max latency
        String flowId = "test_flow_id";
        FlowDto flowDto = FlowDto.builder()
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

        // then: needRerouteFlow flag set to true and rerouteReason is "max latency was changed"
        assertTrue(result.isNeedRerouteFlow());
        assertTrue(result.getRerouteReason().contains("max latency was changed"));
    }

    @Test
    public void shouldPrepareFlowUpdateResultWithChangedMaxLatencyReasonSecondCase() {
        // given: FlowDto with max latency and MAX_LATENCY strategy
        //        and Flow with MAX_LATENCY strategy and no max latency
        String flowId = "test_flow_id";
        FlowDto flowDto = FlowDto.builder()
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

        // then: needRerouteFlow flag set to true and rerouteReason is "max latency was changed"
        assertTrue(result.isNeedRerouteFlow());
        assertTrue(result.getRerouteReason().contains("max latency was changed"));
    }

    @Test
    public void shouldPrepareFlowUpdateResultShouldNotRerouteFirstCase() {
        // given: FlowDto with max latency and MAX_LATENCY strategy
        //        and Flow with MAX_LATENCY strategy and same max latency
        String flowId = "test_flow_id";
        FlowDto flowDto = FlowDto.builder()
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

        // then: needRerouteFlow flag set to false and no rerouteReason
        assertFalse(result.isNeedRerouteFlow());
        assertNull(result.getRerouteReason());
    }

    @Test
    public void shouldPrepareFlowUpdateResultShouldNotRerouteSecondCase() {
        // given: FlowDto with no max latency and no strategy
        //        and Flow with MAX_LATENCY strategy and max latency
        String flowId = "test_flow_id";
        FlowDto flowDto = FlowDto.builder()
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

        assertFalse(result.isNeedRerouteFlow());
        assertNull(result.getRerouteReason());
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
                .cookie(Cookie.buildForwardCookie(UNMASKED_COOKIE))
                .build();

        FlowPath reversePath = FlowPath.builder()
                .pathId(reversePathId)
                .srcSwitch(dstSwitch)
                .destSwitch(srcSwitch)
                .cookie(Cookie.buildReverseCookie(UNMASKED_COOKIE))
                .build();

        if (!srcSwitch.getSwitchId().equals(dstSwitch.getSwitchId())) {
            if (transitSwitch == null) {
                // direct paths between src and dst switches
                forwardPath.setSegments(newArrayList(createPathSegment(srcSwitch, srcPort, dstSwitch, dstPort)));
                reversePath.setSegments(newArrayList(createPathSegment(dstSwitch, dstPort, srcSwitch, srcPort)));
            } else {
                // src switch ==> transit switch ==> dst switch
                forwardPath.setSegments(newArrayList(
                        createPathSegment(srcSwitch, srcPort, transitSwitch, srcPort),
                        createPathSegment(transitSwitch, dstPort, dstSwitch, dstPort)));
                reversePath.setSegments(newArrayList(
                        createPathSegment(dstSwitch, dstPort, transitSwitch, dstPort),
                        createPathSegment(transitSwitch, srcPort, srcSwitch, srcPort)));

            }
        }

        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);
        flowRepository.add(flow);
        return flow;
    }

    private PathSegment createPathSegment(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort) {
        PathSegment pathSegment = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .build();
        pathSegmentRepository.add(pathSegment);
        return pathSegment;
    }
}
