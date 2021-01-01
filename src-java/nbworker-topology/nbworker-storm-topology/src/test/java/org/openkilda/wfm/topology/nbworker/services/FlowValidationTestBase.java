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

package org.openkilda.wfm.topology.nbworker.services;

import org.openkilda.messaging.info.meter.MeterEntry;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.rule.FlowMatchField;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Meter;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.Vxlan;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FlowValidationTestBase extends InMemoryGraphBasedTest {
    protected static final SwitchId TEST_SWITCH_ID_A = new SwitchId(1);
    protected static final SwitchId TEST_SWITCH_ID_B = new SwitchId(2);
    protected static final SwitchId TEST_SWITCH_ID_C = new SwitchId(3);
    protected static final SwitchId TEST_SWITCH_ID_D = new SwitchId(4);
    protected static final SwitchId TEST_SWITCH_ID_E = new SwitchId(5);
    protected static final String TEST_FLOW_ID_A = "test_flow_id_a";
    protected static final String TEST_FLOW_ID_B = "test_flow_id_b";

    protected static final long MIN_BURST_SIZE_IN_KBITS = 1024;
    protected static final double BURST_COEFFICIENT = 1.05;

    private static final int FLOW_A_SRC_PORT = 10;
    private static final int FLOW_A_DST_PORT = 20;
    private static final int FLOW_A_SEGMENT_A_SRC_PORT = 11;
    private static final int FLOW_A_SEGMENT_A_DST_PORT = 15;
    private static final int FLOW_A_SEGMENT_A_SRC_PORT_PROTECTED = 21;
    private static final int FLOW_A_SEGMENT_A_DST_PORT_PROTECTED = 25;
    private static final int FLOW_A_SEGMENT_B_SRC_PORT = 16;
    private static final int FLOW_A_SEGMENT_B_DST_PORT = 19;
    private static final int FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED = 26;
    private static final int FLOW_A_SEGMENT_B_DST_PORT_PROTECTED = 29;
    private static final int FLOW_A_SRC_VLAN = 11;
    private static final int FLOW_A_ENCAP_ID = 12;
    private static final PathId FLOW_A_FORWARD_PATH_ID = new PathId(TEST_FLOW_ID_A + "_forward_path");
    private static final PathId FLOW_A_REVERSE_PATH_ID = new PathId(TEST_FLOW_ID_A + "_reverse_path");
    private static final PathId FLOW_A_FORWARD_PATH_ID_PROTECTED = new PathId(TEST_FLOW_ID_A + "_forward_protect_path");
    private static final PathId FLOW_A_REVERSE_PATH_ID_PROTECTED = new PathId(TEST_FLOW_ID_A + "_reverse_protect_path");
    private static final int FLOW_A_ENCAP_ID_PROTECTED = 22;
    private static final int FLOW_A_DST_VLAN = 14;
    private static final int FLOW_A_FORWARD_METER_ID = 32;
    private static final int FLOW_A_REVERSE_METER_ID = 33;
    private static final int FLOW_A_FORWARD_METER_ID_PROTECTED = 42;
    private static final int FLOW_A_REVERSE_METER_ID_PROTECTED = 43;
    private static final long FLOW_A_FORWARD_COOKIE = new FlowSegmentCookie(FlowPathDirection.FORWARD, 1L).getValue();
    private static final long FLOW_A_REVERSE_COOKIE = new FlowSegmentCookie(FlowPathDirection.REVERSE, 1L).getValue();
    private static final long FLOW_A_FORWARD_COOKIE_PROTECTED = new FlowSegmentCookie(
            FlowPathDirection.FORWARD, 2L).getValue();
    private static final long FLOW_A_REVERSE_COOKIE_PROTECTED = new FlowSegmentCookie(
            FlowPathDirection.REVERSE, 2L).getValue();
    private static final long FLOW_A_BANDWIDTH = 10000;
    private static final int FLOW_B_SRC_PORT = 1;
    private static final int FLOW_B_SRC_VLAN = 15;
    private static final int FLOW_B_DST_VLAN = 16;
    private static final long FLOW_B_FORWARD_COOKIE = new FlowSegmentCookie(FlowPathDirection.FORWARD, 2L).getValue();
    private static final long FLOW_B_REVERSE_COOKIE = new FlowSegmentCookie(FlowPathDirection.REVERSE, 2L).getValue();
    private static final int FLOW_B_FORWARD_METER_ID = 34;
    private static final int FLOW_B_REVERSE_METER_ID = 35;
    private static final long FLOW_B_BANDWIDTH = 11000;

    protected static FlowResourcesConfig flowResourcesConfig;
    protected static FlowRepository flowRepository;
    private static SwitchRepository switchRepository;
    private static TransitVlanRepository transitVlanRepository;
    private static VxlanRepository vxlanRepository;

    protected static void setUpOnce() {
        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        transitVlanRepository = persistenceManager.getRepositoryFactory().createTransitVlanRepository();
        vxlanRepository = persistenceManager.getRepositoryFactory().createVxlanRepository();
        flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
    }

    protected void buildTransitVlanFlow(String endpointSwitchManufacturer) {
        buildFlow(FlowEncapsulationType.TRANSIT_VLAN, endpointSwitchManufacturer);

        TransitVlan transitVlan = TransitVlan.builder()
                .flowId(TEST_FLOW_ID_A)
                .pathId(FLOW_A_FORWARD_PATH_ID)
                .vlan(FLOW_A_ENCAP_ID)
                .build();
        transitVlanRepository.add(transitVlan);

        TransitVlan transitVlanProtected = TransitVlan.builder()
                .flowId(TEST_FLOW_ID_A)
                .pathId(FLOW_A_FORWARD_PATH_ID_PROTECTED)
                .vlan(FLOW_A_ENCAP_ID_PROTECTED)
                .build();
        transitVlanRepository.add(transitVlanProtected);
    }

    protected void buildVxlanFlow() {
        buildFlow(FlowEncapsulationType.VXLAN, "");

        Vxlan vxlan = Vxlan.builder()
                .flowId(TEST_FLOW_ID_A)
                .pathId(FLOW_A_FORWARD_PATH_ID)
                .vni(FLOW_A_ENCAP_ID)
                .build();
        vxlanRepository.add(vxlan);

        Vxlan vxlanProtected = Vxlan.builder()
                .flowId(TEST_FLOW_ID_A)
                .pathId(FLOW_A_FORWARD_PATH_ID_PROTECTED)
                .vni(FLOW_A_ENCAP_ID_PROTECTED)
                .build();
        vxlanRepository.add(vxlanProtected);
    }

    protected void buildFlow(FlowEncapsulationType flowEncapsulationType, String endpointSwitchManufacturer) {
        Switch switchA = Switch.builder().switchId(TEST_SWITCH_ID_A).description("").build();
        switchRepository.add(switchA);
        switchA.setOfDescriptionManufacturer(endpointSwitchManufacturer);
        Switch switchB = Switch.builder().switchId(TEST_SWITCH_ID_B).description("").build();
        switchRepository.add(switchB);
        Switch switchC = Switch.builder().switchId(TEST_SWITCH_ID_C).description("").build();
        switchRepository.add(switchC);
        Switch switchE = Switch.builder().switchId(TEST_SWITCH_ID_E).description("").build();
        switchRepository.add(switchE);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_A)
                .srcSwitch(switchA)
                .srcPort(FLOW_A_SRC_PORT)
                .srcVlan(FLOW_A_SRC_VLAN)
                .destSwitch(switchC)
                .destPort(FLOW_A_DST_PORT)
                .destVlan(FLOW_A_DST_VLAN)
                .allocateProtectedPath(true)
                .encapsulationType(flowEncapsulationType)
                .bandwidth(FLOW_A_BANDWIDTH)
                .status(FlowStatus.UP)
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(FLOW_A_FORWARD_PATH_ID)
                .cookie(new FlowSegmentCookie(FLOW_A_FORWARD_COOKIE))
                .meterId(new MeterId(FLOW_A_FORWARD_METER_ID))
                .srcSwitch(switchA)
                .destSwitch(switchC)
                .bandwidth(FLOW_A_BANDWIDTH)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegmentA = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(switchA)
                .srcPort(FLOW_A_SEGMENT_A_SRC_PORT)
                .destSwitch(switchB)
                .destPort(FLOW_A_SEGMENT_A_DST_PORT)
                .build();

        PathSegment forwardSegmentB = PathSegment.builder()
                .pathId(forwardFlowPath.getPathId())
                .srcSwitch(switchB)
                .srcPort(FLOW_A_SEGMENT_B_SRC_PORT)
                .destSwitch(switchC)
                .destPort(FLOW_A_SEGMENT_B_DST_PORT)
                .build();
        forwardFlowPath.setSegments(Lists.newArrayList(forwardSegmentA, forwardSegmentB));

        FlowPath forwardProtectedFlowPath = FlowPath.builder()
                .pathId(FLOW_A_FORWARD_PATH_ID_PROTECTED)
                .cookie(new FlowSegmentCookie(FLOW_A_FORWARD_COOKIE_PROTECTED))
                .meterId(new MeterId(FLOW_A_FORWARD_METER_ID_PROTECTED))
                .srcSwitch(switchA)
                .destSwitch(switchC)
                .bandwidth(FLOW_A_BANDWIDTH)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setProtectedForwardPath(forwardProtectedFlowPath);

        PathSegment forwardProtectedSegmentA = PathSegment.builder()
                .pathId(forwardProtectedFlowPath.getPathId())
                .srcSwitch(switchA)
                .srcPort(FLOW_A_SEGMENT_A_SRC_PORT_PROTECTED)
                .destSwitch(switchE)
                .destPort(FLOW_A_SEGMENT_A_DST_PORT_PROTECTED)
                .build();

        PathSegment forwardProtectedSegmentB = PathSegment.builder()
                .pathId(forwardProtectedFlowPath.getPathId())
                .srcSwitch(switchE)
                .srcPort(FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED)
                .destSwitch(switchC)
                .destPort(FLOW_A_SEGMENT_B_DST_PORT_PROTECTED)
                .build();
        forwardProtectedFlowPath.setSegments(Lists.newArrayList(forwardProtectedSegmentA, forwardProtectedSegmentB));

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(FLOW_A_REVERSE_PATH_ID)
                .cookie(new FlowSegmentCookie(FLOW_A_REVERSE_COOKIE))
                .meterId(new MeterId(FLOW_A_REVERSE_METER_ID))
                .srcSwitch(switchC)
                .destSwitch(switchA)
                .bandwidth(FLOW_A_BANDWIDTH)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setReversePath(reverseFlowPath);

        PathSegment reverseSegmentA = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(switchC)
                .srcPort(FLOW_A_SEGMENT_B_DST_PORT)
                .destSwitch(switchB)
                .destPort(FLOW_A_SEGMENT_B_SRC_PORT)
                .build();

        PathSegment reverseSegmentB = PathSegment.builder()
                .pathId(reverseFlowPath.getPathId())
                .srcSwitch(switchB)
                .srcPort(FLOW_A_SEGMENT_A_DST_PORT)
                .destSwitch(switchA)
                .destPort(FLOW_A_SEGMENT_A_SRC_PORT)
                .build();
        reverseFlowPath.setSegments(Lists.newArrayList(reverseSegmentA, reverseSegmentB));

        FlowPath reverseProtectedFlowPath = FlowPath.builder()
                .pathId(FLOW_A_REVERSE_PATH_ID_PROTECTED)
                .cookie(new FlowSegmentCookie(FLOW_A_REVERSE_COOKIE_PROTECTED))
                .meterId(new MeterId(FLOW_A_REVERSE_METER_ID_PROTECTED))
                .srcSwitch(switchC)
                .destSwitch(switchA)
                .bandwidth(FLOW_A_BANDWIDTH)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setProtectedReversePath(reverseProtectedFlowPath);

        PathSegment reverseProtectedSegmentA = PathSegment.builder()
                .pathId(reverseProtectedFlowPath.getPathId())
                .srcSwitch(switchC)
                .srcPort(FLOW_A_SEGMENT_B_DST_PORT_PROTECTED)
                .destSwitch(switchE)
                .destPort(FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED)
                .build();

        PathSegment reverseProtectedSegmentB = PathSegment.builder()
                .pathId(reverseProtectedFlowPath.getPathId())
                .srcSwitch(switchE)
                .srcPort(FLOW_A_SEGMENT_A_DST_PORT_PROTECTED)
                .destSwitch(switchA)
                .destPort(FLOW_A_SEGMENT_A_SRC_PORT_PROTECTED)
                .build();
        reverseProtectedFlowPath.setSegments(Lists.newArrayList(reverseProtectedSegmentA, reverseProtectedSegmentB));

        flowRepository.add(flow);
    }

    protected void buildOneSwitchPortFlow() {
        Switch switchD = Switch.builder().switchId(TEST_SWITCH_ID_D).description("").build();
        switchRepository.add(switchD);

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_B)
                .srcSwitch(switchD)
                .srcPort(FLOW_B_SRC_PORT)
                .srcVlan(FLOW_B_SRC_VLAN)
                .destSwitch(switchD)
                .destPort(FLOW_B_SRC_PORT)
                .destVlan(FLOW_B_DST_VLAN)
                .bandwidth(FLOW_B_BANDWIDTH)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .status(FlowStatus.UP)
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_B + "_forward_path"))
                .cookie(new FlowSegmentCookie(FLOW_B_FORWARD_COOKIE))
                .meterId(new MeterId(FLOW_B_FORWARD_METER_ID))
                .srcSwitch(switchD)
                .destSwitch(switchD)
                .bandwidth(FLOW_B_BANDWIDTH)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setForwardPath(forwardFlowPath);

        FlowPath reverseFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_B + "_reverse_path"))
                .cookie(new FlowSegmentCookie(FLOW_B_REVERSE_COOKIE))
                .meterId(new MeterId(FLOW_B_REVERSE_METER_ID))
                .srcSwitch(switchD)
                .destSwitch(switchD)
                .bandwidth(FLOW_B_BANDWIDTH)
                .status(FlowPathStatus.ACTIVE)
                .build();
        flow.setReversePath(reverseFlowPath);

        flowRepository.add(flow);
    }

    protected List<SwitchFlowEntries> getSwitchFlowEntriesWithTransitVlan() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_A,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SRC_PORT, FLOW_A_SRC_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_A_SRC_PORT), 0, getFlowSetFieldAction(FLOW_A_ENCAP_ID),
                        (long) FLOW_A_FORWARD_METER_ID, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_SEGMENT_A_SRC_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_SRC_PORT), 0, getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, FLOW_A_SEGMENT_A_SRC_PORT_PROTECTED,
                        FLOW_A_ENCAP_ID_PROTECTED, String.valueOf(FLOW_A_SRC_PORT), 0,
                        getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_B,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_A_DST_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT), 0, null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_SEGMENT_B_SRC_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_SEGMENT_A_DST_PORT), 0, null, null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_C,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_B_DST_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_DST_PORT), 0, getFlowSetFieldAction(FLOW_A_DST_VLAN), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_DST_PORT, FLOW_A_DST_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_B_DST_PORT), 0, getFlowSetFieldAction(FLOW_A_ENCAP_ID),
                        (long) FLOW_A_REVERSE_METER_ID, false),
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_DST_PORT_PROTECTED,
                        FLOW_A_ENCAP_ID_PROTECTED, String.valueOf(FLOW_A_DST_PORT), 0,
                        getFlowSetFieldAction(FLOW_A_DST_VLAN), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_E,
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_A_DST_PORT_PROTECTED,
                        FLOW_A_ENCAP_ID_PROTECTED, String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED), 0,
                        null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED,
                        FLOW_A_ENCAP_ID_PROTECTED, String.valueOf(FLOW_A_SEGMENT_A_DST_PORT_PROTECTED), 0,
                        null, null, false)));

        return switchEntries;
    }

    protected List<SwitchFlowEntries> getWrongSwitchFlowEntriesWithTransitVlan() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_A,
                getFlowEntry(123, FLOW_A_SRC_PORT, FLOW_A_SRC_VLAN, String.valueOf(FLOW_A_SEGMENT_A_SRC_PORT), 0,
                        getFlowSetFieldAction(FLOW_A_ENCAP_ID), (long) FLOW_A_FORWARD_METER_ID, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, 123, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_SRC_PORT), 0, getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, 123, FLOW_A_ENCAP_ID_PROTECTED,
                        String.valueOf(FLOW_A_SRC_PORT), 0, getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_B,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_A_DST_PORT, 123,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT), 0, null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_SEGMENT_B_SRC_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(123), 0, null, null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_C,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_B_DST_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_DST_PORT), 0, getFlowSetFieldAction(123), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_DST_PORT, FLOW_A_DST_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_B_DST_PORT), 0, getFlowSetFieldAction(FLOW_A_ENCAP_ID),
                        123L, false),
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_DST_PORT_PROTECTED,
                        FLOW_A_ENCAP_ID_PROTECTED, String.valueOf(FLOW_A_DST_PORT), 0,
                        getFlowSetFieldAction(123), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_E,
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_A_DST_PORT_PROTECTED, 123,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED), 0, null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED,
                        FLOW_A_ENCAP_ID_PROTECTED, String.valueOf(123), 0, null, null, false)));

        return switchEntries;
    }

    protected List<SwitchFlowEntries> getSwitchFlowEntriesWithVxlan() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_A,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SRC_PORT, FLOW_A_SRC_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_A_SRC_PORT), FLOW_A_ENCAP_ID, null,
                        (long) FLOW_A_FORWARD_METER_ID, true),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_SEGMENT_A_SRC_PORT, 0, String.valueOf(FLOW_A_SRC_PORT),
                        FLOW_A_ENCAP_ID, getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, FLOW_A_SEGMENT_A_SRC_PORT_PROTECTED,
                        0, String.valueOf(FLOW_A_SRC_PORT), FLOW_A_ENCAP_ID_PROTECTED,
                        getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_B,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_A_DST_PORT, 0,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT), FLOW_A_ENCAP_ID, null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_SEGMENT_B_SRC_PORT, 0,
                        String.valueOf(FLOW_A_SEGMENT_A_DST_PORT), FLOW_A_ENCAP_ID, null, null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_C,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_B_DST_PORT, 0, String.valueOf(FLOW_A_DST_PORT),
                        FLOW_A_ENCAP_ID, getFlowSetFieldAction(FLOW_A_DST_VLAN), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_DST_PORT, FLOW_A_DST_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_B_DST_PORT), FLOW_A_ENCAP_ID, null,
                        (long) FLOW_A_REVERSE_METER_ID, true),
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_DST_PORT_PROTECTED,
                        0, String.valueOf(FLOW_A_DST_PORT), FLOW_A_ENCAP_ID_PROTECTED,
                        getFlowSetFieldAction(FLOW_A_DST_VLAN), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_E,
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_A_DST_PORT_PROTECTED,
                        0, String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED), FLOW_A_ENCAP_ID_PROTECTED,
                        null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED,
                        0, String.valueOf(FLOW_A_SEGMENT_A_DST_PORT_PROTECTED), FLOW_A_ENCAP_ID_PROTECTED,
                        null, null, false)));

        return switchEntries;
    }

    protected List<SwitchFlowEntries> getWrongSwitchFlowEntriesWithVxlan() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_A,
                getFlowEntry(123, FLOW_A_SRC_PORT, FLOW_A_SRC_VLAN, String.valueOf(FLOW_A_SEGMENT_A_SRC_PORT),
                        FLOW_A_ENCAP_ID, null, (long) FLOW_A_FORWARD_METER_ID, true),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, 123, 0, String.valueOf(FLOW_A_SRC_PORT), FLOW_A_ENCAP_ID,
                        getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, 123, 0, String.valueOf(FLOW_A_SRC_PORT),
                        FLOW_A_ENCAP_ID_PROTECTED, getFlowSetFieldAction(FLOW_A_SRC_VLAN), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_B,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_A_DST_PORT, 0,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT), 123, null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_SEGMENT_B_SRC_PORT, 0,
                        String.valueOf(123), FLOW_A_ENCAP_ID, null, null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_C,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_B_DST_PORT, 0,
                        String.valueOf(FLOW_A_DST_PORT), FLOW_A_ENCAP_ID, getFlowSetFieldAction(123), null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE, FLOW_A_DST_PORT, FLOW_A_DST_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_B_DST_PORT), FLOW_A_ENCAP_ID, null,
                        123L, true),
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_DST_PORT_PROTECTED,
                        0, String.valueOf(FLOW_A_DST_PORT), FLOW_A_ENCAP_ID_PROTECTED,
                        getFlowSetFieldAction(123), null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_E,
                getFlowEntry(FLOW_A_FORWARD_COOKIE_PROTECTED, FLOW_A_SEGMENT_A_DST_PORT_PROTECTED, 0,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED), 123, null, null, false),
                getFlowEntry(FLOW_A_REVERSE_COOKIE_PROTECTED, FLOW_A_SEGMENT_B_SRC_PORT_PROTECTED,
                        0, String.valueOf(123), FLOW_A_ENCAP_ID_PROTECTED, null, null, false)));

        return switchEntries;
    }

    protected List<SwitchMeterEntries> getSwitchMeterEntries() {
        List<SwitchMeterEntries> switchMeterEntries = new ArrayList<>();
        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_A)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_FORWARD_METER_ID)
                        .rate(FLOW_A_BANDWIDTH)
                        .burstSize(Meter
                                .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                        .flags(Meter.getMeterKbpsFlags())
                        .build()))
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_B)
                .meterEntries(Collections.emptyList())
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_C)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_REVERSE_METER_ID)
                        .rate(FLOW_A_BANDWIDTH)
                        .burstSize(Meter
                                .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                        .flags(Meter.getMeterKbpsFlags())
                        .build()))
                .build());

        return switchMeterEntries;
    }

    protected List<SwitchMeterEntries> getWrongSwitchMeterEntries() {
        List<SwitchMeterEntries> switchMeterEntries = new ArrayList<>();
        int delta = 5000;
        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_A)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_FORWARD_METER_ID)
                        .rate(FLOW_A_BANDWIDTH + delta)
                        .burstSize(Meter.calculateBurstSize(FLOW_A_BANDWIDTH + delta, MIN_BURST_SIZE_IN_KBITS,
                                BURST_COEFFICIENT, ""))
                        .flags(new String[]{"PKTPS", "BURST", "STATS"})
                        .build()))
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_B)
                .meterEntries(Collections.emptyList())
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_C)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_REVERSE_METER_ID)
                        .rate(FLOW_A_BANDWIDTH)
                        .burstSize(Meter
                                .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                        .flags(Meter.getMeterKbpsFlags())
                        .build()))
                .build());

        return switchMeterEntries;
    }

    protected List<SwitchMeterEntries> getSwitchMeterEntriesWithESwitch() {
        long rateESwitch = FLOW_A_BANDWIDTH + (long) (FLOW_A_BANDWIDTH * 0.01) - 1;
        long burstSize = (long) (FLOW_A_BANDWIDTH * 1.05);
        long burstSizeESwitch = burstSize + (long) (burstSize * 0.01) - 1;

        List<SwitchMeterEntries> switchMeterEntries = new ArrayList<>();
        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_A)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_FORWARD_METER_ID)
                        .rate(rateESwitch)
                        .burstSize(burstSizeESwitch)
                        .flags(Meter.getMeterKbpsFlags())
                        .build()))
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_B)
                .meterEntries(Collections.emptyList())
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_C)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_REVERSE_METER_ID)
                        .rate(FLOW_A_BANDWIDTH)
                        .burstSize(Meter
                                .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                        .flags(Meter.getMeterKbpsFlags())
                        .build()))
                .build());

        return switchMeterEntries;
    }

    protected List<SwitchFlowEntries> getSwitchFlowEntriesOneSwitchFlow() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_D,
                getFlowEntry(FLOW_B_FORWARD_COOKIE, FLOW_B_SRC_PORT, FLOW_B_SRC_VLAN, "in_port", 0,
                        getFlowSetFieldAction(FLOW_B_DST_VLAN), (long) FLOW_B_FORWARD_METER_ID, false),
                getFlowEntry(FLOW_B_REVERSE_COOKIE, FLOW_B_SRC_PORT, FLOW_B_DST_VLAN, "in_port", 0,
                        getFlowSetFieldAction(FLOW_B_SRC_VLAN), (long) FLOW_B_REVERSE_METER_ID, false)));

        return switchEntries;
    }

    protected List<SwitchMeterEntries> getSwitchMeterEntriesOneSwitchFlow() {
        List<MeterEntry> meterEntries = new ArrayList<>();
        meterEntries.add(MeterEntry.builder()
                .meterId(FLOW_B_FORWARD_METER_ID)
                .rate(FLOW_B_BANDWIDTH)
                .burstSize(Meter
                        .calculateBurstSize(FLOW_B_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                .flags(Meter.getMeterKbpsFlags())
                .build());
        meterEntries.add(MeterEntry.builder()
                .meterId(FLOW_B_REVERSE_METER_ID)
                .rate(FLOW_B_BANDWIDTH)
                .burstSize(Meter
                        .calculateBurstSize(FLOW_B_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                .flags(Meter.getMeterKbpsFlags())
                .build());

        return Collections.singletonList(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_D)
                .meterEntries(meterEntries)
                .build());
    }

    private SwitchFlowEntries getSwitchFlowEntries(SwitchId switchId, FlowEntry... flowEntries) {
        return SwitchFlowEntries.builder()
                .switchId(switchId)
                .flowEntries(Lists.newArrayList(flowEntries))
                .build();
    }

    private FlowEntry getFlowEntry(long cookie, int srcPort, int srcVlan, String dstPort, int tunnelId,
                                   FlowSetFieldAction flowSetFieldAction, Long meterId, boolean tunnelIdIngressRule) {
        return FlowEntry.builder()
                .cookie(cookie)
                .packetCount(7)
                .byteCount(480)
                .version("OF_13")
                .match(FlowMatchField.builder()
                        .inPort(String.valueOf(srcPort))
                        .vlanVid(String.valueOf(srcVlan))
                        .tunnelId(!tunnelIdIngressRule ? String.valueOf(tunnelId) : null)
                        .build())
                .instructions(FlowInstructions.builder()
                        .applyActions(FlowApplyActions.builder()
                                .flowOutput(dstPort)
                                .setFieldActions(flowSetFieldAction == null
                                        ? Lists.newArrayList() : Lists.newArrayList(flowSetFieldAction))
                                .pushVxlan(tunnelIdIngressRule ? String.valueOf(tunnelId) : null)
                                .build())
                        .goToMeter(meterId)
                        .build())
                .build();
    }

    private FlowSetFieldAction getFlowSetFieldAction(int dstVlan) {
        return FlowSetFieldAction.builder()
                .fieldName("vlan_vid")
                .fieldValue(String.valueOf(dstVlan))
                .build();
    }
}
