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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import org.openkilda.floodlight.api.request.EgressFlowSegmentRequest;
import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.request.IngressFlowSegmentRequest;
import org.openkilda.floodlight.api.request.OneSwitchFlowRequest;
import org.openkilda.floodlight.api.request.TransitFlowSegmentRequest;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.IngressFlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class SpeakerFlowSegmentRequestBuilderTest extends InMemoryGraphBasedTest {
    private static final CommandContext COMMAND_CONTEXT = new CommandContext();
    private static final SwitchId SWITCH_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final SwitchId SWITCH_3 = new SwitchId("00:00:00:00:00:00:00:03");
    private static final Iterator<Long> cookieFactory = LongStream.iterate(1, entry -> entry + 1).iterator();
    private static final Iterator<Integer> meterFactory = IntStream.iterate(1, entry -> entry + 1).iterator();
    private static final Iterator<Integer> vlanFactory = IntStream.iterate(
            1, entry -> entry < 4096 ? entry + 1 : 1).iterator();

    private final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    private SpeakerFlowSegmentRequestBuilder target;
    private TransitVlanRepository vlanRepository;

    @Before
    public void setUp() {
        FlowResourcesManager resourcesManager = new FlowResourcesManager(persistenceManager,
                configurationProvider.getConfiguration(FlowResourcesConfig.class));
        target = new SpeakerFlowSegmentRequestBuilder(resourcesManager);
        vlanRepository = persistenceManager.getRepositoryFactory().createTransitVlanRepository();
    }

    @Test
    public void shouldCreateNonIngressRequestsWithoutVlans() {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_2).build();

        Flow flow = buildFlow(srcSwitch, 1, 0, destSwitch, 2, 0, 0);
        setSegmentsWithoutTransitSwitches(
                Objects.requireNonNull(flow.getForwardPath()), Objects.requireNonNull(flow.getReversePath()));

        List<FlowSegmentRequestFactory> commands = target.buildAllExceptIngress(COMMAND_CONTEXT, flow);
        assertEquals(2, commands.size());

        verifyForwardEgressRequest(flow, commands.get(0).makeInstallRequest(commandIdGenerator.generate()));
        verifyReverseEgressRequest(flow, commands.get(1).makeInstallRequest(commandIdGenerator.generate()));
    }

    @Test
    public void shouldCreateNonIngressCommandsWithTransitSwitch() {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_3).build();

        Flow flow = buildFlow(srcSwitch, 1, 101, destSwitch, 2, 102, 0);
        setSegmentsWithTransitSwitches(
                Objects.requireNonNull(flow.getForwardPath()), Objects.requireNonNull(flow.getReversePath()));

        List<FlowSegmentRequestFactory> commands = target.buildAllExceptIngress(COMMAND_CONTEXT, flow);
        assertEquals(4, commands.size());

        verifyForwardTransitRequest(flow, SWITCH_2, commands.get(0).makeInstallRequest(commandIdGenerator.generate()));
        verifyForwardEgressRequest(flow, commands.get(1).makeInstallRequest(commandIdGenerator.generate()));

        verifyReverseTransitRequest(flow, SWITCH_2, commands.get(2).makeInstallRequest(commandIdGenerator.generate()));
        verifyReverseEgressRequest(flow, commands.get(3).makeInstallRequest(commandIdGenerator.generate()));
    }

    @Test
    public void shouldCreateIngressWithoutMeterCommands() {
        commonIngressCommandTest(0);
    }

    @Test
    public void shouldCreateIngressCommands() {
        commonIngressCommandTest(1000);
    }

    @Test
    public void shouldCreateOneSwitchFlow() {
        Switch sw = Switch.builder().switchId(SWITCH_1).build();
        Flow flow = buildFlow(sw, 1, 10, sw, 2, 12, 1000);
        List<FlowSegmentRequestFactory> commands = target.buildAll(
                COMMAND_CONTEXT, flow, flow.getForwardPath(), flow.getReversePath(),
                SpeakerRequestBuildContext.EMPTY);

        assertEquals(2, commands.size());

        verifyForwardOneSwitchRequest(flow, commands.get(0).makeInstallRequest(commandIdGenerator.generate()));
        verifyReverseOneSwitchRequest(flow, commands.get(1).makeInstallRequest(commandIdGenerator.generate()));
    }

    @Test
    public void useActualFlowEndpoint() {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_2).build();

        // having flow stored in DB
        Flow origin = buildFlow(srcSwitch, 1, 5, destSwitch, 2, 0, 0);
        setSegmentsWithoutTransitSwitches(
                Objects.requireNonNull(origin.getForwardPath()), Objects.requireNonNull(origin.getReversePath()));

        // then new set of paths are created
        FlowPath goalForwardPath = buildFlowPath(
                origin, origin.getSrcSwitch(), origin.getDestSwitch(), new FlowSegmentCookie(
                        FlowPathDirection.FORWARD, cookieFactory.next()));
        FlowPath goalReversePath = buildFlowPath(
                origin, origin.getDestSwitch(), origin.getSrcSwitch(), new FlowSegmentCookie(
                        FlowPathDirection.REVERSE, cookieFactory.next()));
        setSegmentsWithTransitSwitches(goalForwardPath, goalReversePath);

        // than new version of flow is created to fulfill update request
        Flow goal = Flow.builder()
                .flowId(origin.getFlowId())
                .srcSwitch(origin.getSrcSwitch())
                .srcPort(origin.getSrcPort())
                .srcVlan(origin.getSrcVlan() + 10)  // update
                .destSwitch(origin.getDestSwitch())
                .destPort(origin.getDestPort())
                .destVlan(origin.getDestVlan())
                .bandwidth(origin.getBandwidth())
                .encapsulationType(origin.getEncapsulationType())
                .build();

        // emulate db behaviour - flow will have "existing" paths after fetching it from DB
        goal.setForwardPath(origin.getForwardPath());
        goal.setReversePath(origin.getReversePath());
        goal.addPaths(goalForwardPath, goalReversePath);

        // then produce path segment request factories
        List<FlowSegmentRequestFactory> commands = target.buildIngressOnly(
                COMMAND_CONTEXT, goal, goalForwardPath, goalReversePath, SpeakerRequestBuildContext.EMPTY);
        boolean haveMatch = false;
        for (FlowSegmentRequestFactory entry : commands) {
            // search command for flow source side
            if (SWITCH_1.equals(entry.getSwitchId())) {
                haveMatch = true;
                Assert.assertTrue(entry instanceof IngressFlowSegmentRequestFactory);

                IngressFlowSegmentRequestFactory segment = (IngressFlowSegmentRequestFactory) entry;
                IngressFlowSegmentRequest request = segment.makeInstallRequest(commandIdGenerator.generate());
                Assert.assertEquals(goal.getSrcVlan(), request.getEndpoint().getOuterVlanId());
            }
        }

        Assert.assertTrue(haveMatch);
    }

    private void commonIngressCommandTest(int bandwidth) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_2).build();

        Flow flow = buildFlow(srcSwitch, 1, 101, destSwitch, 2, 102, bandwidth);
        setSegmentsWithoutTransitSwitches(
                Objects.requireNonNull(flow.getForwardPath()), Objects.requireNonNull(flow.getReversePath()));

        List<FlowSegmentRequestFactory> commands = target.buildIngressOnly(
                COMMAND_CONTEXT, flow, SpeakerRequestBuildContext.EMPTY);
        assertEquals(2, commands.size());

        verifyForwardIngressRequest(flow, commands.get(0).makeInstallRequest(commandIdGenerator.generate()));
        verifyReverseIngressRequest(flow, commands.get(1).makeInstallRequest(commandIdGenerator.generate()));
    }

    private IngressFlowSegmentRequest verifyForwardIngressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        IngressFlowSegmentRequest request = verifyCommonIngressRequest(flow, path, rawRequest);

        assertEquals(flow.getSrcSwitchId(), request.getSwitchId());
        FlowEndpoint endpoint = new FlowEndpoint(
                flow.getSrcSwitchId(), flow.getSrcPort(), flow.getSrcVlan());
        assertEquals(endpoint, request.getEndpoint());

        return request;
    }

    private IngressFlowSegmentRequest verifyReverseIngressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        IngressFlowSegmentRequest request = verifyCommonIngressRequest(flow, path, rawRequest);

        assertEquals(flow.getDestSwitchId(), request.getSwitchId());
        FlowEndpoint endpoint = new FlowEndpoint(
                flow.getDestSwitchId(), flow.getDestPort(), flow.getDestVlan());
        assertEquals(endpoint, request.getEndpoint());

        return request;
    }

    private IngressFlowSegmentRequest verifyCommonIngressRequest(
            Flow flow, FlowPath path, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be egress segment request", rawRequest, instanceOf(IngressFlowSegmentRequest.class));
        IngressFlowSegmentRequest request = (IngressFlowSegmentRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getCookie(), request.getCookie());
        assertEquals(path.getSegments().get(0).getSrcPort(), (int) request.getIslPort());

        if (0 < flow.getBandwidth()) {
            MeterConfig config = new MeterConfig(path.getMeterId(), flow.getBandwidth());
            assertEquals(config, request.getMeterConfig());
        } else {
            assertNull(request.getMeterConfig());
        }

        verifyVlanEncapsulation(flow, path, request.getEncapsulation());

        return request;
    }

    private void verifyForwardTransitRequest(Flow flow, SwitchId datapath, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        verifyCommonTransitRequest(flow, path, datapath, rawRequest);
    }

    private void verifyReverseTransitRequest(Flow flow, SwitchId datapath, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        verifyCommonTransitRequest(flow, path, datapath, rawRequest);
    }

    private TransitFlowSegmentRequest verifyCommonTransitRequest(
            Flow flow, FlowPath path, SwitchId datapath, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be egress segment request", rawRequest, instanceOf(TransitFlowSegmentRequest.class));
        TransitFlowSegmentRequest request = (TransitFlowSegmentRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getCookie(), request.getCookie());
        assertEquals(SWITCH_2, request.getSwitchId());

        PathSegment ingress = null;
        PathSegment egress = null;
        for (PathSegment segment : path.getSegments()) {
            if (datapath.equals(segment.getDestSwitchId())) {
                ingress = segment;
            } else if (datapath.equals(segment.getSrcSwitchId())) {
                egress = segment;
            }
        }

        assertNotNull(ingress);
        assertNotNull(egress);

        assertEquals(ingress.getDestPort(), (int) request.getIngressIslPort());
        assertEquals(egress.getSrcPort(), (int) request.getEgressIslPort());

        verifyVlanEncapsulation(flow, path, request.getEncapsulation());
        return request;
    }

    private void verifyForwardEgressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        EgressFlowSegmentRequest request = verifyCommonEgressRequest(flow, path, rawRequest);

        FlowEndpoint expectedEndpoint = new FlowEndpoint(
                flow.getDestSwitchId(), flow.getDestPort(), flow.getDestVlan());
        assertEquals(expectedEndpoint, request.getEndpoint());

        FlowEndpoint expectedIngressEndpoint = new FlowEndpoint(
                flow.getSrcSwitchId(), flow.getSrcPort(), flow.getSrcVlan());
        assertEquals(expectedIngressEndpoint, request.getIngressEndpoint());
    }

    private void verifyReverseEgressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        EgressFlowSegmentRequest request = verifyCommonEgressRequest(flow, path, rawRequest);

        FlowEndpoint expectedEndpoint = new FlowEndpoint(
                flow.getSrcSwitchId(), flow.getSrcPort(), flow.getSrcVlan());
        assertEquals(expectedEndpoint, request.getEndpoint());

        FlowEndpoint expectedIngressEndpoint = new FlowEndpoint(
                flow.getDestSwitchId(), flow.getDestPort(), flow.getDestVlan());
        assertEquals(expectedIngressEndpoint, request.getIngressEndpoint());
    }

    private EgressFlowSegmentRequest verifyCommonEgressRequest(
            Flow flow, FlowPath path, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be egress segment request", rawRequest, instanceOf(EgressFlowSegmentRequest.class));
        EgressFlowSegmentRequest request = (EgressFlowSegmentRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getDestSwitchId(), request.getSwitchId());
        assertEquals(path.getCookie(), request.getCookie());
        assertEquals(path.getSegments().get(path.getSegments().size() - 1).getDestPort(), (int) request.getIslPort());

        verifyVlanEncapsulation(flow, path, request.getEncapsulation());

        return request;
    }

    private void verifyForwardOneSwitchRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        OneSwitchFlowRequest request = verifyCommonOneSwitchRequest(flow, path, rawRequest);

        assertEquals(
                new FlowEndpoint(flow.getSrcSwitchId(), flow.getSrcPort(), flow.getSrcVlan()),
                request.getEndpoint());
        assertEquals(
                new FlowEndpoint(flow.getDestSwitchId(), flow.getDestPort(), flow.getDestVlan()),
                request.getEgressEndpoint());
    }

    private void verifyReverseOneSwitchRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        OneSwitchFlowRequest request = verifyCommonOneSwitchRequest(flow, path, rawRequest);

        assertEquals(
                new FlowEndpoint(flow.getDestSwitchId(), flow.getDestPort(), flow.getDestVlan()),
                request.getEndpoint());
        assertEquals(
                new FlowEndpoint(flow.getSrcSwitchId(), flow.getSrcPort(), flow.getSrcVlan()),
                request.getEgressEndpoint());
    }

    private OneSwitchFlowRequest verifyCommonOneSwitchRequest(
            Flow flow, FlowPath path, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be one switch flow request", rawRequest, instanceOf(OneSwitchFlowRequest.class));
        OneSwitchFlowRequest request = (OneSwitchFlowRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getCookie(), request.getCookie());

        return request;
    }

    private void verifyVlanEncapsulation(Flow flow, FlowPath path, FlowTransitEncapsulation encapsulation) {
        assertEquals(FlowEncapsulationType.TRANSIT_VLAN, encapsulation.getType());
        TransitVlan transitVlan = vlanRepository.findByPathId(path.getPathId(),
                flow.getOppositePathId(path.getPathId()).orElse(null))
                .stream().findAny()
                .orElseThrow(() -> new IllegalStateException("Vlan should be present"));
        assertEquals(transitVlan.getVlan(), (int) encapsulation.getId());
    }

    private void setSegmentsWithoutTransitSwitches(FlowPath forward, FlowPath reverse) {
        PathSegment switch1ToSwitch2 = PathSegment.builder()
                .pathId(forward.getPathId())
                .srcSwitch(forward.getSrcSwitch())
                .srcPort(12)
                .destSwitch(forward.getDestSwitch())
                .destPort(22)
                .build();
        forward.setSegments(ImmutableList.of(switch1ToSwitch2));
        PathSegment switch2ToSwitch1 = PathSegment.builder()
                .pathId(reverse.getPathId())
                .srcSwitch(reverse.getSrcSwitch())
                .srcPort(22)
                .destSwitch(reverse.getDestSwitch())
                .destPort(12)
                .build();
        reverse.setSegments(ImmutableList.of(switch2ToSwitch1));
    }

    private void setSegmentsWithTransitSwitches(FlowPath forward, FlowPath reverse) {
        PathSegment switch1ToSwitch2 = PathSegment.builder()
                .pathId(forward.getPathId())
                .srcSwitch(forward.getSrcSwitch())
                .srcPort(12)
                .destSwitch(Switch.builder().switchId(SWITCH_2).build())
                .destPort(21)
                .build();
        PathSegment switch2ToSwitch3 = PathSegment.builder()
                .pathId(forward.getPathId())
                .srcSwitch(Switch.builder().switchId(SWITCH_2).build())
                .srcPort(23)
                .destSwitch(forward.getDestSwitch())
                .destPort(32)
                .build();
        forward.setSegments(ImmutableList.of(switch1ToSwitch2, switch2ToSwitch3));

        PathSegment switch3ToSwitch2 = PathSegment.builder()
                .pathId(reverse.getPathId())
                .srcSwitch(reverse.getSrcSwitch())
                .srcPort(32)
                .destSwitch(Switch.builder().switchId(SWITCH_2).build())
                .destPort(23)
                .build();
        PathSegment switch2ToSwitch1 = PathSegment.builder()
                .pathId(reverse.getPathId())
                .srcSwitch(Switch.builder().switchId(SWITCH_2).build())
                .srcPort(21)
                .destSwitch(reverse.getDestSwitch())
                .destPort(12)
                .build();
        reverse.setSegments(ImmutableList.of(switch3ToSwitch2, switch2ToSwitch1));
    }

    private FlowPath buildFlowPath(Flow flow, Switch srcSwitch, Switch dstSwitch, FlowSegmentCookie cookie) {
        PathId forwardPathId = new PathId(UUID.randomUUID().toString());
        TransitVlan forwardVlan = TransitVlan.builder()
                .flowId(flow.getFlowId())
                .pathId(forwardPathId)
                .vlan(vlanFactory.next())
                .build();
        vlanRepository.add(forwardVlan);
        return FlowPath.builder()
                .bandwidth(flow.getBandwidth())
                .cookie(cookie)
                .meterId(flow.getBandwidth() != 0 ? new MeterId(meterFactory.next()) : null)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .pathId(forwardPathId)
                .build();
    }

    private Flow buildFlow(Switch srcSwitch, int srcPort, int srcVlan, Switch dstSwitch, int dstPort, int dstVlan,
                           int bandwidth) {
        Flow flow = Flow.builder()
                .flowId(UUID.randomUUID().toString())
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .srcVlan(srcVlan)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .destVlan(dstVlan)
                .bandwidth(bandwidth)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .build();

        Long rawCookie = cookieFactory.next();

        flow.setForwardPath(buildFlowPath(
                flow, flow.getSrcSwitch(), flow.getDestSwitch(), new FlowSegmentCookie(
                        FlowPathDirection.FORWARD, rawCookie)));
        flow.setReversePath(buildFlowPath(
                flow, flow.getDestSwitch(), flow.getSrcSwitch(), new FlowSegmentCookie(
                        FlowPathDirection.REVERSE, rawCookie)));

        return flow;
    }
}
