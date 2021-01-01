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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import com.google.common.collect.Lists;
import junit.framework.AssertionFailedError;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class FermaIslRepositoryTest extends InMemoryGraphBasedTest {
    static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);
    static final String TEST_FLOW_ID = "test_flow";

    IslRepository islRepository;
    SwitchRepository switchRepository;
    SwitchPropertiesRepository switchPropertiesRepository;
    FlowRepository flowRepository;
    FlowPathRepository flowPathRepository;

    Switch switchA;
    Switch switchB;

    @Before
    public void setUp() {
        islRepository = repositoryFactory.createIslRepository();
        switchRepository = repositoryFactory.createSwitchRepository();
        flowRepository = repositoryFactory.createFlowRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();

        switchA = createTestSwitch(1);
        SwitchProperties switchAFeatures = SwitchProperties.builder()
                .switchObj(switchA)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                .build();
        switchPropertiesRepository.add(switchAFeatures);

        switchB = createTestSwitch(2);
        SwitchProperties switchBFeatures = SwitchProperties.builder()
                .switchObj(switchB)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                .build();
        switchPropertiesRepository.add(switchBFeatures);
        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldCreateIsl() {
        Isl isl = Isl.builder().srcSwitch(switchA).destSwitch(switchB).build();

        islRepository.add(isl);

        assertEquals(1, islRepository.findAll().size());
    }

    @Test
    public void shouldCreateSwitchAlongWithIsl() {
        Isl isl = Isl.builder().srcSwitch(switchA).destSwitch(switchB).build();

        islRepository.add(isl);

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldFindIslByEndpoint() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).destPort(112).build();

        islRepository.add(isl);

        List<Isl> foundIsls = Lists.newArrayList(islRepository.findByEndpoint(TEST_SWITCH_A_ID, 111));
        assertEquals(1, foundIsls.size());
        assertEquals(switchA.getSwitchId(), foundIsls.get(0).getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), foundIsls.get(0).getDestSwitchId());

        foundIsls = Lists.newArrayList(islRepository.findByEndpoint(TEST_SWITCH_B_ID, 112));
        assertEquals(1, foundIsls.size());
        assertEquals(switchA.getSwitchId(), foundIsls.get(0).getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), foundIsls.get(0).getDestSwitchId());
    }

    @Test
    public void shouldFindIslBySrcEndpoint() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).build();

        islRepository.add(isl);

        List<Isl> foundIsls = Lists.newArrayList(islRepository.findBySrcEndpoint(TEST_SWITCH_A_ID, 111));
        assertEquals(1, foundIsls.size());
        assertEquals(switchA.getSwitchId(), foundIsls.get(0).getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), foundIsls.get(0).getDestSwitchId());
    }

    @Test
    public void shouldFindIslByDestEndpoint() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).destPort(112).build();

        islRepository.add(isl);

        List<Isl> foundIsls = Lists.newArrayList(islRepository.findByDestEndpoint(TEST_SWITCH_B_ID, 112));
        assertEquals(1, foundIsls.size());
        assertEquals(switchA.getSwitchId(), foundIsls.get(0).getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), foundIsls.get(0).getDestSwitchId());
    }

    @Test
    public void shouldFindIslByEndpoints() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).destPort(112).build();

        islRepository.add(isl);

        Isl foundIsl = islRepository.findByEndpoints(TEST_SWITCH_A_ID, 111, TEST_SWITCH_B_ID, 112).get();
        assertEquals(switchA.getSwitchId(), foundIsl.getSrcSwitchId());
        assertEquals(switchB.getSwitchId(), foundIsl.getDestSwitchId());
    }

    @Test
    public void shouldSkipInactiveIsl() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).status(IslStatus.INACTIVE).build();

        islRepository.add(isl);

        List<Isl> foundIsl = Lists.newArrayList(islRepository.findAllActive());
        assertThat(foundIsl, Matchers.empty());
    }

    @Test
    public void shouldSkipInactiveIslSwitch() {
        switchA.setStatus(SwitchStatus.INACTIVE);

        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).status(IslStatus.ACTIVE).build();

        islRepository.add(isl);

        List<Isl> foundIsl = Lists.newArrayList(islRepository.findAllActive());
        assertThat(foundIsl, Matchers.empty());
    }

    @Test
    public void shouldFindActiveIsl() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(111)
                .destSwitch(switchB).status(IslStatus.ACTIVE).build();

        islRepository.add(isl);

        List<Isl> foundIsl = Lists.newArrayList(islRepository.findAllActive());
        assertThat(foundIsl, Matchers.hasSize(1));
    }

    @Test
    public void shouldFindActiveIslWithAvailableBandwidth() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .status(IslStatus.ACTIVE).availableBandwidth(101).build();

        islRepository.add(isl);

        List<Isl> foundIsl = Lists.newArrayList(islRepository.findActiveWithAvailableBandwidth(100,
                FlowEncapsulationType.TRANSIT_VLAN));
        assertThat(foundIsl, Matchers.hasSize(1));
    }

    @Test
    public void shouldSkipIslWithNoEnoughBandwidth() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .status(IslStatus.ACTIVE).availableBandwidth(99).build();

        islRepository.add(isl);

        List<Isl> foundIsl = Lists.newArrayList(islRepository.findActiveWithAvailableBandwidth(100,
                FlowEncapsulationType.TRANSIT_VLAN));
        assertThat(foundIsl, Matchers.hasSize(0));
    }

    @Test
    public void shouldFindIslOccupiedByFlowWithAvailableBandwidth() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(2)
                .status(IslStatus.ACTIVE).availableBandwidth(101).build();

        islRepository.add(isl);

        Flow flow = createFlowWithPath(0, 0);
        PathId pathId = flow.getPaths().stream()
                .filter(path -> path.getSegments().get(0).getSrcPort() == isl.getSrcPort())
                .findAny().map(FlowPath::getPathId).orElseThrow(AssertionFailedError::new);

        List<Isl> foundIsls = Lists.newArrayList(
                islRepository.findActiveAndOccupiedByFlowPathWithAvailableBandwidth(
                        pathId, 100, FlowEncapsulationType.TRANSIT_VLAN));
        assertThat(foundIsls, Matchers.hasSize(1));
    }

    @Test
    public void shouldSkipIslOccupiedByFlowWithNoEnoughBandwidth() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(2)
                .status(IslStatus.ACTIVE).availableBandwidth(99).build();

        islRepository.add(isl);

        Flow flow = createFlowWithPath(0, 0);

        List<Isl> foundIsls = Lists.newArrayList(
                islRepository.findActiveAndOccupiedByFlowPathWithAvailableBandwidth(
                        flow.getPathIds().iterator().next(), 100, FlowEncapsulationType.TRANSIT_VLAN));
        assertThat(foundIsls, Matchers.hasSize(0));
    }

    @Ignore("Need to fix merging of bandwidth values.")
    @Test
    public void shouldGetUsedBandwidth() {
        createFlowWithPath(59, 99);

        assertEquals(59, flowPathRepository.getUsedBandwidthBetweenEndpoints(
                TEST_SWITCH_A_ID, 1, TEST_SWITCH_B_ID, 2));

        assertEquals(99, flowPathRepository.getUsedBandwidthBetweenEndpoints(
                TEST_SWITCH_B_ID, 2, TEST_SWITCH_A_ID, 1));
    }

    @Test
    public void shouldDeleteIsl() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .build();

        islRepository.add(isl);
        transactionManager.doInTransaction(() ->
                islRepository.remove(isl));

        assertEquals(0, islRepository.findAll().size());
    }

    @Test
    public void shouldDeleteFoundIsl() {
        Isl isl1 = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(1)
                .build();
        Isl isl2 = Isl.builder()
                .srcSwitch(switchA).srcPort(2)
                .destSwitch(switchB).destPort(2)
                .build();

        islRepository.add(isl1);
        islRepository.add(isl2);

        assertEquals(2, islRepository.findAll().size());

        transactionManager.doInTransaction(() ->
                islRepository.remove(isl1));

        assertEquals(1, islRepository.findAll().size());
    }

    @Test
    public void shouldCreateAndFindIslByEndpoint() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(1)
                .build();

        islRepository.add(isl);

        assertEquals(1, islRepository.findAll().size());
        assertEquals(2, switchRepository.findAll().size());

        List<Isl> foundIsls = Lists.newArrayList(islRepository.findBySrcEndpoint(TEST_SWITCH_A_ID, 1));

        assertEquals(1, foundIsls.size());
        assertEquals(switchB.getSwitchId(), foundIsls.get(0).getDestSwitchId());
    }

    @Test
    public void shouldCreateAndFindIslByEndpoints() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(1)
                .build();

        islRepository.add(isl);

        assertEquals(1, islRepository.findAll().size());
        assertEquals(2, switchRepository.findAll().size());

        Isl foundIsl = islRepository.findByEndpoints(TEST_SWITCH_A_ID, 1, TEST_SWITCH_B_ID, 1).get();

        assertEquals(switchB.getSwitchId(), foundIsl.getDestSwitchId());
    }

    @Test
    public void shouldReturnSymmetricIslsWithRequiredBandwidth() {
        long availableBandwidth = 100L;

        Isl forwardIsl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(2)
                .status(IslStatus.ACTIVE).availableBandwidth(availableBandwidth)
                .build();

        Isl reverseIsl = Isl.builder()
                .srcSwitch(switchB).srcPort(2)
                .destSwitch(switchA).destPort(1)
                .status(IslStatus.ACTIVE).availableBandwidth(availableBandwidth)
                .build();

        islRepository.add(forwardIsl);
        islRepository.add(reverseIsl);

        assertEquals(2, islRepository.findSymmetricActiveWithAvailableBandwidth(availableBandwidth,
                FlowEncapsulationType.TRANSIT_VLAN).size());
    }

    @Test
    public void shouldNotReturnIslIfOneDirectionDoesntHaveEnoughBandwidth() {
        long availableBandwidth = 100L;

        Isl forwardIsl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(2)
                .status(IslStatus.ACTIVE).availableBandwidth(availableBandwidth)
                .build();

        Isl reverseIsl = Isl.builder()
                .srcSwitch(switchB).srcPort(2)
                .destSwitch(switchA).destPort(1)
                .status(IslStatus.ACTIVE).availableBandwidth(availableBandwidth - 1)
                .build();

        islRepository.add(forwardIsl);
        islRepository.add(reverseIsl);

        assertEquals(0, islRepository.findSymmetricActiveWithAvailableBandwidth(availableBandwidth,
                FlowEncapsulationType.TRANSIT_VLAN).size());
    }

    @Test
    public void shouldFindActiveIslsByFlowEncapsulationType() {
        Isl isl = Isl.builder()
                .srcSwitch(switchA).srcPort(1)
                .destSwitch(switchB).destPort(2)
                .status(IslStatus.ACTIVE).availableBandwidth(100).build();
        islRepository.add(isl);

        List<Isl> allIsls = Lists.newArrayList(
                islRepository.findAllActive());
        assertThat(allIsls, Matchers.hasSize(1));

        List<Isl> foundIsls = Lists.newArrayList(
                islRepository.findAllActiveByEncapsulationType(FlowEncapsulationType.TRANSIT_VLAN));
        assertThat(foundIsls, Matchers.hasSize(1));
    }

    private Flow createFlowWithPath(int forwardBandwidth, int reverseBandwidth) {
        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID)
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .status(FlowStatus.UP)
                .build();

        FlowPath forwardPath = FlowPath.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .pathId(new PathId(TEST_FLOW_ID + "_forward_path"))
                .cookie(new FlowSegmentCookie(1))
                .meterId(new MeterId(1))
                .status(FlowPathStatus.ACTIVE)
                .bandwidth(forwardBandwidth)
                .ignoreBandwidth(false)
                .build();
        flow.setForwardPath(forwardPath);

        PathSegment forwardSegment = PathSegment.builder()
                .pathId(forwardPath.getPathId())
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchB)
                .destPort(2)
                .build();
        forwardPath.setSegments(Collections.singletonList(forwardSegment));

        FlowPath reversePath = FlowPath.builder()
                .srcSwitch(switchB)
                .destSwitch(switchA)
                .pathId(new PathId(TEST_FLOW_ID + "_reverse_path"))
                .cookie(new FlowSegmentCookie(2))
                .meterId(new MeterId(2))
                .status(FlowPathStatus.ACTIVE)
                .bandwidth(reverseBandwidth)
                .ignoreBandwidth(false)
                .build();
        flow.setReversePath(reversePath);

        PathSegment reverseSegment = PathSegment.builder()
                .pathId(reversePath.getPathId())
                .srcSwitch(switchB)
                .srcPort(2)
                .destSwitch(switchA)
                .destPort(1)
                .build();
        reversePath.setSegments(Collections.singletonList(reverseSegment));

        flowRepository.add(flow);
        return flow;
    }
}
