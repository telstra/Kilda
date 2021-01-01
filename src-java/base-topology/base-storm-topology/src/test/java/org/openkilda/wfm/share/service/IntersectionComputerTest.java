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

package org.openkilda.wfm.share.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.payload.flow.OverlappingSegmentsStats;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.flow.TestFlowBuilder;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class IntersectionComputerTest {
    private static final SwitchId SWITCH_ID_A = new SwitchId("00:00:00:00:00:00:00:0A");
    private static final SwitchId SWITCH_ID_B = new SwitchId("00:00:00:00:00:00:00:0B");
    private static final SwitchId SWITCH_ID_C = new SwitchId("00:00:00:00:00:00:00:0C");
    private static final SwitchId SWITCH_ID_D = new SwitchId("00:00:00:00:00:00:00:0D");
    private static final SwitchId SWITCH_ID_E = new SwitchId("00:00:00:00:00:00:00:0E");

    private static final String FLOW_ID = "flow-id";
    private static final String FLOW_ID2 = "new-flow-id";

    private static final PathId PATH_ID = new PathId("old-path");
    private static final PathId PATH_ID_REVERSE = new PathId("old-path-reverse");
    private static final PathId NEW_PATH_ID = new PathId("new-path");
    private static final PathId NEW_PATH_ID_REVERSE = new PathId("new-path-reverse");

    private static final OverlappingSegmentsStats ZERO_STATS =
            new OverlappingSegmentsStats(0, 0, 0, 0);

    private Flow flow;
    private Flow flow2;

    @Before
    public void setup() {
        flow = new TestFlowBuilder(FLOW_ID)
                .srcSwitch(Switch.builder().switchId(SWITCH_ID_A).build())
                .destSwitch(Switch.builder().switchId(SWITCH_ID_B).build())
                .build();
        flow2 = new TestFlowBuilder(FLOW_ID2)
                .srcSwitch(Switch.builder().switchId(SWITCH_ID_A).build())
                .destSwitch(Switch.builder().switchId(SWITCH_ID_B).build())
                .build();
    }

    @Test
    public void noGroupIntersections() {
        List<FlowPath> paths = getFlowPaths();

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats();

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void noGroupIntersectionsInOneFlow() {
        List<FlowPath> paths = getFlowPaths();
        paths.addAll(getFlowPaths(NEW_PATH_ID, NEW_PATH_ID_REVERSE, flow));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats();

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNoIntersections() {
        List<FlowPath> paths = getFlowPaths();

        FlowPath path = FlowPath.builder()
                .pathId(NEW_PATH_ID)
                .srcSwitch(makeSwitch(SWITCH_ID_D))
                .destSwitch(makeSwitch(SWITCH_ID_E))
                .segments(Lists.newArrayList(
                        buildPathSegment(NEW_PATH_ID, SWITCH_ID_D, SWITCH_ID_E, 10, 10)))
                .build();
        flow.addPaths(path);

        FlowPath revPath = FlowPath.builder()
                .pathId(NEW_PATH_ID_REVERSE)
                .srcSwitch(makeSwitch(SWITCH_ID_E))
                .destSwitch(makeSwitch(SWITCH_ID_D))
                .segments(Lists.newArrayList(
                        buildPathSegment(NEW_PATH_ID_REVERSE, SWITCH_ID_E, SWITCH_ID_D, 10, 10)))
                .build();
        flow2.addPaths(revPath);
        paths.addAll(Lists.newArrayList(path, revPath));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNotIntersectPathInSameFlow() {
        List<FlowPath> paths = getFlowPaths();

        FlowPath newPath = FlowPath.builder()
                .pathId(NEW_PATH_ID)
                .srcSwitch(makeSwitch(SWITCH_ID_A))
                .destSwitch(makeSwitch(SWITCH_ID_D))
                .segments(Lists.newArrayList(
                        buildPathSegment(NEW_PATH_ID, SWITCH_ID_A, SWITCH_ID_D, 10, 10)))
                .build();
        flow.addPaths(newPath);
        paths.add(newPath);

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNotFailIfNoSegments() {
        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE,
                Collections.emptyList());
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void shouldNotFailIfNoIntersectionSegments() {
        List<FlowPath> paths = getFlowPaths();

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(ZERO_STATS, stats);
    }

    @Test
    public void switchIntersectionByPathId() {
        List<FlowPath> paths = getFlowPaths();

        FlowPath newPath = FlowPath.builder()
                .pathId(NEW_PATH_ID)
                .srcSwitch(makeSwitch(SWITCH_ID_A))
                .destSwitch(makeSwitch(SWITCH_ID_D))
                .segments(Lists.newArrayList(
                        buildPathSegment(NEW_PATH_ID, SWITCH_ID_A, SWITCH_ID_D, 10, 10)))
                .build();
        flow2.addPaths(newPath);
        paths.add(newPath);

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(new OverlappingSegmentsStats(0, 1, 0, 33), stats);
    }

    @Test
    public void partialIntersection() {
        List<FlowPath> paths = getFlowPaths();

        FlowPath newPath = FlowPath.builder()
                .pathId(NEW_PATH_ID)
                .srcSwitch(makeSwitch(SWITCH_ID_A))
                .destSwitch(makeSwitch(SWITCH_ID_B))
                .segments(Lists.newArrayList(
                        buildPathSegment(NEW_PATH_ID, SWITCH_ID_A, SWITCH_ID_B, 1, 1)))
                .build();
        flow2.addPaths(newPath);
        paths.add(newPath);

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(new OverlappingSegmentsStats(1, 2, 50, 66), stats);
    }

    @Test
    public void fullIntersection() {
        List<FlowPath> paths = getFlowPaths();
        paths.addAll(getFlowPaths(NEW_PATH_ID, NEW_PATH_ID_REVERSE, flow2));

        IntersectionComputer computer = new IntersectionComputer(FLOW_ID, PATH_ID, PATH_ID_REVERSE, paths);
        OverlappingSegmentsStats stats = computer.getOverlappingStats(NEW_PATH_ID, NEW_PATH_ID_REVERSE);

        assertEquals(new OverlappingSegmentsStats(2, 3, 100, 100), stats);
    }

    @Test
    public void isProtectedPathOverlapsPositive() {
        List<FlowPath> paths = getFlowPaths(PATH_ID, PATH_ID_REVERSE, flow);

        List<PathSegment> primarySegments = getFlowPathSegments(paths);
        List<PathSegment> protectedSegmets = Collections.singletonList(
                buildPathSegment(PATH_ID, SWITCH_ID_A, SWITCH_ID_B, 1, 1));

        assertTrue(IntersectionComputer.isProtectedPathOverlaps(primarySegments, protectedSegmets));
    }

    @Test
    public void isProtectedPathOverlapsNegative() {
        List<FlowPath> paths = getFlowPaths(PATH_ID, PATH_ID_REVERSE, flow);

        List<PathSegment> primarySegments = getFlowPathSegments(paths);
        List<PathSegment> protectedSegmets = Collections.singletonList(
                buildPathSegment(PATH_ID, SWITCH_ID_A, SWITCH_ID_C, 3, 3));

        assertFalse(IntersectionComputer.isProtectedPathOverlaps(primarySegments, protectedSegmets));
    }

    private List<PathSegment> getFlowPathSegments(List<FlowPath> paths) {
        return paths.stream()
                .flatMap(e -> e.getSegments().stream())
                .collect(Collectors.toList());
    }

    private List<FlowPath> getFlowPaths() {
        return getFlowPaths(PATH_ID, PATH_ID_REVERSE, flow);
    }

    private List<FlowPath> getFlowPaths(PathId pathId, PathId reversePathId, Flow flow) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_A).build();
        Switch dstSwitch = Switch.builder().switchId(SWITCH_ID_C).build();

        FlowPath path = FlowPath.builder()
                .pathId(pathId)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .segments(Lists.newArrayList(
                        buildPathSegment(pathId, SWITCH_ID_A, SWITCH_ID_B, 1, 1),
                        buildPathSegment(pathId, SWITCH_ID_B, SWITCH_ID_C, 2, 2)))
                .build();
        flow.addPaths(path);

        FlowPath revPath = FlowPath.builder()
                .pathId(reversePathId)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .segments(Lists.newArrayList(
                        buildPathSegment(pathId, SWITCH_ID_C, SWITCH_ID_B, 2, 2),
                        buildPathSegment(pathId, SWITCH_ID_B, SWITCH_ID_A, 1, 1)))
                .build();
        flow.addPaths(revPath);

        return Lists.newArrayList(path, revPath);
    }

    private PathSegment buildPathSegment(PathId pathId, SwitchId srcDpid, SwitchId dstDpid, int srcPort, int dstPort) {
        return PathSegment.builder()
                .pathId(pathId)
                .srcSwitch(makeSwitch(srcDpid))
                .destSwitch(makeSwitch(dstDpid))
                .srcPort(srcPort)
                .destPort(dstPort)
                .build();
    }

    private Switch makeSwitch(SwitchId switchId) {
        return Switch.builder().switchId(switchId).build();
    }
}
