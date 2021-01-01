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

package org.openkilda.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.cookie.FlowSegmentCookie;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PathSegmentTest {

    private static final SwitchId SWITCH_ID_A = new SwitchId(1L);
    private static final Switch SWITCH_A = Switch.builder().switchId(SWITCH_ID_A).build();
    private static final SwitchId SWITCH_ID_B = new SwitchId(2L);
    private static final Switch SWITCH_B = Switch.builder().switchId(SWITCH_ID_B).build();
    private static final String FLOW_ID = "TEST_FLOW";

    private static final SwitchId SWITCH_ID_C = new SwitchId(3L);


    private Flow flow;

    @Before
    public void setup() {
        flow = Flow.builder().flowId(FLOW_ID).srcSwitch(SWITCH_A)
                .destSwitch(SWITCH_B).pinned(true).build();
        PathId forwardPathId = new PathId("1");
        FlowPath flowForwardPath = FlowPath.builder().pathId(forwardPathId)
                .srcSwitch(SWITCH_A).destSwitch(SWITCH_B)
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1))
                .build();
        List<PathSegment> flowForwardSegments = new ArrayList<>();
        flowForwardSegments.add(PathSegment.builder()
                .pathId(forwardPathId)
                .srcSwitch(SWITCH_A)
                .srcPort(1)
                .destSwitch(SWITCH_B)
                .destPort(1)
                .build());
        flowForwardPath.setSegments(flowForwardSegments);

        PathId reversePathId = new PathId("2");
        FlowPath flowReversePath = FlowPath.builder().pathId(reversePathId)
                .srcSwitch(SWITCH_B).destSwitch(SWITCH_A)
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 2)).build();
        List<PathSegment> flowReverseSegments = new ArrayList<>();

        flowReverseSegments.add(PathSegment.builder()
                .pathId(reversePathId)
                .srcSwitch(SWITCH_B)
                .srcPort(1)
                .destSwitch(SWITCH_A)
                .destPort(1)
                .build());
        flowReversePath.setSegments(flowReverseSegments);
        flow.setForwardPath(flowForwardPath);
        flow.setReversePath(flowReversePath);
    }


    @Test(expected = IllegalArgumentException.class)
    public void testContainsNodeInvalidInput() {
        flow.getForwardPath().getSegments().get(0).containsNode(null, 1);
    }

    @Test
    public void testContainsNodeSourceMatch() {
        assertTrue(flow.getForwardPath().getSegments().get(0).containsNode(SWITCH_ID_A, 1));
    }

    @Test
    public void testContainsNodeDestinationMatch() {
        assertTrue(flow.getForwardPath().getSegments().get(0).containsNode(SWITCH_ID_B, 1));
    }

    @Test
    public void testContainsNodeNoSwitchMatch() {
        assertFalse(flow.getForwardPath().getSegments().get(0).containsNode(SWITCH_ID_C, 1));
    }

    @Test
    public void testContainsNodeNoPortMatch() {
        assertFalse(flow.getForwardPath().getSegments().get(0).containsNode(SWITCH_ID_B, 2));
    }

    @Test
    public void testContainsNodeNoSwitchPortMatch() {
        assertFalse(flow.getForwardPath().getSegments().get(0).containsNode(SWITCH_ID_C, 2));
    }
}
