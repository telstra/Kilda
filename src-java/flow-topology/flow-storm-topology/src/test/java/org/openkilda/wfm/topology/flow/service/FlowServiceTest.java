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

package org.openkilda.wfm.topology.flow.service;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.openkilda.model.SwitchFeature.MULTI_TABLE;

import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.pce.Path;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.share.flow.TestFlowBuilder;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.flow.service.FlowCommandFactory;
import org.openkilda.wfm.topology.flow.model.FlowPathsWithEncapsulation;
import org.openkilda.wfm.topology.flow.model.ReroutedFlowPaths;
import org.openkilda.wfm.topology.flow.validation.FlowValidationException;
import org.openkilda.wfm.topology.flow.validation.FlowValidator;
import org.openkilda.wfm.topology.flow.validation.SwitchValidationException;
import org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder;

import com.google.common.collect.Sets;
import junitparams.JUnitParamsRunner;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.Optional;

@RunWith(JUnitParamsRunner.class)
public class FlowServiceTest extends InMemoryGraphBasedTest {
    public static final Object[][] SHOW_SRC_DEVICES_SHOW_DST_DEVICES_MATRIX = new Object[][]{
            // showSrcConnectedDevices, showDstConnectedDevices
            {true, true},
            {true, false},
            {false, true},
            {false, false}
    };
    private static final SwitchId SWITCH_ID_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_ID_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final SwitchId SWITCH_ID_3 = new SwitchId("00:00:00:00:00:00:00:03");
    private static final SwitchId SWITCH_ID_4 = new SwitchId("00:00:00:00:00:00:00:04");
    private static final PathPair PATH_DIRECT_1_TO_3 = PathPair.builder()
            .forward(Path.builder().srcSwitchId(SWITCH_ID_1).destSwitchId(SWITCH_ID_3).latency(1).segments(asList(
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_1).srcPort(11).latency(1L)
                            .destSwitchId(SWITCH_ID_3).destPort(11).build())).build())
            .reverse(Path.builder().srcSwitchId(SWITCH_ID_3).destSwitchId(SWITCH_ID_1).latency(1).segments(asList(
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_3).srcPort(11).latency(1L)
                            .destSwitchId(SWITCH_ID_1).destPort(11).build())).build()).build();
    private static final PathPair PATH_DIRECT_1_TO_4 = PathPair.builder()
            .forward(Path.builder().srcSwitchId(SWITCH_ID_1).destSwitchId(SWITCH_ID_4).latency(1).segments(asList(
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_1).srcPort(22).latency(1L)
                            .destSwitchId(SWITCH_ID_4).destPort(22).build())).build())
            .reverse(Path.builder().srcSwitchId(SWITCH_ID_4).destSwitchId(SWITCH_ID_1).latency(1).segments(asList(
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_4).srcPort(22).latency(1L)
                            .destSwitchId(SWITCH_ID_1).destPort(22).build())).build()).build();

    private static final PathPair PATH_1_TO_3_VIA_2 = PathPair.builder()
            .forward(Path.builder().srcSwitchId(SWITCH_ID_1).destSwitchId(SWITCH_ID_3).latency(2).segments(asList(
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_1).srcPort(11).latency(1L)
                            .destSwitchId(SWITCH_ID_2).destPort(11).build(),
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_2).srcPort(12).latency(1L)
                            .destSwitchId(SWITCH_ID_3).destPort(11).build()))
                    .build())
            .reverse(Path.builder().srcSwitchId(SWITCH_ID_3).destSwitchId(SWITCH_ID_1).latency(2).segments(asList(
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_3).srcPort(11).latency(1L)
                            .destSwitchId(SWITCH_ID_2).destPort(12).build(),
                    Path.Segment.builder().srcSwitchId(SWITCH_ID_2).srcPort(11).latency(1L)
                            .destSwitchId(SWITCH_ID_1).destPort(11).build()))
                    .build()).build();
    private static final PathId FORWARD_PATH_ID = new PathId("forward");
    private static final PathId REVERSE_PATH_ID = new PathId("reverse");
    private static final MeterId METER_32 = new MeterId(32);
    private static final MeterId METER_33 = new MeterId(33);
    private static final MeterId METER_34 = new MeterId(34);
    private static final MeterId METER_35 = new MeterId(35);

    private static final int FORWARD_COOKIE = 123;
    private static final int REVERSE_COOKIE = 321;

    private static String FLOW_ID = "test-flow";

    private static long BANDWIDTH = 1000L;

    private SwitchRepository switchRepository;
    private SwitchPropertiesRepository switchPropertiesRepository;
    private IslRepository islRepository;
    private FlowRepository flowRepository;
    private FlowPathRepository flowPathRepository;
    private FlowMeterRepository flowMeterRepository;
    private FlowCookieRepository flowCookieRepository;
    private FlowResourcesConfig flowResourcesConfig;

    private FlowService flowService;
    private PathComputer pathComputer;


    @Before
    public void setUp() {
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        switchPropertiesRepository = persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository();
        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        flowMeterRepository = persistenceManager.getRepositoryFactory().createFlowMeterRepository();
        flowCookieRepository = persistenceManager.getRepositoryFactory().createFlowCookieRepository();

        pathComputer = mock(PathComputer.class);
        PathComputerFactory pathComputerFactory = mock(PathComputerFactory.class);
        FlowValidator flowValidator = new FlowValidator(persistenceManager.getRepositoryFactory());
        when(pathComputerFactory.getPathComputer()).thenReturn(pathComputer);

        flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        flowService = new FlowService(persistenceManager,
                pathComputerFactory, new FlowResourcesManager(persistenceManager, flowResourcesConfig), flowValidator,
                new FlowCommandFactory());

        createTopology();
    }

    @Test
    public void shouldUpdateFlowStatus() {
        String flowId = "test-flow";
        Flow flow = new TestFlowBuilder(flowId)
                .srcSwitch(getOrCreateSwitch(SWITCH_ID_1))
                .srcPort(1)
                .srcVlan(101)
                .destSwitch(getOrCreateSwitch(SWITCH_ID_2))
                .destPort(2)
                .destVlan(102)
                .status(FlowStatus.IN_PROGRESS)
                .build();

        flowRepository.add(flow);

        flowService.updateFlowStatus(flowId, FlowStatus.UP, emptySet());

        Optional<Flow> foundFlow = flowRepository.findById(flowId);
        assertEquals(FlowStatus.UP, foundFlow.get().getStatus());
    }

    @Test
    public void shouldSwapEndpointsForFlow() throws FlowNotFoundException, FlowValidationException,
            RecoverableException, UnroutableFlowException {

        FlowService flowServiceSpy = Mockito.spy(flowService);

        String firstFlowId = "flow1";
        String secondFlowId = "flow2";

        Flow firstFlow = new TestFlowBuilder(firstFlowId)
                .srcSwitch(getOrCreateSwitch(SWITCH_ID_1))
                .srcPort(33)
                .destSwitch(getOrCreateSwitch(SWITCH_ID_3))
                .destPort(33)
                .build();

        Flow secondFlow = new TestFlowBuilder(secondFlowId)
                .srcSwitch(getOrCreateSwitch(SWITCH_ID_1))
                .srcPort(44)
                .destSwitch(getOrCreateSwitch(SWITCH_ID_4))
                .destPort(44)
                .build();
        flowRepository.add(firstFlow);
        flowRepository.add(secondFlow);

        Flow updFirstFlow = new TestFlowBuilder(secondFlowId)
                .srcSwitch(getOrCreateSwitch(SWITCH_ID_1))
                .srcPort(44)
                .destSwitch(getOrCreateSwitch(SWITCH_ID_4))
                .destPort(44)
                .build();

        Flow updSecondFlow = new TestFlowBuilder(firstFlowId)
                .srcSwitch(getOrCreateSwitch(SWITCH_ID_1))
                .srcPort(33)
                .destSwitch(getOrCreateSwitch(SWITCH_ID_3))
                .destPort(33)
                .build();

        when(pathComputer.getPath(any(), anyCollection()))
                .thenReturn(PATH_DIRECT_1_TO_4)
                .thenReturn(PATH_DIRECT_1_TO_3);

        //flowService.swapFlowEnpoints(updFirstFlow, updSecondFlow, mock(FlowCommandSender.class));

        //Optional<Flow> resultFirstFlow = flowRepository.findById(firstFlowId);
        //Optional<Flow> resultSecondFlow = flowRepository.findById(secondFlowId);

        //assertEquals(firstFlow.getSrcSwitchId(), resultSecondFlow.get().getDestSwitchId());

    }

    @Test
    public void shouldSurviveResourceAllocationFailureOnCreateFlowWithProtectedPath() throws RecoverableException,
            UnroutableFlowException, FlowNotFoundException, FlowAlreadyExistException, FlowValidationException,
            SwitchValidationException, ResourceAllocationException {

        FlowService flowServiceSpy = Mockito.spy(flowService);
        Mockito.doThrow(ResourceAllocationException.class).doCallRealMethod()
                .when(flowServiceSpy).createProtectedPath(any());

        Flow flow = getFlowBuilder()
                .allocateProtectedPath(true)
                .build();

        when(pathComputer.getPath(any()))
                .thenReturn(PATH_DIRECT_1_TO_3)
                .thenReturn(PATH_1_TO_3_VIA_2);

        flowService.createFlow(flow, null, mock(FlowCommandSender.class));

        Optional<Flow> foundFlow = persistenceManager.getRepositoryFactory().createFlowRepository()
                .findById(FLOW_ID);
        assertEquals(flow.getFlowId(), foundFlow.get().getFlowId());
    }

    @Test
    public void createFlowAllocateResources() throws Exception {
        Flow flow = getFlowBuilder()
                .allocateProtectedPath(true)
                .build();

        when(pathComputer.getPath(any())).thenReturn(PATH_1_TO_3_VIA_2, PATH_DIRECT_1_TO_3);

        FlowCommandSender flowCommandSender = mock(FlowCommandSender.class);
        flowService.createFlow(flow, null, flowCommandSender);

        checkFlowResources(flow);
    }

    @Test
    public void saveFlowAllocateResources() throws Exception {
        Flow flow = getFlowBuilder().build();
        flow.setAllocateProtectedPath(false);
        addFlowPaths(flow, PATH_DIRECT_1_TO_3);

        FlowCommandSender flowCommandSender = mock(FlowCommandSender.class);
        flowService.saveFlow(flow, flowCommandSender);

        checkFlowResources(flow);
    }

    @Test
    public void shouldRerouteFlow() throws Exception {
        FlowRepository flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        Flow flow = getFlowBuilder().build();

        when(pathComputer.getPath(any())).thenReturn(PATH_DIRECT_1_TO_3);

        flowService.createFlow(flow, null, mock(FlowCommandSender.class));
        flowService.updateFlowStatus(FLOW_ID, FlowStatus.UP, emptySet());
        flowRepository.findById(FLOW_ID)
                .ifPresent(f -> {
                    f.setTargetPathComputationStrategy(PathComputationStrategy.LATENCY);
                });

        when(pathComputer.getPath(any(), anyCollection())).thenReturn(PATH_1_TO_3_VIA_2);

        ReroutedFlowPaths reroutedFlowPaths =
                flowService.rerouteFlow(FLOW_ID, true, emptySet(), mock(FlowCommandSender.class));

        assertNotNull(reroutedFlowPaths);
        assertTrue(reroutedFlowPaths.isRerouted());
        checkSamePaths(PATH_1_TO_3_VIA_2.getForward(), reroutedFlowPaths.getNewFlowPaths().getForwardPath());

        Flow foundFlow = flowRepository.findById(FLOW_ID)
                .orElseThrow(() -> new IllegalStateException("No flow found " + FLOW_ID));
        assertEquals(flow.getFlowId(), foundFlow.getFlowId());
        assertEquals(PathComputationStrategy.LATENCY, foundFlow.getPathComputationStrategy());
        assertNull(foundFlow.getTargetPathComputationStrategy());
    }

    @Test
    public void shouldNotUpdatePathsOnRerouteWithProtectedFlowIfNoOtherPaths() throws RecoverableException,
            UnroutableFlowException, FlowNotFoundException, FlowAlreadyExistException, FlowValidationException,
            SwitchValidationException, ResourceAllocationException {

        Flow flow = getFlowBuilder()
                .allocateProtectedPath(true)
                .build();

        when(pathComputer.getPath(any()))
                .thenReturn(PATH_DIRECT_1_TO_3)
                .thenReturn(PATH_1_TO_3_VIA_2);

        flowService.createFlow(flow, null, mock(FlowCommandSender.class));
        flowService.updateFlowStatus(FLOW_ID, FlowStatus.UP, emptySet());

        Assert.assertThat(flowPathRepository.findAll(), hasSize(4));

        when(pathComputer.getPath(any(), anyCollection())).thenReturn(PATH_DIRECT_1_TO_3);
        when(pathComputer.getPath(any()))
                .thenReturn(PATH_1_TO_3_VIA_2);

        ReroutedFlowPaths reroutedFlowPaths =
                flowService.rerouteFlow(FLOW_ID, false, emptySet(), mock(FlowCommandSender.class));

        FlowPathsWithEncapsulation newFlowPaths = reroutedFlowPaths.getNewFlowPaths();
        FlowPathsWithEncapsulation oldFlowPaths = reroutedFlowPaths.getOldFlowPaths();

        assertEquals(newFlowPaths.getForwardPath(), oldFlowPaths.getForwardPath());
        assertEquals(newFlowPaths.getReversePath(), oldFlowPaths.getReversePath());
        assertEquals(newFlowPaths.getProtectedForwardPath(), oldFlowPaths.getProtectedForwardPath());
        assertEquals(newFlowPaths.getProtectedReversePath(), oldFlowPaths.getProtectedReversePath());

        Optional<Flow> foundFlow = persistenceManager.getRepositoryFactory().createFlowRepository()
                .findById(FLOW_ID);
        assertEquals(flow.getFlowId(), foundFlow.get().getFlowId());
    }

    private void checkSamePaths(Path path, FlowPath flowPath) {
        assertEquals(path.getSrcSwitchId(), flowPath.getSrcSwitchId());
        assertEquals(path.getDestSwitchId(), flowPath.getDestSwitchId());
        Path.Segment[] flowPathSegments = flowPath.getSegments().stream()
                .map(fp -> Path.Segment.builder()
                        .srcSwitchId(fp.getSrcSwitchId())
                        .srcPort(fp.getSrcPort())
                        .destSwitchId(fp.getDestSwitchId())
                        .destPort(fp.getDestPort())
                        .latency(fp.getLatency())
                        .build())
                .toArray(Path.Segment[]::new);
        assertThat(path.getSegments(), containsInAnyOrder(flowPathSegments));
    }

    private void checkFlowResources(Flow flow) {
        Flow createdFlow = flowRepository.findById(flow.getFlowId()).get();

        assertEquals(getExpectedMeterCount(flow), flowMeterRepository.findAll().size());
        assertEquals(getExpectedCookieCount(flow), flowCookieRepository.findAll().size());

        long forwardMeterId = createdFlow.getForwardPath().getMeterId().getValue();
        assertTrue(forwardMeterId >= flowResourcesConfig.getMinFlowMeterId()
                && forwardMeterId <= flowResourcesConfig.getMaxFlowMeterId());
        long reverseMeterId = createdFlow.getReversePath().getMeterId().getValue();
        assertTrue(reverseMeterId >= flowResourcesConfig.getMinFlowMeterId()
                && reverseMeterId <= flowResourcesConfig.getMaxFlowMeterId());
    }

    private int getExpectedMeterCount(Flow flow) {
        int count = 0;

        count += flow.getBandwidth() > 0 ? 2 : 0;
        count *= flow.isAllocateProtectedPath() ? 2 : 1;
        return count;
    }

    private int getExpectedCookieCount(Flow flow) {
        int count = 1;
        count *= flow.isAllocateProtectedPath() ? 2 : 1;
        return count;
    }

    private void addFlowPaths(Flow flow, PathPair pathPair) {
        FlowPathBuilder flowPathBuilder = new FlowPathBuilder(switchRepository, switchPropertiesRepository);

        PathResources forwardPathResources = PathResources.builder().pathId(FORWARD_PATH_ID).build();
        PathResources reversePathResources = PathResources.builder().pathId(REVERSE_PATH_ID).build();

        FlowPath forward = flowPathBuilder.buildFlowPath(
                flow, forwardPathResources, pathPair.forward, new Cookie(FORWARD_COOKIE));
        FlowPath reverse = flowPathBuilder.buildFlowPath(
                flow, reversePathResources, pathPair.reverse, new Cookie(REVERSE_COOKIE));

        flow.setForwardPath(forward);
        flow.setReversePath(reverse);
    }

    private Flow.FlowBuilder getFlowBuilder() {
        return Flow.builder()
                .flowId(FLOW_ID)
                .srcSwitch(getOrCreateSwitch(SWITCH_ID_1)).srcPort(1).srcVlan(101)
                .destSwitch(getOrCreateSwitch(SWITCH_ID_3)).destPort(2).destVlan(102)
                .bandwidth(BANDWIDTH)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .pathComputationStrategy(PathComputationStrategy.COST)
                .status(FlowStatus.IN_PROGRESS);
    }

    private void createTopology() {
        Switch switch1 = getOrCreateSwitch(SWITCH_ID_1);
        getOrCreateSwitchProperties(switch1);
        Switch switch2 = getOrCreateSwitch(SWITCH_ID_2);
        getOrCreateSwitchProperties(switch2);
        Switch switch3 = getOrCreateSwitch(SWITCH_ID_3);
        getOrCreateSwitchProperties(switch3);

        createIsl(switch1, 11, switch2, 11);
        createIsl(switch2, 11, switch1, 11);
        createIsl(switch2, 12, switch3, 12);
        createIsl(switch3, 12, switch2, 12);
        createIsl(switch1, 13, switch3, 13);
        createIsl(switch3, 13, switch1, 13);
    }

    private Switch getOrCreateSwitch(SwitchId switchId) {
        return switchRepository.findById(switchId).orElseGet(() -> {
            Switch sw = Switch.builder()
                    .switchId(switchId)
                    .status(SwitchStatus.ACTIVE)
                    .features(Sets.newHashSet(MULTI_TABLE)).build();
            switchRepository.add(sw);
            return sw;
        });
    }

    private void getOrCreateSwitchProperties(Switch sw) {
        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(sw)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                .multiTable(false)
                .build();
        switchPropertiesRepository.add(switchProperties);
    }

    private Isl createIsl(Switch srcSwitch, int srcPort, Switch destSwitch, int destPort) {
        Isl isl = Isl.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .maxBandwidth(BANDWIDTH)
                .build();
        islRepository.add(isl);
        return isl;
    }
}
