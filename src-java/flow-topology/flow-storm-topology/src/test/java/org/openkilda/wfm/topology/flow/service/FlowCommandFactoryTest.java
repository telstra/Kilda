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

package org.openkilda.wfm.topology.flow.service;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.InstallEgressFlow;
import org.openkilda.messaging.command.flow.InstallIngressFlow;
import org.openkilda.messaging.command.flow.InstallOneSwitchFlow;
import org.openkilda.messaging.command.flow.InstallTransitFlow;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.PathSegment;
import org.openkilda.model.PathSegment.PathSegmentBuilder;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.wfm.share.flow.TestFlowBuilder;
import org.openkilda.wfm.share.flow.service.FlowCommandFactory;
import org.openkilda.wfm.topology.flow.model.FlowPathsWithEncapsulation;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class FlowCommandFactoryTest {
    private static final String TEST_FLOW = "test-flow";
    private static final long TEST_COOKIE = FlowSegmentCookieSchema.INSTANCE.make(
            1, FlowPathDirection.FORWARD).getValue();
    private static final int METER_ID = 42;
    private static final SwitchId SWITCH_ID_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_ID_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final SwitchId SWITCH_ID_3 = new SwitchId("00:00:00:00:00:00:00:03");
    private static final SwitchId SWITCH_ID_4 = new SwitchId("00:00:00:00:00:00:00:04");

    private FlowCommandFactory factory;

    @Before
    public void setup() {
        factory = new FlowCommandFactory();
    }

    @Test
    public void shouldCreateInstallRulesFor1SegmentPathTransitVlan() {
        shouldCreateInstallRulesFor1SegmentPath(FlowEncapsulationType.TRANSIT_VLAN);
    }

    @Test
    public void shouldCreateInstallRulesFor1SegmentPathVxlan() {
        shouldCreateInstallRulesFor1SegmentPath(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateInstallRulesFor1SegmentPath(FlowEncapsulationType encapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_ID_2).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(destSwitch)
                .destPort(21);

        FlowPathsWithEncapsulation flow = buildFlowPathsWithEncapsulation(srcSwitch, 1, 101,
                destSwitch, 2, 201, 301, 0, true,
                asList(segment1to2), encapsulationType);

        List<BaseInstallFlow> rules = factory.createInstallLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertThat(rules, hasSize(1));
        assertThat(rules, everyItem(hasProperty("cookie", equalTo(TEST_COOKIE))));
        assertEquals(SWITCH_ID_2, rules.get(0).getSwitchId());
        assertEquals(21, (int) rules.get(0).getInputPort());
        assertEquals(2, (int) rules.get(0).getOutputPort());
        assertEquals(301, (int) ((InstallTransitFlow) rules.get(0)).getTransitEncapsulationId());
        assertEquals(201, (int) ((InstallEgressFlow) rules.get(0)).getOutputVlanId());

        BaseInstallFlow ingressRule = factory.createInstallIngressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getInputPort());
        assertEquals(11, (int) ingressRule.getOutputPort());
        assertEquals(101, (int) ((InstallIngressFlow) ingressRule).getInputVlanId());
    }


    @Test
    public void shouldCreateRemoveRulesFor1SegmentPathTransitVlan() {
        shouldCreateRemoveRulesFor1SegmentPath(FlowEncapsulationType.TRANSIT_VLAN);
    }

    @Test
    public void shouldCreateRemoveRulesFor1SegmentPathVxlan() {
        shouldCreateRemoveRulesFor1SegmentPath(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateRemoveRulesFor1SegmentPath(FlowEncapsulationType encapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_ID_2).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(destSwitch)
                .destPort(21);

        FlowPathsWithEncapsulation flow = buildFlowPathsWithEncapsulation(srcSwitch, 1, 101,
                destSwitch, 2, 201, 301, 0, true,
                asList(segment1to2), encapsulationType);

        List<RemoveFlow> rules = factory.createRemoveLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertThat(rules, hasSize(1));
        assertThat(rules, everyItem(hasProperty("criteria",
                hasProperty("cookie", equalTo(TEST_COOKIE)))));
        assertEquals(SWITCH_ID_2, rules.get(0).getSwitchId());
        assertEquals(21, (int) rules.get(0).getCriteria().getInPort());
        assertEquals(2, (int) rules.get(0).getCriteria().getOutPort());
        assertEquals(301, (int) rules.get(0).getCriteria().getEncapsulationId());
        assertEquals(flow.getForwardPath().getDestSwitch().getSwitchId(),
                rules.get(0).getCriteria().getEgressSwitchId());

        RemoveFlow ingressRule = factory.createRemoveIngressRulesForFlow(flow.getForwardPath(), false, false, false);
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getCriteria().getInPort());
        assertEquals(11, (int) ingressRule.getCriteria().getOutPort());
        assertEquals(null, ingressRule.getCriteria().getEgressSwitchId());
    }


    @Test
    public void shouldCreateInstallRulesFor2SegmentsPathTransitVlan() {
        shouldCreateInstallRulesFor2SegmentsPath(FlowEncapsulationType.TRANSIT_VLAN);
    }

    @Test
    public void shouldCreateInstallRulesFor2SegmentsPathVxlan() {
        shouldCreateInstallRulesFor2SegmentsPath(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateInstallRulesFor2SegmentsPath(FlowEncapsulationType encapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch switch2 = Switch.builder().switchId(SWITCH_ID_2).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_ID_3).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(switch2)
                .destPort(21);
        PathSegment.PathSegmentBuilder segment2to3 = PathSegment.builder()
                .srcSwitch(switch2)
                .srcPort(22)
                .destSwitch(destSwitch)
                .destPort(31);

        FlowPathsWithEncapsulation flow = buildFlowPathsWithEncapsulation(srcSwitch, 1, 101,
                destSwitch, 2, 201, 301, 0, true,
                asList(segment1to2, segment2to3), encapsulationType);

        List<BaseInstallFlow> rules = factory.createInstallLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertThat(rules, hasSize(2));
        assertThat(rules, everyItem(hasProperty("cookie", equalTo(TEST_COOKIE))));
        assertEquals(SWITCH_ID_2, rules.get(0).getSwitchId());
        assertEquals(21, (int) rules.get(0).getInputPort());
        assertEquals(22, (int) rules.get(0).getOutputPort());
        assertEquals(301, (int) ((InstallTransitFlow) rules.get(0)).getTransitEncapsulationId());

        assertEquals(SWITCH_ID_3, rules.get(1).getSwitchId());
        assertEquals(31, (int) rules.get(1).getInputPort());
        assertEquals(2, (int) rules.get(1).getOutputPort());
        assertEquals(301, (int) ((InstallTransitFlow) rules.get(1)).getTransitEncapsulationId());
        assertEquals(201, (int) ((InstallEgressFlow) rules.get(1)).getOutputVlanId());

        BaseInstallFlow ingressRule = factory.createInstallIngressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getInputPort());
        assertEquals(11, (int) ingressRule.getOutputPort());
        assertEquals(101, (int) ((InstallIngressFlow) ingressRule).getInputVlanId());
    }


    @Test
    public void shouldCreateInstallRulesFor3SegmentsPathTransitVlan() {
        shouldCreateInstallRulesFor3SegmentsPath(FlowEncapsulationType.TRANSIT_VLAN);
    }

    @Test
    public void shouldCreateInstallRulesFor3SegmentsPathVxlan() {
        shouldCreateInstallRulesFor3SegmentsPath(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateInstallRulesFor3SegmentsPath(FlowEncapsulationType encapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch switch2 = Switch.builder().switchId(SWITCH_ID_2).build();
        Switch switch3 = Switch.builder().switchId(SWITCH_ID_3).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_ID_4).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(switch2)
                .destPort(21);
        PathSegment.PathSegmentBuilder segment2to3 = PathSegment.builder()
                .srcSwitch(switch2)
                .srcPort(22)
                .destSwitch(switch3)
                .destPort(31);
        PathSegment.PathSegmentBuilder segment3to4 = PathSegment.builder()
                .srcSwitch(switch3)
                .srcPort(32)
                .destSwitch(destSwitch)
                .destPort(41);

        FlowPathsWithEncapsulation flow = buildFlowPathsWithEncapsulation(srcSwitch, 1, 101,
                destSwitch, 2, 201, 301, 0, true,
                asList(segment1to2, segment2to3, segment3to4), encapsulationType);

        List<BaseInstallFlow> rules = factory.createInstallLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertThat(rules, hasSize(3));
        assertThat(rules, everyItem(hasProperty("cookie", equalTo(TEST_COOKIE))));
        assertEquals(SWITCH_ID_2, rules.get(0).getSwitchId());
        assertEquals(21, (int) rules.get(0).getInputPort());
        assertEquals(22, (int) rules.get(0).getOutputPort());
        assertEquals(301, (int) ((InstallTransitFlow) rules.get(0)).getTransitEncapsulationId());

        assertEquals(SWITCH_ID_3, rules.get(1).getSwitchId());
        assertEquals(31, (int) rules.get(1).getInputPort());
        assertEquals(32, (int) rules.get(1).getOutputPort());
        assertEquals(301, (int) ((InstallTransitFlow) rules.get(1)).getTransitEncapsulationId());

        assertEquals(SWITCH_ID_4, rules.get(2).getSwitchId());
        assertEquals(41, (int) rules.get(2).getInputPort());
        assertEquals(2, (int) rules.get(2).getOutputPort());
        assertEquals(301, (int) ((InstallTransitFlow) rules.get(2)).getTransitEncapsulationId());
        assertEquals(201, (int) ((InstallEgressFlow) rules.get(2)).getOutputVlanId());

        BaseInstallFlow ingressRule = factory.createInstallIngressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getInputPort());
        assertEquals(11, (int) ingressRule.getOutputPort());
        assertEquals(101, (int) ((InstallIngressFlow) ingressRule).getInputVlanId());
    }

    @Test
    public  void shouldCreateRemoveRulesFor2SegmentsPathTransitVlan() {
        shouldCreateRemoveRulesFor2SegmentsPath(FlowEncapsulationType.TRANSIT_VLAN);
    }

    @Test
    public  void shouldCreateRemoveRulesFor2SegmentsPathVxlan() {
        shouldCreateRemoveRulesFor2SegmentsPath(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateRemoveRulesFor2SegmentsPath(FlowEncapsulationType flowEncapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch switch2 = Switch.builder().switchId(SWITCH_ID_2).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_ID_3).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(switch2)
                .destPort(21);
        PathSegment.PathSegmentBuilder segment2to3 = PathSegment.builder()
                .srcSwitch(switch2)
                .srcPort(22)
                .destSwitch(destSwitch)
                .destPort(31);

        FlowPathsWithEncapsulation flow = buildFlowPathsWithEncapsulation(srcSwitch, 1, 101,
                destSwitch, 2, 201, 301, 0, true,
                asList(segment1to2, segment2to3), flowEncapsulationType);

        List<RemoveFlow> rules = factory.createRemoveLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertThat(rules, hasSize(2));
        assertThat(rules, everyItem(hasProperty("criteria",
                hasProperty("cookie", equalTo(TEST_COOKIE)))));
        assertEquals(SWITCH_ID_2, rules.get(0).getSwitchId());
        assertEquals(21, (int) rules.get(0).getCriteria().getInPort());
        assertEquals(22, (int) rules.get(0).getCriteria().getOutPort());
        assertEquals(301, (int) rules.get(0).getCriteria().getEncapsulationId());
        assertEquals(null, rules.get(0).getCriteria().getEgressSwitchId());


        assertEquals(SWITCH_ID_3, rules.get(1).getSwitchId());
        assertEquals(31, (int) rules.get(1).getCriteria().getInPort());
        assertEquals(2, (int) rules.get(1).getCriteria().getOutPort());
        assertEquals(301, (int) rules.get(1).getCriteria().getEncapsulationId());
        assertEquals(flow.getForwardPath().getDestSwitch().getSwitchId(),
                rules.get(1).getCriteria().getEgressSwitchId());

        RemoveFlow ingressRule = factory.createRemoveIngressRulesForFlow(flow.getForwardPath(), false, false, false);
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getCriteria().getInPort());
        assertEquals(11, (int) ingressRule.getCriteria().getOutPort());
        assertEquals(101, (int) ingressRule.getCriteria().getEncapsulationId());
        assertEquals(null, ingressRule.getCriteria().getEgressSwitchId());

    }

    @Test
    public void shouldCreateRemoveRulesFor3SegmentsPathTransitVlan() {
        shouldCreateRemoveRulesFor3SegmentsPath(FlowEncapsulationType.TRANSIT_VLAN);
    }

    @Test
    public void shouldCreateRemoveRulesFor3SegmentsPathTransitVxlan() {
        shouldCreateRemoveRulesFor3SegmentsPath(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateRemoveRulesFor3SegmentsPath(FlowEncapsulationType encapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch switch2 = Switch.builder().switchId(SWITCH_ID_2).build();
        Switch switch3 = Switch.builder().switchId(SWITCH_ID_3).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_ID_4).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(switch2)
                .destPort(21);
        PathSegment.PathSegmentBuilder segment2to3 = PathSegment.builder()
                .srcSwitch(switch2)
                .srcPort(22)
                .destSwitch(switch3)
                .destPort(31);
        PathSegment.PathSegmentBuilder segment3to4 = PathSegment.builder()
                .srcSwitch(switch3)
                .srcPort(32)
                .destSwitch(destSwitch)
                .destPort(41);

        FlowPathsWithEncapsulation flow = buildFlowPathsWithEncapsulation(srcSwitch, 1, 101,
                destSwitch, 2, 201, 301, 0, true,
                asList(segment1to2, segment2to3, segment3to4), FlowEncapsulationType.TRANSIT_VLAN);

        List<RemoveFlow> rules = factory.createRemoveLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), flow.getForwardEncapsulation());
        assertThat(rules, hasSize(3));
        assertThat(rules, everyItem(hasProperty("criteria",
                hasProperty("cookie", equalTo(TEST_COOKIE)))));
        assertEquals(SWITCH_ID_2, rules.get(0).getSwitchId());
        assertEquals(21, (int) rules.get(0).getCriteria().getInPort());
        assertEquals(22, (int) rules.get(0).getCriteria().getOutPort());
        assertEquals(301, (int) rules.get(0).getCriteria().getEncapsulationId());
        assertEquals(null, rules.get(0).getCriteria().getEgressSwitchId());

        assertEquals(SWITCH_ID_3, rules.get(1).getSwitchId());
        assertEquals(31, (int) rules.get(1).getCriteria().getInPort());
        assertEquals(32, (int) rules.get(1).getCriteria().getOutPort());
        assertEquals(301, (int) rules.get(1).getCriteria().getEncapsulationId());
        assertEquals(null, rules.get(1).getCriteria().getEgressSwitchId());

        assertEquals(SWITCH_ID_4, rules.get(2).getSwitchId());
        assertEquals(41, (int) rules.get(2).getCriteria().getInPort());
        assertEquals(2, (int) rules.get(2).getCriteria().getOutPort());
        assertEquals(301, (int) rules.get(2).getCriteria().getEncapsulationId());
        assertEquals(flow.getForwardPath().getDestSwitch().getSwitchId(),
                rules.get(2).getCriteria().getEgressSwitchId());

        RemoveFlow ingressRule = factory.createRemoveIngressRulesForFlow(flow.getForwardPath(), false, false, false);
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getCriteria().getInPort());
        assertEquals(11, (int) ingressRule.getCriteria().getOutPort());
        assertEquals(101, (int) ingressRule.getCriteria().getEncapsulationId());
        assertEquals(null, ingressRule.getCriteria().getEgressSwitchId());
    }

    @Test
    public void shouldCreateRemoveRulesWithoutMeterTransitVlan() {
        shouldCreateRemoveRulesWithoutMeter(FlowEncapsulationType.TRANSIT_VLAN);

    }

    @Test
    public void shouldCreateRemoveRulesWithoutMeterTransitVxlan() {
        shouldCreateRemoveRulesWithoutMeter(FlowEncapsulationType.VXLAN);
    }

    private void shouldCreateRemoveRulesWithoutMeter(FlowEncapsulationType encapsulationType) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_ID_1).build();
        Switch dstSwitch = Switch.builder().switchId(SWITCH_ID_2).build();

        PathSegment.PathSegmentBuilder segment1to2 = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(11)
                .destSwitch(dstSwitch)
                .destPort(21);

        Flow flow = buildFlow(srcSwitch, 1, 101,
                dstSwitch, 2, 201, 301, 0, true,
                asList(segment1to2), encapsulationType);
        flow.getForwardPath().setMeterId(null);

        RemoveFlow command = factory.createRemoveIngressRulesForFlow(flow.getForwardPath(), false, false, false);

        assertEquals(SWITCH_ID_1, command.getSwitchId());
        assertEquals(1, (int) command.getCriteria().getInPort());
        assertEquals(11, (int) command.getCriteria().getOutPort());
        assertEquals(101, (int) command.getCriteria().getEncapsulationId());
        assertEquals(FlowEncapsulationType.TRANSIT_VLAN, command.getCriteria().getEncapsulationType());
        assertNull(command.getCriteria().getEgressSwitchId());
        assertNull(command.getMeterId());
    }

    @Test
    public void shouldCreateInstallRulesForSingleSwitch() {
        Switch theSwitch = Switch.builder().switchId(SWITCH_ID_1).build();

        Flow flow = buildFlow(theSwitch, 1, 101,
                theSwitch, 2, 201, 0, 0, true,
                Collections.emptyList(), null);

        FlowCommandFactory factory = new FlowCommandFactory();
        List<BaseInstallFlow> rules =
                factory.createInstallLldpTransitAndEgressRulesForFlow(
                        flow.getForwardPath(), null);
        assertThat(rules, hasSize(0));

        BaseInstallFlow ingressRule = factory.createInstallIngressRulesForFlow(
                flow.getForwardPath(), null);
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getInputPort());
        assertEquals(101, (int) ((InstallOneSwitchFlow) ingressRule).getInputVlanId());
        assertEquals(2, (int) ingressRule.getOutputPort());
        assertEquals(201, (int) ((InstallOneSwitchFlow) ingressRule).getOutputVlanId());
    }

    @Test
    public void shouldCreateRemoveRulesForSingleSwitch() {
        Switch theSwitch = Switch.builder().switchId(SWITCH_ID_1).build();

        Flow flow = buildFlow(theSwitch, 1, 101,
                theSwitch, 2, 201, 0, 0, true,
                Collections.emptyList(), null);

        List<RemoveFlow> rules = factory.createRemoveLldpTransitAndEgressRulesForFlow(
                flow.getForwardPath(), null);
        assertThat(rules, hasSize(0));

        RemoveFlow ingressRule = factory.createRemoveIngressRulesForFlow(flow.getForwardPath(), false, false, false);
        assertEquals(TEST_COOKIE, (long) ingressRule.getCookie());
        assertEquals(SWITCH_ID_1, ingressRule.getSwitchId());
        assertEquals(1, (int) ingressRule.getCriteria().getInPort());
        assertNull(ingressRule.getCriteria().getOutPort());
        assertEquals(101, (int) ingressRule.getCriteria().getEncapsulationId());
        assertNull(ingressRule.getCriteria().getEgressSwitchId());
    }

    private Flow buildFlow(Switch srcSwitch, int srcPort, int srcVlan,
                                         Switch destSwitch, int destPort, int destVlan,
                                         int transitEncapsulationId, int bandwidth,
                                         boolean ignoreBandwidth, List<PathSegment.PathSegmentBuilder> pathSegments,
                                         FlowEncapsulationType flowEncapsulationType) {
        Flow flow = new TestFlowBuilder(TEST_FLOW)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .srcVlan(srcVlan)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .destVlan(destVlan)
                .bandwidth(bandwidth)
                .ignoreBandwidth(ignoreBandwidth)
                .cookie(TEST_COOKIE)
                .meterId(METER_ID)
                .transitEncapsulationId(transitEncapsulationId)
                .encapsulationType(flowEncapsulationType)
                .build();

        flow.getForwardPath().setSegments(pathSegments.stream()
                .map(PathSegmentBuilder::build)
                .collect(Collectors.toList()));

        return flow;
    }

    private FlowPathsWithEncapsulation buildFlowPathsWithEncapsulation(
            Switch srcSwitch, int srcPort, int srcVlan, Switch destSwitch, int destPort, int destVlan,
            int transitEncapsulationId, int bandwidth, boolean ignoreBandwidth,
            List<PathSegment.PathSegmentBuilder> pathSegments,
            FlowEncapsulationType flowEncapsulationType) {
        FlowPathsWithEncapsulation flowPathsWithEncapsulation = new TestFlowBuilder(TEST_FLOW)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .srcVlan(srcVlan)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .destVlan(destVlan)
                .bandwidth(bandwidth)
                .ignoreBandwidth(ignoreBandwidth)
                .cookie(TEST_COOKIE)
                .meterId(METER_ID)
                .transitEncapsulationId(transitEncapsulationId)
                .encapsulationType(flowEncapsulationType)
                .buildFlowPathsWithEncapsulation();

        flowPathsWithEncapsulation.getForwardPath().setSegments(pathSegments.stream()
                .map(PathSegmentBuilder::build)
                .collect(Collectors.toList()));

        return flowPathsWithEncapsulation;
    }
}
