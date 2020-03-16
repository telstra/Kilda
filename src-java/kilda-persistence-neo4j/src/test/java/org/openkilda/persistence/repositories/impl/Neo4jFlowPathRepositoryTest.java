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

package org.openkilda.persistence.repositories.impl;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.Cookie;
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

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class Neo4jFlowPathRepositoryTest extends Neo4jBasedTest {
    static final String TEST_FLOW_ID = "test_flow";
    static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);
    static final SwitchId TEST_SWITCH_C_ID = new SwitchId(3);

    static FlowPathRepository flowPathRepository;
    static FlowRepository flowRepository;
    static SwitchRepository switchRepository;

    private Switch switchA;
    private Switch switchB;
    private Switch switchC;
    private Flow flow;

    @BeforeClass
    public static void setUp() {
        flowRepository = new Neo4jFlowRepository(neo4jSessionFactory, txManager);
        flowPathRepository = new Neo4jFlowPathRepository(neo4jSessionFactory, txManager);
        switchRepository = new Neo4jSwitchRepository(neo4jSessionFactory, txManager);
    }

    @Before
    public void createSwitchesAndFlow() {
        switchA = buildTestSwitch(TEST_SWITCH_A_ID.getId());
        switchRepository.createOrUpdate(switchA);

        switchB = buildTestSwitch(TEST_SWITCH_B_ID.getId());
        switchRepository.createOrUpdate(switchB);

        switchC = buildTestSwitch(TEST_SWITCH_C_ID.getId());
        switchRepository.createOrUpdate(switchC);

        assertEquals(3, switchRepository.findAll().size());

        flow = Flow.builder()
                .flowId(TEST_FLOW_ID)
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchB)
                .destPort(2)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flowRepository.createOrUpdate(flow);
    }

    @Test
    public void shouldCreateFlowPaths() {
        Flow flowWithPaths = buildTestFlowPathPair();
        flowPathRepository.createOrUpdate(flowWithPaths.getForwardPath());
        flowPathRepository.createOrUpdate(flowWithPaths.getReversePath());

        Collection<FlowPath> allPaths = flowPathRepository.findAll();
        assertThat(allPaths, hasSize(2));

        FlowPath foundForwardPath = flowPathRepository.findById(flowWithPaths.getForwardPathId()).get();
        assertEquals(switchA.getSwitchId(), foundForwardPath.getSrcSwitch().getSwitchId());
        assertEquals(switchB.getSwitchId(), foundForwardPath.getDestSwitch().getSwitchId());

        Flow foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertThat(foundFlow.getPaths(), hasSize(2));

        assertEquals(foundFlow.getFlowId(), foundForwardPath.getFlow().getFlowId());
    }

    @Test
    public void shouldCreateFlowWithPaths() {
        Flow flowWithPaths = buildTestFlowPathPair();
        flowRepository.createOrUpdate(flowWithPaths);

        Collection<FlowPath> allPaths = flowPathRepository.findAll();
        assertThat(allPaths, hasSize(2));

        FlowPath foundForwardPath = flowPathRepository.findById(flowWithPaths.getForwardPathId()).get();
        assertEquals(switchA.getSwitchId(), foundForwardPath.getSrcSwitch().getSwitchId());
        assertEquals(switchB.getSwitchId(), foundForwardPath.getDestSwitch().getSwitchId());

        Flow foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertThat(foundFlow.getPaths(), hasSize(2));

        assertEquals(foundFlow.getFlowId(), foundForwardPath.getFlow().getFlowId());
    }

    @Test
    public void shouldFlowPathUpdateKeepRelations() {
        Flow flowWithPaths = buildTestFlowPathPair();
        flowPathRepository.createOrUpdate(flowWithPaths.getForwardPath());
        flowPathRepository.createOrUpdate(flowWithPaths.getReversePath());

        Flow foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertThat(foundFlow.getPaths(), hasSize(2));

        FlowPath foundPath = flowPathRepository.findById(flowWithPaths.getForwardPathId()).get();
        foundPath.setStatus(FlowPathStatus.INACTIVE);
        flowPathRepository.createOrUpdate(foundPath);

        foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertThat(foundFlow.getPaths(), hasSize(2));
    }

    @Test
    public void shouldFlowPathUpdateKeepFlowRelations() {
        Flow flowWithPaths = buildTestFlowPathPair();
        flowRepository.createOrUpdate(flowWithPaths);

        Flow foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertThat(foundFlow.getPaths(), hasSize(2));

        FlowPath flowPath = foundFlow.getPaths().stream()
                .filter(path -> path.getPathId().equals(flowWithPaths.getReversePathId()))
                .findAny().get();
        flowPath.setStatus(FlowPathStatus.INACTIVE);
        flowPathRepository.createOrUpdate(flowPath);

        foundFlow = flowRepository.findById(TEST_FLOW_ID).get();
        assertThat(foundFlow.getPaths(), hasSize(2));
    }

    @Test
    public void shouldDeleteFlowPath() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        flowPathRepository.delete(flowPath);

        assertEquals(0, flowPathRepository.findAll().size());
    }

    @Test
    public void shouldDeleteFoundFlowPath() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> allPaths = flowPathRepository.findAll();
        FlowPath foundPath = allPaths.iterator().next();
        flowPathRepository.delete(foundPath);

        assertEquals(0, flowPathRepository.findAll().size());
    }

    @Test
    public void shouldFindPathById() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Optional<FlowPath> foundPath = flowPathRepository.findById(flowPath.getPathId());
        assertTrue(foundPath.isPresent());
    }

    @Test
    public void shouldFindPathByFlowIdAndCookie() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Optional<FlowPath> foundPath = flowPathRepository.findByFlowIdAndCookie(TEST_FLOW_ID, flowPath.getCookie());
        assertTrue(foundPath.isPresent());
    }

    @Test
    public void shouldFindByEndpointSwitch() {
        Flow flow = buildTestFlowPathPair();

        flowRepository.createOrUpdate(flow);

        Collection<FlowPath> paths = flowPathRepository.findByEndpointSwitch(switchA.getSwitchId());
        assertThat(paths, containsInAnyOrder(flow.getForwardPath(), flow.getReversePath()));
    }

    @Test
    public void shouldNotFindProtectedIngressByEndpointSwitch() {
        Flow flow = buildTestFlowPathPair();
        FlowPath protect = buildFlowPath(flow, "_protectedpath", 10, 10, switchA, switchB);
        flow.setProtectedForwardPath(protect);

        flowRepository.createOrUpdate(flow);

        Collection<FlowPath> paths = flowPathRepository.findByEndpointSwitch(switchA.getSwitchId());
        assertThat(paths, containsInAnyOrder(flow.getForwardPath(), flow.getReversePath()));
    }

    @Test
    public void shouldFindProtectedPathsByEndpointSwitchIncludeProtected() {
        Flow flow = buildTestFlowPathPair();
        flow.setProtectedForwardPath(buildFlowPath(flow, "_forward_protected", 10, 10, switchA, switchB));
        flow.setProtectedReversePath(buildFlowPath(flow, "_reverse_protected", 11, 11, switchB, switchA));

        flowRepository.createOrUpdate(flow);

        Collection<FlowPath> paths = flowPathRepository.findByEndpointSwitchIncludeProtected(switchA.getSwitchId());
        assertThat(paths, containsInAnyOrder(flow.getForwardPath(), flow.getReversePath(),
                flow.getProtectedForwardPath(), flow.getProtectedReversePath()));
    }

    @Test
    public void shouldFindProtectedPathsBySrcSwitchIncludeProtected() {
        Flow flow = buildTestFlowPathPair();
        flow.setProtectedForwardPath(buildFlowPath(flow, "_forward_protected", 10, 10, switchA, switchB));
        flow.setProtectedReversePath(buildFlowPath(flow, "_reverse_protected", 11, 11, switchB, switchA));

        flowRepository.createOrUpdate(flow);

        assertThat(flowPathRepository.findBySrcSwitchIncludeProtected(switchA.getSwitchId()),
                containsInAnyOrder(flow.getForwardPath(), flow.getProtectedForwardPath()));
        assertThat(flowPathRepository.findBySrcSwitchIncludeProtected(switchB.getSwitchId()),
                containsInAnyOrder(flow.getReversePath(), flow.getProtectedReversePath()));
    }

    @Test
    public void shouldFindBySrcSwitch() {
        Flow flow = buildTestFlowPathPair();
        flowRepository.createOrUpdate(flow);

        Collection<FlowPath> paths = flowPathRepository.findBySrcSwitch(switchA.getSwitchId());
        assertThat(paths, containsInAnyOrder(flow.getForwardPath()));
    }

    @Test
    public void shouldFindFlowPathsForIsl() {
        FlowPath path = buildTestFlowPathWithIntermediate(switchC, 100);
        flowPathRepository.createOrUpdate(path);

        Collection<FlowPath> paths = flowPathRepository.findWithPathSegment(switchA.getSwitchId(), 1,
                switchC.getSwitchId(), 100);
        assertThat(paths, Matchers.hasSize(1));
        assertThat(paths, containsInAnyOrder(flow.getForwardPath()));
    }

    @Test
    public void shouldFindActiveAffectedPaths() {
        FlowPath flowPath = buildTestFlowPathWithIntermediate(switchC, 100);
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> paths = flowPathRepository.findBySegmentEndpoint(
                switchC.getSwitchId(), 100);
        assertThat(paths, containsInAnyOrder(flowPath));
    }

    @Test
    public void shouldFindPathByFlowId() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findByFlowId(TEST_FLOW_ID);
        assertThat(foundPaths, hasSize(1));
    }

    @Test
    public void shouldFindPathBySrc() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findBySrcSwitch(switchA.getSwitchId());
        assertThat(foundPaths, hasSize(1));
    }

    @Test
    public void shouldNotFindPathByWrongSrc() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findBySrcSwitch(switchB.getSwitchId());
        assertThat(foundPaths, Matchers.empty());
    }

    @Test
    public void shouldFindPathByEndpointSwitch() {
        FlowPath flowPath = buildTestFlowPath();
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findByEndpointSwitch(switchB.getSwitchId());
        assertThat(foundPaths, hasSize(1));
    }

    @Test
    public void shouldFindPathBySegmentSwitch() {
        FlowPath flowPath = buildTestFlowPathWithIntermediate(switchC, 100);
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findBySegmentSwitch(switchC.getSwitchId());
        assertThat(foundPaths, hasSize(1));
    }

    @Test
    public void shouldFindInactivePathBySegmentSwitch() {
        Flow activeFlow = Flow.builder()
                .flowId("active flow")
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchB)
                .destPort(2)
                .status(FlowStatus.UP)
                .build();
        flowRepository.createOrUpdate(activeFlow);
        FlowPath activeFlowPath = buildFlowPath(activeFlow, "active", 100L, 200L, switchA, switchB);
        activeFlowPath.getFlow().setStatus(FlowStatus.DOWN);
        FlowPath expectedFlowPath = buildTestFlowPathWithIntermediate(switchC, 100);
        expectedFlowPath.getFlow().setStatus(FlowStatus.DOWN);
        flowPathRepository.createOrUpdate(activeFlowPath);
        flowPathRepository.createOrUpdate(expectedFlowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findInactiveBySegmentSwitch(switchA.getSwitchId());
        assertThat(foundPaths, hasSize(1));
        FlowPath actualFlowPath = foundPaths.stream().findFirst().orElse(null);
        assertEquals(expectedFlowPath, actualFlowPath);
    }

    @Test
    public void shouldFindPathBySegmentDestSwitch() {
        FlowPath flowPath = buildTestFlowPathWithIntermediate(switchC, 100);
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findBySegmentDestSwitch(switchC.getSwitchId());
        assertThat(foundPaths, hasSize(1));
    }

    @Test
    public void shouldNotFindPathByWrongSegmentDestSwitch() {
        FlowPath flowPath = buildTestFlowPathWithIntermediate(switchC, 100);
        flowPathRepository.createOrUpdate(flowPath);

        Collection<FlowPath> foundPaths = flowPathRepository.findBySegmentDestSwitch(switchA.getSwitchId());
        assertThat(foundPaths, Matchers.empty());
    }

    @Test
    public void shouldKeepSegmentsOrdered() {
        FlowPath flowPath = buildTestFlowPath();

        List<PathSegment> segments = asList(PathSegment.builder()
                        .srcSwitch(switchA)
                        .destSwitch(switchC)
                        .build(),
                PathSegment.builder()
                        .srcSwitch(switchC)
                        .destSwitch(switchB)
                        .build());
        flowPath.setSegments(segments);

        flowPathRepository.createOrUpdate(flowPath);

        Optional<FlowPath> foundPath = flowPathRepository.findById(flowPath.getPathId());
        assertEquals(foundPath.get().getSegments().get(0).getDestSwitch().getSwitchId(), switchC.getSwitchId());
    }

    private FlowPath buildTestFlowPath() {
        FlowPath flowPath = buildFlowPath(flow, "_path", 1, 1, switchA, switchB);

        flow.setForwardPath(flowPath);

        return flowPath;
    }

    private Flow buildTestFlowPathPair() {
        FlowPath forwardPath = buildFlowPath(flow, "_forward", 1, 1, switchA, switchB);
        flow.setForwardPath(forwardPath);

        FlowPath reversePath = buildFlowPath(flow, "_reverse", 2, 2, switchB, switchA);
        flow.setReversePath(reversePath);

        return flow;
    }

    private FlowPath buildFlowPath(
            Flow flow, String suffixName, long cookie, long meterId, Switch srcSwitch, Switch dstSwitch) {
        return FlowPath.builder()
                .pathId(new PathId(flow.getFlowId() + suffixName))
                .flow(flow)
                .cookie(new Cookie(cookie))
                .meterId(new MeterId(meterId))
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .status(FlowPathStatus.ACTIVE)
                .segments(Collections.emptyList())
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
    }

    private FlowPath buildTestFlowPathWithIntermediate(Switch intSwitch, int intPort) {
        FlowPath flowPath = FlowPath.builder()
                .pathId(new PathId(flow.getFlowId() + "_forward_path"))
                .flow(flow)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(1L, FlowPathDirection.FORWARD))
                .meterId(new MeterId(1))
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();

        flow.setForwardPath(flowPath);

        PathSegment segment1 = PathSegment.builder()
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(intSwitch)
                .destPort(intPort)
                .build();
        PathSegment segment2 = PathSegment.builder()
                .srcSwitch(intSwitch)
                .srcPort(intPort + 100)
                .destSwitch(switchB)
                .destPort(2)
                .build();
        flowPath.setSegments(asList(segment1, segment2));

        return flowPath;
    }
}
