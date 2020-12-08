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

package org.openkilda.pce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.AvailableNetworkFactory.BuildStrategy;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.impl.AvailableNetwork;
import org.openkilda.pce.model.Edge;
import org.openkilda.pce.model.IslViewTestImpl;
import org.openkilda.pce.model.Node;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.IslRepository.IslView;
import org.openkilda.persistence.repositories.RepositoryFactory;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AvailableNetworkFactoryTest {

    public static final SwitchId SWITCH_ID_1 = new SwitchId(1);
    public static final SwitchId SWITCH_ID_2 = new SwitchId(2);
    public static final SwitchId SWITCH_ID_3 = new SwitchId(3);
    public static final SwitchId SWITCH_ID_4 = new SwitchId(4);
    public static final String GROUP_ID = "group_id_1";
    public static final String FLOW_ID_1 = "flow_id_1";
    public static final int AVAILABLE_BANDWIDTH = 1000;
    public static final int SRC_PORT = 30;
    public static final int DEST_PORT = 31;
    public static final PathId FORWARD_PATH_ID = new PathId("path_id_1");
    public static final PathId REVERSE_PATH_ID = new PathId("path_id_2");
    public static final Switch switchA = Switch.builder().switchId(SWITCH_ID_1).build();
    public static final Switch switchB = Switch.builder().switchId(SWITCH_ID_2).build();
    public static final Switch switchC = Switch.builder().switchId(SWITCH_ID_3).build();
    public static final Switch switchD = Switch.builder().switchId(SWITCH_ID_4).build();

    @Mock
    private PathComputerConfig config;
    @Mock
    RepositoryFactory repositoryFactory;
    @Mock
    private IslRepository islRepository;
    @Mock
    private FlowPathRepository flowPathRepository;

    private AvailableNetworkFactory availableNetworkFactory;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(repositoryFactory.createIslRepository()).thenReturn(islRepository);
        when(repositoryFactory.createFlowPathRepository()).thenReturn(flowPathRepository);

        availableNetworkFactory = new AvailableNetworkFactory(config, repositoryFactory);
    }

    @Test
    public void shouldBuildAvailableNetworkUsingCostStrategy() throws RecoverableException {
        Flow flow = getFlow(false);
        IslView isl = getIslView(flow);

        when(config.getNetworkStrategy()).thenReturn("COST");

        when(islRepository.findActiveByBandwidthAndEncapsulationType(flow.getBandwidth(), flow.getEncapsulationType()))
                .thenReturn(Collections.singletonList(isl));

        AvailableNetwork availableNetwork = availableNetworkFactory.getAvailableNetwork(flow, Collections.emptyList());

        assertAvailableNetworkIsCorrect(isl, availableNetwork);
    }

    @Test
    public void shouldIncreaseDiversityGroupUseCounter() throws RecoverableException {
        // Topology:
        // A----B----C     Already created flow: B-D
        //      |          Requested flow: A-B-C
        //      D

        // there is no ISL B-D because we assume that is has no enough bandwidth
        List<IslView> isls = new ArrayList<>();
        isls.addAll(getBidirectionalIsls(switchA, 1, switchB, 2));
        isls.addAll(getBidirectionalIsls(switchB, 3, switchC, 4));

        final Flow diverseFlow = Flow.builder()
                .flowId(FLOW_ID_1)
                .groupId(GROUP_ID)
                .srcSwitch(switchB)
                .destSwitch(switchD)
                .build();

        FlowPath forwardPath = FlowPath.builder()
                .srcSwitch(switchB)
                .destSwitch(switchD)
                .pathId(FORWARD_PATH_ID)
                .segments(Collections.singletonList(PathSegment.builder()
                        .srcSwitch(switchB).srcPort(SRC_PORT).destSwitch(switchD).destPort(DEST_PORT).build()))
                .build();
        when(flowPathRepository.findById(FORWARD_PATH_ID)).thenReturn(java.util.Optional.of(forwardPath));

        FlowPath reversePath = FlowPath.builder()
                .srcSwitch(switchD)
                .destSwitch(switchB)
                .pathId(REVERSE_PATH_ID)
                .segments(Collections.singletonList(PathSegment.builder()
                        .srcSwitch(switchD).srcPort(DEST_PORT).destSwitch(switchB).destPort(SRC_PORT).build()))
                .build();
        when(flowPathRepository.findById(REVERSE_PATH_ID)).thenReturn(java.util.Optional.of(reversePath));

        Flow flow = getFlow(false);
        flow.setSrcSwitch(switchA);
        flow.setDestSwitch(switchC);
        flow.setGroupId(GROUP_ID);

        when(config.getNetworkStrategy()).thenReturn(BuildStrategy.COST.name());
        when(islRepository.findActiveByBandwidthAndEncapsulationType(flow.getBandwidth(), flow.getEncapsulationType()))
                .thenReturn(isls);

        when(flowPathRepository.findPathIdsByFlowGroupId(GROUP_ID))
                .thenReturn(Lists.newArrayList(FORWARD_PATH_ID, REVERSE_PATH_ID));



        AvailableNetwork availableNetwork = availableNetworkFactory.getAvailableNetwork(flow, Collections.emptyList());
        assertEquals(2, availableNetwork.getSwitch(switchB.getSwitchId()).getDiversityGroupUseCounter());
    }

    @Test
    public void shouldBuildAvailableNetworkUsingCostStrategyWithIgnoreBandwidth() throws RecoverableException {
        Flow flow = getFlow(true);
        IslView isl = getIslView(flow);

        when(config.getNetworkStrategy()).thenReturn("COST");

        when(islRepository.findActiveByEncapsulationType(flow.getEncapsulationType()))
                .thenReturn(Collections.singletonList(isl));

        AvailableNetwork availableNetwork = availableNetworkFactory.getAvailableNetwork(flow, Collections.emptyList());

        assertAvailableNetworkIsCorrect(isl, availableNetwork);
    }

    @Test
    public void shouldBuildAvailableNetworkUsingSymmetricCostStrategy() throws RecoverableException {
        Flow flow = getFlow(false);
        IslView isl = getIslView(flow);

        when(config.getNetworkStrategy()).thenReturn("SYMMETRIC_COST");

        when(islRepository.findSymmetricActiveByBandwidthAndEncapsulationType(flow.getBandwidth(),
                flow.getEncapsulationType()))
                .thenReturn(Collections.singletonList(isl));

        AvailableNetwork availableNetwork = availableNetworkFactory.getAvailableNetwork(flow, Collections.emptyList());

        assertAvailableNetworkIsCorrect(isl, availableNetwork);
    }

    @Test
    public void shouldBuildAvailableNetworkUsingSymmetricCostStrategyWithIgnoreBandwidth() throws RecoverableException {
        Flow flow = getFlow(true);
        IslView isl = getIslView(flow);

        when(config.getNetworkStrategy()).thenReturn("SYMMETRIC_COST");

        when(islRepository.findActiveByEncapsulationType(flow.getEncapsulationType()))
                .thenReturn(Collections.singletonList(isl));

        AvailableNetwork availableNetwork = availableNetworkFactory.getAvailableNetwork(flow, Collections.emptyList());

        assertAvailableNetworkIsCorrect(isl, availableNetwork);
    }

    private static Flow getFlow(boolean ignoreBandwidth) {
        return Flow.builder()
                .flowId("test-id")
                .srcSwitch(Switch.builder().switchId(new SwitchId("1")).build())
                .destSwitch(Switch.builder().switchId(new SwitchId("2")).build())
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .bandwidth(100)
                .ignoreBandwidth(ignoreBandwidth)
                .build();
    }

    private static IslView getIslView(Flow flow) {
        return getIslView(flow.getSrcSwitch(), SRC_PORT, flow.getDestSwitch(), DEST_PORT);
    }

    private static IslView getIslView(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort) {
        return new IslViewTestImpl(srcSwitch.getSwitchId(), srcPort, null, dstSwitch.getSwitchId(), dstPort, null,
                33L, 10, AVAILABLE_BANDWIDTH, false, false);
    }

    private static List<IslView> getBidirectionalIsls(
            Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort) {
        return Lists.newArrayList(
                getIslView(srcSwitch, srcPort, dstSwitch, dstPort),
                getIslView(dstSwitch, dstPort, srcSwitch, srcPort));
    }

    private static void assertAvailableNetworkIsCorrect(IslView isl, AvailableNetwork availableNetwork) {
        Node src = availableNetwork.getSwitch(isl.getSrcSwitchId());
        assertNotNull(src);
        assertEquals(1, src.getOutgoingLinks().size());
        Edge edge = src.getOutgoingLinks().iterator().next();
        assertEquals(isl.getSrcSwitchId(), edge.getSrcSwitch().getSwitchId());
        assertEquals(isl.getSrcPort(), edge.getSrcPort());
        assertEquals(isl.getDestSwitchId(), edge.getDestSwitch().getSwitchId());
        assertEquals(isl.getDestPort(), edge.getDestPort());
        assertEquals(isl.getCost(), edge.getCost());
        assertEquals(isl.getLatency(), edge.getLatency());
        assertEquals(isl.getAvailableBandwidth(), edge.getAvailableBandwidth());
        Node dst = availableNetwork.getSwitch(isl.getDestSwitchId());
        assertNotNull(dst);
        assertEquals(1, dst.getIncomingLinks().size());
    }
}
