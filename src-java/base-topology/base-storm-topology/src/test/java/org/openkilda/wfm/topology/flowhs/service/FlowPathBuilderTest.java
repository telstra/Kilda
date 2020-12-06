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

package org.openkilda.wfm.topology.flowhs.service;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.pce.Path;
import org.openkilda.pce.Path.Segment;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

public class FlowPathBuilderTest {
    private FlowPathBuilder builder;

    @Before
    public void setUp() {
        SwitchRepository switchRepository = mock(SwitchRepository.class);
        SwitchPropertiesRepository switchPropertiesRepository = mock(SwitchPropertiesRepository.class);
        when(switchRepository.findById(any())).thenAnswer(invocation ->
                Optional.of(Switch.builder().switchId(invocation.getArgument(0)).build()));
        when(switchPropertiesRepository.findBySwitchId(any())).thenAnswer(invocation -> {
            Switch sw = Switch.builder().switchId(invocation.getArgument(0)).build();
            return Optional.of(SwitchProperties.builder().switchObj(sw).multiTable(false)
                    .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES).build());
        });
        builder = new FlowPathBuilder(switchPropertiesRepository);
    }

    @Test
    public void shouldDetectSameSwitchPaths() {
        SwitchId switchId = new SwitchId(1);
        Switch switchEntity = Switch.builder().switchId(switchId).build();

        Path path = Path.builder()
                .srcSwitchId(switchId)
                .destSwitchId(switchId)
                .segments(Collections.emptyList())
                .build();

        FlowPath flowPath = FlowPath.builder()
                .srcSwitch(switchEntity)
                .destSwitch(switchEntity)
                .pathId(new PathId("test_path_id"))
                .build();

        assertTrue(builder.isSamePath(path, flowPath));
    }

    @Test
    public void shouldDetectNotSameSwitchPaths() {
        SwitchId switchId1 = new SwitchId(1);

        SwitchId switchId2 = new SwitchId(2);
        Switch switch2 = Switch.builder().switchId(switchId2).build();

        Path path = Path.builder()
                .srcSwitchId(switchId1)
                .destSwitchId(switchId1)
                .segments(Collections.emptyList())
                .build();

        FlowPath flowPath = FlowPath.builder()
                .srcSwitch(switch2)
                .destSwitch(switch2)
                .pathId(new PathId("test_path_id"))
                .build();

        assertFalse(builder.isSamePath(path, flowPath));
    }

    @Test
    public void shouldDetectSame2SwitchPaths() {
        SwitchId switchId1 = new SwitchId(1);
        Switch switch1 = Switch.builder().switchId(switchId1).build();

        SwitchId switchId2 = new SwitchId(2);
        Switch switch2 = Switch.builder().switchId(switchId2).build();

        Path path = Path.builder()
                .srcSwitchId(switchId1)
                .destSwitchId(switchId2)
                .segments(Collections.singletonList(Segment.builder()
                        .srcSwitchId(switchId1)
                        .srcPort(1)
                        .destSwitchId(switchId2)
                        .destPort(2)
                        .build()))
                .build();

        PathId flowPathId = new PathId("test_path_id");
        FlowPath flowPath = FlowPath.builder()
                .srcSwitch(switch1)
                .destSwitch(switch2)
                .pathId(flowPathId)
                .segments(Collections.singletonList(PathSegment.builder()
                        .pathId(flowPathId)
                        .srcSwitch(switch1).srcPort(1).destSwitch(switch2).destPort(2).build()))
                .build();

        assertTrue(builder.isSamePath(path, flowPath));
    }

    @Test
    public void shouldDetectDifferenceInPortsFor2SwitchPaths() {
        SwitchId switchId1 = new SwitchId(1);
        Switch switch1 = Switch.builder().switchId(switchId1).build();

        SwitchId switchId2 = new SwitchId(2);
        Switch switch2 = Switch.builder().switchId(switchId2).build();

        Path path = Path.builder()
                .srcSwitchId(switchId1)
                .destSwitchId(switchId2)
                .segments(Collections.singletonList(Segment.builder()
                        .srcSwitchId(switchId1)
                        .srcPort(1)
                        .destSwitchId(switchId2)
                        .destPort(2)
                        .build()))
                .build();

        PathId flowPathId = new PathId("test_path_id");
        FlowPath flowPath = FlowPath.builder()
                .srcSwitch(switch1)
                .destSwitch(switch2)
                .pathId(flowPathId)
                .segments(Collections.singletonList(PathSegment.builder()
                        .pathId(flowPathId)
                        .srcSwitch(switch1).srcPort(2).destSwitch(switch2).destPort(3).build()))
                .build();

        assertFalse(builder.isSamePath(path, flowPath));
    }

    @Test
    public void shouldDetectSame3SwitchPaths() {
        SwitchId switchId1 = new SwitchId(1);
        Switch switch1 = Switch.builder().switchId(switchId1).build();

        SwitchId switchId2 = new SwitchId(2);
        Switch switch2 = Switch.builder().switchId(switchId2).build();

        SwitchId switchId3 = new SwitchId(3);
        Switch switch3 = Switch.builder().switchId(switchId3).build();

        Path path = Path.builder()
                .srcSwitchId(switchId1)
                .destSwitchId(switchId2)
                .segments(asList(Segment.builder()
                        .srcSwitchId(switchId1)
                        .srcPort(1)
                        .destSwitchId(switchId3)
                        .destPort(2)
                        .build(), Segment.builder()
                        .srcSwitchId(switchId3)
                        .srcPort(1)
                        .destSwitchId(switchId2)
                        .destPort(2)
                        .build()))
                .build();

        PathId flowPathId = new PathId("test_path_id");
        FlowPath flowPath = FlowPath.builder()
                .srcSwitch(switch1)
                .destSwitch(switch2)
                .pathId(flowPathId)
                .segments(asList(
                        PathSegment.builder().pathId(flowPathId)
                                .srcSwitch(switch1).srcPort(1).destSwitch(switch3).destPort(2).build(),
                        PathSegment.builder().pathId(flowPathId)
                                .srcSwitch(switch3).srcPort(1).destSwitch(switch2).destPort(2).build()))
                .build();

        assertTrue(builder.isSamePath(path, flowPath));
    }

    @Test
    public void shouldBuildFlowPathFor1SwitchPath() {
        SwitchId switchId = new SwitchId(1);

        Path path = Path.builder()
                .srcSwitchId(switchId)
                .destSwitchId(switchId)
                .segments(Collections.emptyList())
                .build();

        Flow flow = Flow.builder()
                .flowId("test_flow")
                .srcSwitch(Switch.builder().switchId(switchId).build())
                .destSwitch(Switch.builder().switchId(switchId).build())
                .build();
        PathId pathId = new PathId("test_path_id");
        MeterId meterId = new MeterId(MeterId.MIN_FLOW_METER_ID);
        PathResources pathResources = PathResources.builder()
                .pathId(pathId)
                .meterId(meterId)
                .build();
        FlowSegmentCookie cookie = new FlowSegmentCookie(FlowPathDirection.FORWARD, 1);

        FlowPath flowPath = builder.buildFlowPath(flow, pathResources, path, cookie, false);

        assertEquals(pathId, flowPath.getPathId());
        assertEquals(meterId, flowPath.getMeterId());
        assertEquals(cookie, flowPath.getCookie());
        assertThat(flowPath.getSegments(), empty());
    }

    @Test
    public void shouldBuildFlowPathFor2SwitchPath() {
        SwitchId switchId1 = new SwitchId(1);
        SwitchId switchId2 = new SwitchId(2);

        Path path = Path.builder()
                .srcSwitchId(switchId1)
                .destSwitchId(switchId2)
                .segments(Collections.singletonList(Segment.builder()
                        .srcSwitchId(switchId1)
                        .srcPort(1)
                        .destSwitchId(switchId2)
                        .destPort(2)
                        .build()))
                .build();

        Flow flow = Flow.builder()
                .flowId("test_flow")
                .srcSwitch(Switch.builder().switchId(switchId1).build())
                .destSwitch(Switch.builder().switchId(switchId2).build())
                .build();
        PathId pathId = new PathId("test_path_id");
        MeterId meterId = new MeterId(MeterId.MIN_FLOW_METER_ID);
        PathResources pathResources = PathResources.builder()
                .pathId(pathId)
                .meterId(meterId)
                .build();
        FlowSegmentCookie cookie = new FlowSegmentCookie(FlowPathDirection.FORWARD, 1);

        FlowPath flowPath = builder.buildFlowPath(flow, pathResources, path, cookie, false);

        assertEquals(switchId1, flowPath.getSrcSwitchId());
        assertEquals(switchId2, flowPath.getDestSwitchId());
        assertEquals(pathId, flowPath.getPathId());
        assertEquals(meterId, flowPath.getMeterId());
        assertEquals(cookie, flowPath.getCookie());
        assertThat(flowPath.getSegments(), hasSize(1));
    }

    @Test
    public void shouldBuildFlowPathFor3SwitchPath() {
        SwitchId switchId1 = new SwitchId(1);
        SwitchId switchId2 = new SwitchId(2);
        SwitchId switchId3 = new SwitchId(3);

        Path path = Path.builder()
                .srcSwitchId(switchId1)
                .destSwitchId(switchId2)
                .segments(asList(Segment.builder()
                        .srcSwitchId(switchId1)
                        .srcPort(1)
                        .destSwitchId(switchId3)
                        .destPort(2)
                        .build(), Segment.builder()
                        .srcSwitchId(switchId3)
                        .srcPort(1)
                        .destSwitchId(switchId2)
                        .destPort(2)
                        .build()))
                .build();

        Flow flow = Flow.builder()
                .flowId("test_flow")
                .srcSwitch(Switch.builder().switchId(switchId1).build())
                .destSwitch(Switch.builder().switchId(switchId2).build())
                .build();
        PathId pathId = new PathId("test_path_id");
        MeterId meterId = new MeterId(MeterId.MIN_FLOW_METER_ID);
        PathResources pathResources = PathResources.builder()
                .pathId(pathId)
                .meterId(meterId)
                .build();
        FlowSegmentCookie cookie = new FlowSegmentCookie(FlowPathDirection.FORWARD, 1);

        FlowPath flowPath = builder.buildFlowPath(flow, pathResources, path, cookie, false);

        assertEquals(switchId1, flowPath.getSrcSwitchId());
        assertEquals(switchId2, flowPath.getDestSwitchId());
        assertEquals(pathId, flowPath.getPathId());
        assertEquals(meterId, flowPath.getMeterId());
        assertEquals(cookie, flowPath.getCookie());
        assertThat(flowPath.getSegments(), hasSize(2));
    }
}
