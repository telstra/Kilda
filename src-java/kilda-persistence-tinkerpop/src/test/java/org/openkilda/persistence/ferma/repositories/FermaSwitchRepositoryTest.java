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
import org.openkilda.model.SwitchStatus;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

public class FermaSwitchRepositoryTest extends InMemoryGraphBasedTest {
    static final SwitchId TEST_SWITCH_ID_A = new SwitchId(1);
    static final SwitchId TEST_SWITCH_ID_B = new SwitchId(2);
    static final SwitchId TEST_SWITCH_ID_C = new SwitchId(3);
    static final String TEST_FLOW_ID_A = "test_flow_id_a";
    static final String TEST_FLOW_ID_B = "test_flow_id_b";

    SwitchRepository switchRepository;
    FlowRepository flowRepository;

    @Before
    public void setUp() {
        switchRepository = repositoryFactory.createSwitchRepository();
        flowRepository = repositoryFactory.createFlowRepository();
    }

    @Test
    public void shouldCreateSwitch() {
        switchRepository.add(Switch.builder()
                .switchId(TEST_SWITCH_ID_A)
                .description("Some description")
                .build());

        assertEquals(1, switchRepository.findAll().size());
    }

    @Test
    public void shouldFindActive() {
        Switch activeSwitch = Switch.builder().switchId(TEST_SWITCH_ID_A)
                .status(SwitchStatus.ACTIVE).build();
        Switch inactiveSwitch = Switch.builder().switchId(TEST_SWITCH_ID_B)
                .status(SwitchStatus.INACTIVE).build();

        switchRepository.add(activeSwitch);
        switchRepository.add(inactiveSwitch);

        Collection<Switch> switches = switchRepository.findActive();
        assertEquals(1, switches.size());
        assertEquals(TEST_SWITCH_ID_A, switches.iterator().next().getSwitchId());
    }

    @Test
    public void shouldFindSwitchById() {
        Switch origSwitch = Switch.builder()
                .switchId(TEST_SWITCH_ID_A)
                .description("Some description")
                .build();
        switchRepository.add(origSwitch);

        Switch foundSwitch = switchRepository.findById(TEST_SWITCH_ID_A).get();
        assertEquals(origSwitch.getDescription(), foundSwitch.getDescription());
    }

    @Test
    public void shouldFindSwitchesByFlowId() {
        createTwoFlows();

        Collection<SwitchId> switches = switchRepository.findSwitchesInFlowPathByFlowId(TEST_FLOW_ID_A).stream()
                .map(Switch::getSwitchId)
                .collect(Collectors.toList());

        assertEquals(2, switches.size());
        assertTrue(switches.contains(TEST_SWITCH_ID_A));
        assertTrue(switches.contains(TEST_SWITCH_ID_B));
        assertFalse(switches.contains(TEST_SWITCH_ID_C));
    }

    @Test
    public void shouldFindSwitchOfOneSwitchFlowByFlowId() {
        createOneSwitchFlow();

        Collection<SwitchId> switches = switchRepository.findSwitchesInFlowPathByFlowId(TEST_FLOW_ID_A).stream()
                .map(Switch::getSwitchId)
                .collect(Collectors.toList());

        assertEquals(1, switches.size());
        assertTrue(switches.contains(TEST_SWITCH_ID_A));
    }

    @Test
    public void shouldDeleteSwitch() {
        Switch origSwitch = Switch.builder()
                .switchId(TEST_SWITCH_ID_A)
                .description("Some description")
                .build();
        switchRepository.add(origSwitch);

        transactionManager.doInTransaction(() ->
                switchRepository.remove(origSwitch));

        assertEquals(0, switchRepository.findAll().size());
    }

    private void createOneSwitchFlow() {
        Switch switchA = createTestSwitch(TEST_SWITCH_ID_A.getId());

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_A)
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchA)
                .destPort(2)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_A + "_forward_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1L))
                .meterId(new MeterId(1))
                .srcSwitch(switchA)
                .destSwitch(switchA)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setForwardPath(forwardFlowPath);

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_A + "_reverse_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 1L))
                .meterId(new MeterId(2))
                .srcSwitch(switchA)
                .destSwitch(switchA)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setReversePath(reverseFlowPath);

        flowRepository.add(flow);
    }

    private void createTwoFlows() {
        Switch switchA = createTestSwitch(TEST_SWITCH_ID_A.getId());
        Switch switchB = createTestSwitch(TEST_SWITCH_ID_B.getId());

        //create flow TEST_FLOW_ID_A
        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_A)
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchB)
                .destPort(4)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_A + "_forward_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1L))
                .meterId(new MeterId(1))
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegment = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(switchA)
                .srcPort(2)
                .destSwitch(switchB)
                .destPort(3)
                .build();
        forwardFlowPath.setSegments(Collections.singletonList(forwardSegment));

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_A + "_reverse_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 1L))
                .meterId(new MeterId(2))
                .srcSwitch(switchB)
                .destSwitch(switchA)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegment = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(switchB)
                .srcPort(3)
                .destSwitch(switchA)
                .destPort(2)
                .build();
        reverseFlowPath.setSegments(Collections.singletonList(reverseSegment));

        flowRepository.add(flow);

        Switch switchC = createTestSwitch(TEST_SWITCH_ID_C.getId());

        //create flow TEST_FLOW_ID_B
        flow = Flow.builder()
                .flowId(TEST_FLOW_ID_B)
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchC)
                .destPort(7)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .build();

        forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_B + "_forward_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1L))
                .meterId(new MeterId(3))
                .srcSwitch(switchA)
                .destSwitch(switchC)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegmentA = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(switchA)
                .srcPort(2)
                .destSwitch(switchB)
                .destPort(3)
                .build();

        PathSegment forwardSegmentB = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(switchB)
                .srcPort(5)
                .destSwitch(switchC)
                .destPort(6)
                .build();
        forwardFlowPath.setSegments(Lists.newArrayList(forwardSegmentA, forwardSegmentB));

        reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_B + "_reverse_path"))
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 1L))
                .meterId(new MeterId(4))
                .srcSwitch(switchC)
                .destSwitch(switchA)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegmentA = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(switchC)
                .srcPort(6)
                .destSwitch(switchB)
                .destPort(5)
                .build();

        PathSegment reverseSegmentB = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(switchB)
                .srcPort(3)
                .destSwitch(switchA)
                .destPort(2)
                .build();
        reverseFlowPath.setSegments(Lists.newArrayList(reverseSegmentA, reverseSegmentB));

        flowRepository.add(flow);
    }
}
