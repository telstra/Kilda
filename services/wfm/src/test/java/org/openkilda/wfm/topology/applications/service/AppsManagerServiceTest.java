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

package org.openkilda.wfm.topology.applications.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.openkilda.applications.model.Exclusion;
import org.openkilda.model.ApplicationRule;
import org.openkilda.model.Cookie;
import org.openkilda.model.ExclusionId;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowApplication;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Metadata;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.ApplicationRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.Neo4jBasedTest;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.vxlan.VxlanEncapsulation;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AppsManagerServiceTest extends Neo4jBasedTest {

    private static final String TEST_FLOW_ID = "test_flow_id";
    private static final String TEST_FLOW_ID_ONE_SWITCH = "test_flow_id_one_switch";
    private static final SwitchId TEST_SWITCH_ID_A = new SwitchId("A");
    private static final SwitchId TEST_SWITCH_ID_B = new SwitchId("B");

    private static AppsManagerService service;

    private static SwitchRepository switchRepository;
    private static FlowRepository flowRepository;
    private static ApplicationRepository applicationRepository;

    private static FlowResourcesManager flowResourcesManager;

    @BeforeClass
    public static void setUp() {
        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        flowResourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        service = new AppsManagerService(null, persistenceManager, flowResourcesConfig);

        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        applicationRepository = persistenceManager.getRepositoryFactory().createApplicationRepository();
    }

    @Before
    public void init() {
        buildFlow();
        buildOneSwitchFlow();
        buildFlowAppRules();
    }

    @Test
    public void shouldPersistTelescope() {
        Flow originalFlow = flowRepository.findById(TEST_FLOW_ID).get();
        Flow flow = flowRepository.findById(TEST_FLOW_ID).get();

        assertNull(flow.getForwardPath().getApplications());
        assertNull(flow.getReversePath().getApplications());

        service.persistTelescope(flow);

        flow = flowRepository.findById(TEST_FLOW_ID).get();

        assertTrue(flow.getForwardPath().getApplications().contains(FlowApplication.TELESCOPE));
        assertTrue(flow.getReversePath().getApplications().contains(FlowApplication.TELESCOPE));

        // verify that the flow has not changed after adding the Telescope App
        flow.getForwardPath().setApplications(null);
        flow.getReversePath().setApplications(null);
        flow.getForwardPath().setTimeModify(originalFlow.getForwardPath().getTimeModify());
        flow.getReversePath().setTimeModify(originalFlow.getReversePath().getTimeModify());

        assertEquals(originalFlow.getForwardPath(), flow.getForwardPath());
        assertEquals(originalFlow.getReversePath(), flow.getReversePath());
        assertEquals(originalFlow, flow);
    }

    @Test
    public void shouldRemoveTelescope() {
        Flow flow = flowRepository.findById(TEST_FLOW_ID).get();
        service.persistTelescope(flow);

        Flow originalFlow = flowRepository.findById(TEST_FLOW_ID).get();
        flow = flowRepository.findById(TEST_FLOW_ID).get();

        service.removeTelescopeFromFlowPaths(flow);

        assertFalse(flow.getForwardPath().getApplications().contains(FlowApplication.TELESCOPE));
        assertFalse(flow.getReversePath().getApplications().contains(FlowApplication.TELESCOPE));

        // verify that the flow has not changed after removing the Telescope App
        flow.getForwardPath().getApplications().add(FlowApplication.TELESCOPE);
        flow.getReversePath().getApplications().add(FlowApplication.TELESCOPE);
        flow.getForwardPath().setTimeModify(originalFlow.getForwardPath().getTimeModify());
        flow.getReversePath().setTimeModify(originalFlow.getReversePath().getTimeModify());

        assertEquals(originalFlow.getForwardPath(), flow.getForwardPath());
        assertEquals(originalFlow.getReversePath(), flow.getReversePath());
        assertEquals(originalFlow, flow);
    }

    @Test
    public void shouldRemoveFlowExclusions() {
        Collection<ApplicationRule> applicationRules = applicationRepository.findByFlowId(TEST_FLOW_ID);
        assertEquals(2, applicationRules.size());

        Collection<ExclusionId> exclusionIds = flowResourcesManager.getExclusionIdResources(TEST_FLOW_ID);
        assertEquals(2, exclusionIds.size());

        service.removeFlowExclusions(TEST_FLOW_ID);

        applicationRules = applicationRepository.findByFlowId(TEST_FLOW_ID);
        assertEquals(0, applicationRules.size());

        exclusionIds = flowResourcesManager.getExclusionIdResources(TEST_FLOW_ID);
        assertEquals(0, exclusionIds.size());
    }

    @Test
    public void shouldAllocateVxlanForOneSwitchFlow() {
        Flow flow = flowRepository.findById(TEST_FLOW_ID_ONE_SWITCH).get();
        Set<FlowEncapsulationType> supportedEncapsulation = Collections.singleton(FlowEncapsulationType.VXLAN);
        SwitchProperties targetProps = SwitchProperties.builder()
                .supportedTransitEncapsulation(supportedEncapsulation)
                .build();
        EncapsulationResources encapResources = service.checkOneSwitchFlowAndGetFlowEncapsulationResources(targetProps,
                flow);
        assertTrue(encapResources instanceof VxlanEncapsulation);

        flow = flowRepository.findById(TEST_FLOW_ID_ONE_SWITCH).get();
        assertEquals(FlowEncapsulationType.VXLAN, flow.getEncapsulationType());

        EncapsulationResources encapResourcesFromManager =
                flowResourcesManager.getEncapsulationResources(flow.getForwardPathId(), flow.getReversePathId(),
                        FlowEncapsulationType.VXLAN).get();
        assertEquals(encapResources.getEncapsulation(), encapResourcesFromManager.getEncapsulation());
    }

    @Test
    public void shouldPersistApplicationRule() {
        Flow flow = flowRepository.findById(TEST_FLOW_ID).get();
        EncapsulationResources encapsulationResources = flowResourcesManager
                .getEncapsulationResources(flow.getForwardPathId(), flow.getReversePathId(),
                        flow.getEncapsulationType()).get();

        Exclusion exclusion = Exclusion.builder()
                .srcIp("192.168.1.1")
                .srcPort(1)
                .dstIp("192.168.1.2")
                .dstPort(2)
                .ethType("IPv4")
                .proto("TCP")
                .build();

        int exclusionId = flowResourcesManager.allocateExclusionIdResources(flow.getFlowId());
        int expirationTimeout = 10;
        Optional<ApplicationRule> applicationRule = service.persistApplicationRule(flow, flow.getForwardPath(),
                exclusion, exclusionId, expirationTimeout, encapsulationResources);
        assertTrue(applicationRule.isPresent());

        Metadata metadata = Metadata.builder().encapsulationId(encapsulationResources.getTransitEncapsulationId())
                .forward(true).build();
        Optional<ApplicationRule> foundApplicationRule = applicationRepository.lookupRuleByMatchAndFlow(
                flow.getSrcSwitch().getSwitchId(), flow.getFlowId(), exclusion.getSrcIp(), exclusion.getSrcPort(),
                exclusion.getDstIp(), exclusion.getDstPort(), exclusion.getProto(), exclusion.getEthType(),
                metadata);
        assertTrue(foundApplicationRule.isPresent());
        assertEquals(applicationRule.get(), foundApplicationRule.get());

        applicationRule = service.persistApplicationRule(flow, flow.getForwardPath(),
                exclusion, exclusionId, expirationTimeout, encapsulationResources);
        assertFalse(applicationRule.isPresent());
    }

    @Test
    public void shouldRemoveApplicationRule() {
        Flow flow = flowRepository.findById(TEST_FLOW_ID).get();
        EncapsulationResources encapsulationResources = flowResourcesManager
                .getEncapsulationResources(flow.getForwardPathId(), flow.getReversePathId(),
                        flow.getEncapsulationType()).get();

        Exclusion exclusion = Exclusion.builder()
                .srcIp("192.168.1.1")
                .srcPort(1)
                .dstIp("192.168.1.2")
                .dstPort(2)
                .ethType("IPv4")
                .proto("TCP")
                .build();

        int exclusionId = flowResourcesManager.allocateExclusionIdResources(flow.getFlowId());
        int expirationTimeout = 10;
        service.persistApplicationRule(flow, flow.getForwardPath(), exclusion, exclusionId, expirationTimeout,
                encapsulationResources);

        Optional<ApplicationRule> applicationRule = service.removeApplicationRule(flow, flow.getForwardPath(),
                exclusion, encapsulationResources);
        assertTrue(applicationRule.isPresent());

        Metadata metadata = Metadata.builder().encapsulationId(encapsulationResources.getTransitEncapsulationId())
                .forward(true).build();
        Optional<ApplicationRule> foundApplicationRule = applicationRepository.lookupRuleByMatchAndFlow(
                flow.getSrcSwitch().getSwitchId(), flow.getFlowId(), exclusion.getSrcIp(), exclusion.getSrcPort(),
                exclusion.getDstIp(), exclusion.getDstPort(), exclusion.getProto(), exclusion.getEthType(),
                metadata);
        assertFalse(foundApplicationRule.isPresent());

        Collection<Integer> exclusionIds = flowResourcesManager.getExclusionIdResources(TEST_FLOW_ID).stream()
                .map(ExclusionId::getId)
                .collect(Collectors.toList());
        assertFalse(exclusionIds.contains(exclusionId));
    }

    private void buildFlow() {
        Switch switchA = Switch.builder().switchId(TEST_SWITCH_ID_A).build();
        switchRepository.createOrUpdate(switchA);
        Switch switchB = Switch.builder().switchId(TEST_SWITCH_ID_B).build();
        switchRepository.createOrUpdate(switchB);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID)
                .srcSwitch(switchA)
                .srcPort(1)
                .srcVlan(2)
                .destSwitch(switchB)
                .destPort(3)
                .destVlan(4)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .bandwidth(5)
                .status(FlowStatus.UP)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID + "_forward_path"))
                .flow(flow)
                .cookie(Cookie.buildForwardCookie(6L))
                .meterId(new MeterId(32))
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .bandwidth(5)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegment = PathSegment.builder()
                .srcSwitch(switchA)
                .srcPort(1)
                .destSwitch(switchB)
                .destPort(3)
                .build();
        forwardFlowPath.setSegments(Lists.newArrayList(forwardSegment));

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID + "_reverse_path"))
                .flow(flow)
                .cookie(Cookie.buildReverseCookie(6L))
                .meterId(new MeterId(33))
                .srcSwitch(switchB)
                .destSwitch(switchA)
                .bandwidth(5)
                .status(FlowPathStatus.ACTIVE)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegment = PathSegment.builder()
                .srcSwitch(switchB)
                .srcPort(3)
                .destSwitch(switchB)
                .destPort(1)
                .build();
        reverseFlowPath.setSegments(Lists.newArrayList(reverseSegment));

        flowRepository.createOrUpdate(flow);

        flowResourcesManager.allocateEncapsulationResources(flow, FlowEncapsulationType.TRANSIT_VLAN);
    }

    private void buildOneSwitchFlow() {
        Switch sw = Switch.builder().switchId(new SwitchId(1)).build();
        switchRepository.createOrUpdate(sw);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_ONE_SWITCH)
                .srcSwitch(sw)
                .srcPort(1)
                .srcVlan(2)
                .destSwitch(sw)
                .destPort(3)
                .destVlan(4)
                .bandwidth(5)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_ONE_SWITCH + "_forward_path"))
                .flow(flow)
                .cookie(Cookie.buildForwardCookie(6L))
                .meterId(new MeterId(32))
                .srcSwitch(sw)
                .destSwitch(sw)
                .bandwidth(5)
                .status(FlowPathStatus.ACTIVE)
                .segments(Collections.emptyList())
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setForwardPath(forwardFlowPath);

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_ONE_SWITCH + "_reverse_path"))
                .flow(flow)
                .cookie(Cookie.buildReverseCookie(6L))
                .meterId(new MeterId(33))
                .srcSwitch(sw)
                .destSwitch(sw)
                .bandwidth(5)
                .status(FlowPathStatus.ACTIVE)
                .segments(Collections.emptyList())
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setReversePath(reverseFlowPath);

        flowRepository.createOrUpdate(flow);
    }

    private void buildFlowAppRules() {
        int exclusionId = flowResourcesManager.allocateExclusionIdResources(TEST_FLOW_ID);
        applicationRepository.createOrUpdate(buildRuleA(exclusionId));

        exclusionId = flowResourcesManager.allocateExclusionIdResources(TEST_FLOW_ID);
        applicationRepository.createOrUpdate(buildRuleB(exclusionId));
    }

    private ApplicationRule buildRuleA(int exclusionId) {
        return ApplicationRule.builder()
                .flowId(TEST_FLOW_ID)
                .switchId(TEST_SWITCH_ID_A)
                .srcIp("127.0.1.2")
                .srcPort(2)
                .dstIp("127.0.1.3")
                .dstPort(3)
                .proto("UDP")
                .ethType("IPv4")
                .cookie(Cookie.buildExclusionCookie(4L, exclusionId, true))
                .metadata(Metadata.builder().encapsulationId(5).forward(true).build())
                .expirationTimeout(7)
                .timeCreate(Instant.now())
                .build();
    }

    private ApplicationRule buildRuleB(int exclusionId) {
        return ApplicationRule.builder()
                .flowId(TEST_FLOW_ID)
                .switchId(TEST_SWITCH_ID_A)
                .srcIp("127.0.1.4")
                .srcPort(9)
                .dstIp("127.0.1.5")
                .dstPort(10)
                .proto("TCP")
                .ethType("IPv4")
                .cookie(Cookie.buildExclusionCookie(11L, exclusionId, false))
                .metadata(Metadata.builder().encapsulationId(12).build())
                .expirationTimeout(14)
                .timeCreate(Instant.now())
                .build();
    }
}
