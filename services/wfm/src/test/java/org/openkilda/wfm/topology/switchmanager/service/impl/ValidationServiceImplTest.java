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

package org.openkilda.wfm.topology.switchmanager.service.impl;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.messaging.info.meter.MeterEntry;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.switches.MeterInfoEntry;
import org.openkilda.model.ApplicationRule;
import org.openkilda.model.Cookie;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowApplication;
import org.openkilda.model.FlowPath;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.ApplicationRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.topology.switchmanager.SwitchManagerTopologyConfig;
import org.openkilda.wfm.topology.switchmanager.model.ValidateMetersResult;
import org.openkilda.wfm.topology.switchmanager.model.ValidateRulesResult;
import org.openkilda.wfm.topology.switchmanager.service.ValidationService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

public class ValidationServiceImplTest {

    private static final SwitchId SWITCH_ID_A = new SwitchId("00:10");
    private static final SwitchId SWITCH_ID_B = new SwitchId("00:20");
    private static final SwitchId SWITCH_ID_E = new SwitchId("00:30");
    private static final SwitchId SWITCH_ID_C = new SwitchId("00:40");
    private static final long FLOW_E_BANDWIDTH = 10000L;
    private static final Switch switchA = Switch.builder()
            .switchId(SWITCH_ID_A)
            .description("Nicira, Inc. OF_13 2.5.5")
            .build();
    private static final Switch switchB = Switch.builder()
            .switchId(SWITCH_ID_B)
            .description("Nicira, Inc. OF_13 2.5.5")
            .build();
    private static DetectConnectedDevices detectConnectedDevices = new DetectConnectedDevices(
            true, false, true, false, false, false);
    private static final Switch switchC = Switch.builder()
            .switchId(SWITCH_ID_C)
            .description("Nicira, Inc. OF_13 2.5.5")
            .build();
    private static final long UNMASKED_FLOW_COOKIE_WITH_TELESCOPE = 1L;

    private static SwitchManagerTopologyConfig topologyConfig;

    @BeforeClass
    public static void setupOnce() {
        PropertiesBasedConfigurationProvider configurationProvider =
                new PropertiesBasedConfigurationProvider(new Properties());
        topologyConfig = configurationProvider.getConfiguration(SwitchManagerTopologyConfig.class);
    }

    @Test
    public void validateRulesEmpty() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        ValidateRulesResult response = validationService.validateRules(SWITCH_ID_A, emptyList(), emptyList());
        assertTrue(response.getMissingRules().isEmpty());
        assertTrue(response.getProperRules().isEmpty());
        assertTrue(response.getExcessRules().isEmpty());
    }

    @Test
    public void mustHaveLldpRuleWithConnectedDevices() {
        Flow flow = createFlowForConnectedDevices(true, true);

        ValidationServiceImpl validationService =
                new ValidationServiceImpl(persistenceManager().withSegmentsCookies(1).build(), topologyConfig);

        assertTrue(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getForwardPath()));
        assertTrue(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getReversePath()));
        assertTrue(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getProtectedForwardPath()));
        assertTrue(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getProtectedReversePath()));

        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getForwardPath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getReversePath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getProtectedForwardPath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getProtectedReversePath()));
    }

    @Test
    public void mustHaveLldpRuleWithOutConnectedDevices() {
        Flow flow = createFlowForConnectedDevices(false, false);

        ValidationServiceImpl validationService =
                new ValidationServiceImpl(persistenceManager().withSegmentsCookies(1).build(), topologyConfig);

        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getForwardPath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getReversePath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getProtectedForwardPath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_A, flow.getProtectedReversePath()));

        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getForwardPath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getReversePath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getProtectedForwardPath()));
        assertFalse(validationService.mustHaveLldpRule(SWITCH_ID_B, flow.getProtectedReversePath()));
    }

    @Test
    public void validateRulesSimpleSegmentCookies() {
        ValidationService validationService =
                new ValidationServiceImpl(persistenceManager().withSegmentsCookies(2L, 3L).build(), topologyConfig);
        List<FlowEntry> flowEntries =
                Lists.newArrayList(FlowEntry.builder().cookie(1L).build(), FlowEntry.builder().cookie(2L).build());
        ValidateRulesResult response = validationService.validateRules(SWITCH_ID_A, flowEntries, emptyList());
        assertEquals(ImmutableList.of(3L), response.getMissingRules());
        assertEquals(ImmutableList.of(2L), response.getProperRules());
        assertEquals(ImmutableList.of(1L), response.getExcessRules());
    }

    @Test
    public void validateRulesSegmentAndIngressCookies() {
        ValidationService validationService =
                new ValidationServiceImpl(persistenceManager().withSegmentsCookies(2L).withIngressCookies(1L).build(),
                        topologyConfig);
        List<FlowEntry> flowEntries =
                Lists.newArrayList(FlowEntry.builder().cookie(1L).build(), FlowEntry.builder().cookie(2L).build());
        ValidateRulesResult response = validationService.validateRules(SWITCH_ID_A, flowEntries, emptyList());
        assertTrue(response.getMissingRules().isEmpty());
        assertEquals(ImmutableSet.of(1L, 2L), new HashSet<>(response.getProperRules()));
        assertTrue(response.getExcessRules().isEmpty());
    }

    @Test
    public void validateRulesSegmentAndIngressLldpCookiesProper() {
        PersistenceManager persistenceManager = persistenceManager()
                .withIngressCookies(1L)
                .withDetectConnectedDevices(detectConnectedDevices)
                .build();
        ValidationService validationService = new ValidationServiceImpl(persistenceManager, topologyConfig);
        List<FlowEntry> flowEntries =
                Lists.newArrayList(FlowEntry.builder().cookie(1L).build());
        ValidateRulesResult response = validationService.validateRules(SWITCH_ID_A, flowEntries, emptyList());
        assertTrue(response.getMissingRules().isEmpty());
        assertEquals(ImmutableSet.of(1L), new HashSet<>(response.getProperRules()));
        assertTrue(response.getExcessRules().isEmpty());
    }

    @Test
    public void validateRulesSegmentAndIngressLldpCookiesMissing() {
        PersistenceManager persistenceManager = persistenceManager()
                .withIngressCookies(1L)
                .withDetectConnectedDevices(new DetectConnectedDevices(true, false, true, false, false, false))
                .build();
        ValidationService validationService = new ValidationServiceImpl(persistenceManager, topologyConfig);
        ValidateRulesResult response = validationService.validateRules(SWITCH_ID_A, emptyList(), emptyList());
        assertEquals(ImmutableSet.of(1L), new HashSet<>(response.getMissingRules()));
        assertTrue(response.getProperRules().isEmpty());
        assertTrue(response.getExcessRules().isEmpty());
    }

    @Test
    public void validateDefaultRules() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        List<FlowEntry> flowEntries =
                Lists.newArrayList(FlowEntry.builder().cookie(0x8000000000000001L).priority(1).byteCount(123).build(),
                        FlowEntry.builder().cookie(0x8000000000000001L).priority(2).build(),
                        FlowEntry.builder().cookie(0x8000000000000002L).priority(1).build(),
                        FlowEntry.builder().cookie(0x8000000000000002L).priority(2).build(),
                        FlowEntry.builder().cookie(0x8000000000000004L).priority(1).build());
        List<FlowEntry> expectedDefaultFlowEntries =
                Lists.newArrayList(FlowEntry.builder().cookie(0x8000000000000001L).priority(1).byteCount(321).build(),
                        FlowEntry.builder().cookie(0x8000000000000002L).priority(3).build(),
                        FlowEntry.builder().cookie(0x8000000000000003L).priority(1).build());
        ValidateRulesResult response =
                validationService.validateRules(SWITCH_ID_A, flowEntries, expectedDefaultFlowEntries);
        assertEquals(ImmutableSet.of(0x8000000000000001L), new HashSet<>(response.getProperRules()));
        assertEquals(ImmutableSet.of(0x8000000000000001L, 0x8000000000000002L),
                new HashSet<>(response.getMisconfiguredRules()));
        assertEquals(ImmutableSet.of(0x8000000000000003L), new HashSet<>(response.getMissingRules()));
        assertEquals(ImmutableSet.of(0x8000000000000004L), new HashSet<>(response.getExcessRules()));
    }

    @Test
    public void validateMetersEmpty() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_A, new ArrayList<>());
        assertTrue(response.getMissingMeters().isEmpty());
        assertTrue(response.getMisconfiguredMeters().isEmpty());
        assertTrue(response.getProperMeters().isEmpty());
        assertTrue(response.getExcessMeters().isEmpty());
    }

    @Test
    public void validateMetersProperMeters() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_B,
                Lists.newArrayList(new MeterEntry(32, 10000, 10500, "OF_13", new String[]{"KBPS", "BURST", "STATS"})));
        assertTrue(response.getMissingMeters().isEmpty());
        assertTrue(response.getMisconfiguredMeters().isEmpty());
        assertFalse(response.getProperMeters().isEmpty());
        assertEquals(32L, (long) response.getProperMeters().get(0).getMeterId());
        assertMeter(response.getProperMeters().get(0), 32, 10000, 10500, new String[]{"KBPS", "BURST", "STATS"});
        assertTrue(response.getExcessMeters().isEmpty());
    }

    @Test
    public void validateMetersMisconfiguredMeters() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        String[] actualFlags = new String[]{"PKTPS", "BURST", "STATS"};
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_B,
                Lists.newArrayList(new MeterEntry(32, 10002, 10498, "OF_13", actualFlags)));
        assertTrue(response.getMissingMeters().isEmpty());
        assertFalse(response.getMisconfiguredMeters().isEmpty());
        assertEquals(10002, (long) response.getMisconfiguredMeters().get(0).getActual().getRate());
        assertEquals(10000, (long) response.getMisconfiguredMeters().get(0).getExpected().getRate());
        assertEquals(10498L, (long) response.getMisconfiguredMeters().get(0).getActual().getBurstSize());
        assertEquals(10500L, (long) response.getMisconfiguredMeters().get(0).getExpected().getBurstSize());
        assertArrayEquals(actualFlags, response.getMisconfiguredMeters().get(0).getActual().getFlags());
        assertArrayEquals(new String[]{"KBPS", "BURST", "STATS"},
                response.getMisconfiguredMeters().get(0).getExpected().getFlags());
        assertTrue(response.getProperMeters().isEmpty());
        assertTrue(response.getExcessMeters().isEmpty());
    }

    @Test
    public void validateMetersMissingAndExcessMeters() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_B,
                Lists.newArrayList(new MeterEntry(33, 10000, 10500, "OF_13", new String[]{"KBPS", "BURST", "STATS"})));
        assertFalse(response.getMissingMeters().isEmpty());
        assertMeter(response.getMissingMeters().get(0), 32, 10000, 10500, new String[]{"KBPS", "BURST", "STATS"});
        assertTrue(response.getMisconfiguredMeters().isEmpty());
        assertTrue(response.getProperMeters().isEmpty());
        assertFalse(response.getExcessMeters().isEmpty());
        assertMeter(response.getExcessMeters().get(0), 33, 10000, 10500, new String[]{"KBPS", "BURST", "STATS"});
    }

    @Test
    public void validateExcessMeters() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_A,
                Lists.newArrayList(new MeterEntry(100, 10000, 10500, "OF_13", new String[]{"KBPS", "BURST", "STATS"})));
        assertTrue(response.getMissingMeters().isEmpty());
        assertTrue(response.getMisconfiguredMeters().isEmpty());
        assertTrue(response.getProperMeters().isEmpty());
        assertEquals(1, response.getExcessMeters().size());
        assertMeter(response.getExcessMeters().get(0), 100, 10000, 10500, new String[]{"KBPS", "BURST", "STATS"});
    }

    @Test
    public void validateMetersIgnoreDefaultMeters() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_B,
                Lists.newArrayList(new MeterEntry(2, 0, 0, null, null), new MeterEntry(3, 0, 0, null, null)));
        assertFalse(response.getMissingMeters().isEmpty());
        assertEquals(1, response.getMissingMeters().size());
        assertTrue(response.getMisconfiguredMeters().isEmpty());
        assertTrue(response.getProperMeters().isEmpty());
        assertTrue(response.getExcessMeters().isEmpty());
    }

    @Test
    public void validateMetersProperMetersESwitch() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        long rateESwitch = FLOW_E_BANDWIDTH + (long) (FLOW_E_BANDWIDTH * 0.01) - 1;
        long burstSize = (long) (FLOW_E_BANDWIDTH * 1.05);
        long burstSizeESwitch = burstSize + (long) (burstSize * 0.01) - 1;
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_E,
                Lists.newArrayList(new MeterEntry(32, rateESwitch, burstSizeESwitch, "OF_13",
                        new String[]{"KBPS", "BURST", "STATS"})));
        assertTrue(response.getMissingMeters().isEmpty());
        assertTrue(response.getMisconfiguredMeters().isEmpty());
        assertFalse(response.getProperMeters().isEmpty());
        assertEquals(32L, (long) response.getProperMeters().get(0).getMeterId());
        assertMeter(response.getProperMeters().get(0), 32, rateESwitch, burstSizeESwitch,
                new String[]{"KBPS", "BURST", "STATS"});
        assertTrue(response.getExcessMeters().isEmpty());
    }

    @Test
    public void validateMetersMisconfiguredMetersESwitch() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);
        long rateESwitch = FLOW_E_BANDWIDTH + (long) (FLOW_E_BANDWIDTH * 0.01) + 1;
        long burstSize = (long) (FLOW_E_BANDWIDTH * 1.05);
        long burstSizeESwitch = burstSize + (long) (burstSize * 0.01) + 1;
        ValidateMetersResult response = validationService.validateMeters(SWITCH_ID_E,
                Lists.newArrayList(new MeterEntry(32, rateESwitch, burstSizeESwitch, "OF_13",
                        new String[]{"KBPS", "BURST", "STATS"})));
        assertTrue(response.getMissingMeters().isEmpty());
        assertFalse(response.getMisconfiguredMeters().isEmpty());
        assertEquals(10606L, (long) response.getMisconfiguredMeters().get(0).getActual().getBurstSize());
        assertEquals(10500L, (long) response.getMisconfiguredMeters().get(0).getExpected().getBurstSize());
        assertTrue(response.getProperMeters().isEmpty());
        assertTrue(response.getExcessMeters().isEmpty());
    }

    @Test
    public void validateSwitchWhenTelescopeEnabled() {
        ValidationService validationService = new ValidationServiceImpl(persistenceManager().build(), topologyConfig);

        long forwardTelescopeCookie = Cookie.buildTelescopeCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE, true).getValue();
        long reverseTelescopeCookie =
                Cookie.buildTelescopeCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE, false).getValue();
        long forwardExclusionCookie =
                Cookie.buildExclusionCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE, 1, true).getValue();
        long reversedExclusionCookie =
                Cookie.buildExclusionCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE, 1, false).getValue();
        long forwardFlowCookie = Cookie.buildForwardCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE).getValue();
        long reverseFlowCookie = Cookie.buildReverseCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE).getValue();

        List<FlowEntry> flowEntries =
                Lists.newArrayList(
                        FlowEntry.builder().cookie(reverseTelescopeCookie).build(),
                        FlowEntry.builder().cookie(forwardExclusionCookie).build(),
                        FlowEntry.builder().cookie(reversedExclusionCookie).build(),
                        FlowEntry.builder().cookie(forwardFlowCookie)
                                .instructions(FlowInstructions.builder().writeMetadata(2L).build()).build(),
                        FlowEntry.builder().cookie(reverseFlowCookie).build());

        ValidateRulesResult response = validationService.validateRules(SWITCH_ID_C, flowEntries, emptyList());
        assertEquals(ImmutableSet.of(forwardTelescopeCookie), new HashSet<>(response.getMissingRules()));
        assertEquals(ImmutableSet.of(reverseTelescopeCookie, reverseFlowCookie,
                forwardExclusionCookie, forwardFlowCookie), new HashSet<>(response.getProperRules()));
        assertEquals(ImmutableSet.of(), new HashSet<>(response.getMisconfiguredRules()));
        assertEquals(ImmutableSet.of(reversedExclusionCookie), new HashSet<>(response.getExcessRules()));
    }

    private void assertMeter(MeterInfoEntry meterInfoEntry, long expectedId, long expectedRate, long expectedBurstSize,
                             String[] expectedFlags) {
        assertEquals(expectedId, (long) meterInfoEntry.getMeterId());
        assertEquals(expectedRate, (long) meterInfoEntry.getRate());
        assertEquals(expectedBurstSize, (long) meterInfoEntry.getBurstSize());
        assertEquals(expectedFlags, meterInfoEntry.getFlags());
    }

    private Flow createFlowForConnectedDevices(boolean detectSrcLldp,
                                               boolean detectDstLldp) {
        Flow flow = Flow.builder()
                .flowId("flow")
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .detectConnectedDevices(new DetectConnectedDevices(
                        detectSrcLldp, false, detectDstLldp, false, false, false))
                .build();

        FlowPath forwardPath = buildFlowPath(flow, switchA, switchB, "1", 1);
        FlowPath reversePath = buildFlowPath(flow, switchB, switchA, "2", 2);
        FlowPath protectedForwardPath = buildFlowPath(flow, switchA, switchB, "3", 3);
        FlowPath protectedReversePath = buildFlowPath(flow, switchB, switchA, "4", 4);


        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);
        flow.setProtectedForwardPath(protectedForwardPath);
        flow.setProtectedReversePath(protectedReversePath);

        return flow;
    }

    private static FlowPath buildFlowPath(Flow flow, Switch srcSwitch, Switch dstSwitch, String pathId, long cookie) {
        return FlowPath.builder()
                .flow(flow)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .pathId(new PathId(pathId))
                .cookie(new Cookie(cookie))
                .build();
    }

    private PersistenceManagerBuilder persistenceManager() {
        return new PersistenceManagerBuilder();
    }

    private static class PersistenceManagerBuilder {
        private FlowPathRepository flowPathRepository = mock(FlowPathRepository.class);
        private ApplicationRepository applicationRepository = mock(ApplicationRepository.class);

        private long[] segmentsCookies = new long[0];
        private long[] ingressCookies = new long[0];
        private DetectConnectedDevices detectConnectedDevices = new DetectConnectedDevices(false, false, false, false,
                false, false);

        private PersistenceManagerBuilder withSegmentsCookies(long... cookies) {
            segmentsCookies = cookies;
            return this;
        }

        private PersistenceManagerBuilder withIngressCookies(long... cookies) {
            ingressCookies = cookies;
            return this;
        }

        private PersistenceManagerBuilder withDetectConnectedDevices(DetectConnectedDevices detectConnectedDevices) {
            this.detectConnectedDevices = detectConnectedDevices;
            return this;
        }

        private PersistenceManager build() {
            List<FlowPath> pathsBySegment = new ArrayList<>(segmentsCookies.length);
            for (long cookie : segmentsCookies) {
                FlowPath flowPath = mock(FlowPath.class);
                when(flowPath.getCookie()).thenReturn(new Cookie(cookie));
                pathsBySegment.add(flowPath);
            }
            List<FlowPath> flowPaths = new ArrayList<>(ingressCookies.length);
            for (int i = 0; i < ingressCookies.length; i++) {
                long cookie = ingressCookies[i];
                Flow flow = buildFlow(cookie, "flow_");
                FlowPath flowPath = buildFlowPath(flow, switchA, switchB, "path_" + cookie, cookie);
                flowPaths.add(flowPath);
            }
            when(flowPathRepository.findBySegmentDestSwitch(any())).thenReturn(pathsBySegment);
            when(flowPathRepository.findByEndpointSwitch(any())).thenReturn(flowPaths);

            FlowPath flowPathA = mock(FlowPath.class);
            when(flowPathA.getSrcSwitch()).thenReturn(switchB);
            when(flowPathA.getDestSwitch()).thenReturn(switchA);
            when(flowPathA.getBandwidth()).thenReturn(10000L);
            when(flowPathA.getCookie()).thenReturn(Cookie.buildForwardCookie(1));
            when(flowPathA.getMeterId()).thenReturn(new MeterId(32L));

            Flow flowA = mock(Flow.class);
            when(flowA.getFlowId()).thenReturn("test_flow");
            when(flowA.getSrcSwitch()).thenReturn(switchB);
            when(flowA.getDestSwitch()).thenReturn(switchA);
            when(flowA.getDetectConnectedDevices()).thenReturn(detectConnectedDevices);
            when(flowPathA.getFlow()).thenReturn(flowA);

            Switch switchE = Switch.builder()
                    .switchId(SWITCH_ID_E)
                    .description("Nicira, Inc. OF_13 2.5.5")
                    .build();
            switchE.setOfDescriptionManufacturer("E");
            FlowPath flowPathB = mock(FlowPath.class);
            when(flowPathB.getSrcSwitch()).thenReturn(switchE);
            when(flowPathB.getDestSwitch()).thenReturn(switchA);
            when(flowPathB.getBandwidth()).thenReturn(FLOW_E_BANDWIDTH);
            when(flowPathB.getCookie()).thenReturn(Cookie.buildForwardCookie(1));
            when(flowPathB.getMeterId()).thenReturn(new MeterId(32L));

            Flow flowB = mock(Flow.class);
            when(flowB.getFlowId()).thenReturn("test_flow_b");
            when(flowB.getSrcSwitch()).thenReturn(switchE);
            when(flowB.getDestSwitch()).thenReturn(switchA);
            when(flowB.getDetectConnectedDevices()).thenReturn(detectConnectedDevices);
            when(flowPathB.getFlow()).thenReturn(flowB);

            when(flowPathRepository.findBySrcSwitch(eq(SWITCH_ID_B)))
                    .thenReturn(singletonList(flowPathA));
            when(flowPathRepository.findBySrcSwitch(eq(SWITCH_ID_E)))
                    .thenReturn(singletonList(flowPathB));

            FlowPath forwardFlowPathC = mock(FlowPath.class);
            when(forwardFlowPathC.getSrcSwitch()).thenReturn(switchC);
            when(forwardFlowPathC.getDestSwitch()).thenReturn(switchA);
            when(forwardFlowPathC.getCookie())
                    .thenReturn(Cookie.buildForwardCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE));
            when(forwardFlowPathC.getApplications()).thenReturn(EnumSet.of(FlowApplication.TELESCOPE));

            FlowPath reverseFlowPathC = mock(FlowPath.class);
            when(reverseFlowPathC.getSrcSwitch()).thenReturn(switchA);
            when(reverseFlowPathC.getDestSwitch()).thenReturn(switchC);
            when(reverseFlowPathC.getCookie())
                    .thenReturn(Cookie.buildReverseCookie(UNMASKED_FLOW_COOKIE_WITH_TELESCOPE));
            when(reverseFlowPathC.getApplications()).thenReturn(EnumSet.of(FlowApplication.TELESCOPE));

            Flow flowC = mock(Flow.class);
            when(flowC.getFlowId()).thenReturn("test_flow_c");
            when(flowC.getSrcSwitch()).thenReturn(switchC);
            when(flowC.getDestSwitch()).thenReturn(switchA);
            when(flowC.getDetectConnectedDevices()).thenReturn(detectConnectedDevices);
            when(forwardFlowPathC.getFlow()).thenReturn(flowC);
            when(reverseFlowPathC.getFlow()).thenReturn(flowC);

            when(flowPathRepository.findByEndpointSwitch(eq(SWITCH_ID_C)))
                    .thenReturn(Lists.newArrayList(forwardFlowPathC, reverseFlowPathC));

            when(applicationRepository.findBySwitchId(SWITCH_ID_A)).thenReturn(emptyList());
            when(applicationRepository.findBySwitchId(SWITCH_ID_C))
                    .thenReturn(Lists.newArrayList(ApplicationRule.builder()
                            .cookie(Cookie.buildExclusionCookie(1L, 1, true)).build()));

            RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
            when(repositoryFactory.createFlowPathRepository()).thenReturn(flowPathRepository);

            when(repositoryFactory.createApplicationRepository()).thenReturn(applicationRepository);

            PersistenceManager persistenceManager = mock(PersistenceManager.class);
            when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
            return persistenceManager;
        }

        private Flow buildFlow(long cookie, String flowIdPrefix) {
            return Flow.builder()
                    .srcSwitch(switchA)
                    .destSwitch(switchB)
                    .detectConnectedDevices(detectConnectedDevices)
                    .flowId(flowIdPrefix + cookie)
                    .build();
        }
    }
}
