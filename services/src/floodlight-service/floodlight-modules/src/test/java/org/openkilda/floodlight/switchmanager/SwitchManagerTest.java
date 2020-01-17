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

package org.openkilda.floodlight.switchmanager;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.mock;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.beans.HasPropertyWithValue.hasProperty;
import static org.hamcrest.core.Every.everyItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.openkilda.floodlight.Constants.inputPort;
import static org.openkilda.floodlight.Constants.inputVlanId;
import static org.openkilda.floodlight.Constants.meterId;
import static org.openkilda.floodlight.Constants.outputPort;
import static org.openkilda.floodlight.Constants.outputVlanId;
import static org.openkilda.floodlight.Constants.transitVlanId;
import static org.openkilda.floodlight.pathverification.PathVerificationService.LATENCY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.switchmanager.ISwitchManager.OVS_MANUFACTURER;
import static org.openkilda.floodlight.switchmanager.SwitchManager.ROUND_TRIP_LATENCY_GROUP_ID;
import static org.openkilda.floodlight.test.standard.PushSchemeOutputCommands.ofFactory;
import static org.openkilda.model.Cookie.CATCH_BFD_RULE_COOKIE;
import static org.openkilda.model.Cookie.DROP_RULE_COOKIE;
import static org.openkilda.model.Cookie.DROP_VERIFICATION_LOOP_RULE_COOKIE;
import static org.openkilda.model.Cookie.LLDP_INGRESS_COOKIE;
import static org.openkilda.model.Cookie.LLDP_INPUT_PRE_DROP_COOKIE;
import static org.openkilda.model.Cookie.LLDP_POST_INGRESS_COOKIE;
import static org.openkilda.model.Cookie.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE;
import static org.openkilda.model.Cookie.LLDP_POST_INGRESS_VXLAN_COOKIE;
import static org.openkilda.model.Cookie.LLDP_TRANSIT_COOKIE;
import static org.openkilda.model.Cookie.MULTITABLE_EGRESS_PASS_THROUGH_COOKIE;
import static org.openkilda.model.Cookie.MULTITABLE_INGRESS_DROP_COOKIE;
import static org.openkilda.model.Cookie.MULTITABLE_POST_INGRESS_DROP_COOKIE;
import static org.openkilda.model.Cookie.MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE;
import static org.openkilda.model.Cookie.MULTITABLE_TRANSIT_DROP_COOKIE;
import static org.openkilda.model.Cookie.ROUND_TRIP_LATENCY_RULE_COOKIE;
import static org.openkilda.model.Cookie.VERIFICATION_BROADCAST_RULE_COOKIE;
import static org.openkilda.model.Cookie.VERIFICATION_UNICAST_RULE_COOKIE;
import static org.openkilda.model.Cookie.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE;
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID;
import static org.openkilda.model.MeterId.MIN_SYSTEM_RULE_METER_ID;
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule;
import static org.openkilda.model.SwitchFeature.BFD;
import static org.openkilda.model.SwitchFeature.GROUP_PACKET_OUT_CONTROLLER;
import static org.openkilda.model.SwitchFeature.LIMITED_BURST_SIZE;
import static org.openkilda.model.SwitchFeature.MATCH_UDP_PORT;
import static org.openkilda.model.SwitchFeature.METERS;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN;
import static org.openkilda.model.SwitchFeature.PKTPS_FLAG;
import static org.openkilda.model.SwitchFeature.RESET_COUNTS_FLAG;

import org.openkilda.floodlight.KildaCore;
import org.openkilda.floodlight.KildaCoreConfig;
import org.openkilda.floodlight.OFFactoryVer12Mock;
import org.openkilda.floodlight.config.provider.FloodlightModuleConfigurationProvider;
import org.openkilda.floodlight.error.InvalidMeterIdException;
import org.openkilda.floodlight.error.SwitchOperationException;
import org.openkilda.floodlight.pathverification.IPathVerificationService;
import org.openkilda.floodlight.pathverification.PathVerificationService;
import org.openkilda.floodlight.pathverification.PathVerificationServiceConfig;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.test.standard.OutputCommands;
import org.openkilda.floodlight.test.standard.ReplaceSchemeOutputCommands;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.sabre.oss.conf4j.factory.jdkproxy.JdkProxyStaticConfigurationFactory;
import com.sabre.oss.conf4j.source.MapConfigurationSource;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.SwitchDescription;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.restserver.IRestApiService;
import org.apache.commons.lang3.StringUtils;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFBarrierReply;
import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFBucket;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsRequest;
import org.projectfloodlight.openflow.protocol.OFGroupAdd;
import org.projectfloodlight.openflow.protocol.OFGroupDelete;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsEntry;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsReply;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsRequest;
import org.projectfloodlight.openflow.protocol.OFGroupModCommand;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFMeterConfig;
import org.projectfloodlight.openflow.protocol.OFMeterConfigStatsReply;
import org.projectfloodlight.openflow.protocol.OFMeterConfigStatsRequest;
import org.projectfloodlight.openflow.protocol.OFMeterFlags;
import org.projectfloodlight.openflow.protocol.OFMeterMod;
import org.projectfloodlight.openflow.protocol.OFMeterModCommand;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.meterband.OFMeterBandDrop;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class SwitchManagerTest {
    private static final OutputCommands scheme = new ReplaceSchemeOutputCommands();
    private static final FloodlightModuleContext context = new FloodlightModuleContext();
    private static final long cookie = 123L;
    private static final long bandwidth = 20000L;
    private static final String cookieHex = "7B";
    private static final SwitchId SWITCH_ID = new SwitchId(0x0000000000000001L);
    private static final DatapathId EGRESS_SWITCH_DP_ID = DatapathId.of(1);
    private static final DatapathId defaultDpid = DatapathId.of(1);
    private static final String CENTEC_SWITCH_DESCRIPTION = "Centec";
    private static final String NOVIFLOW_SWITCH_DESCRIPTION = "E OF_13 NW400.6.4";
    private static final double MAX_NOVIFLOW_BURST_COEFFICIENT = 1.005;
    private static final long MAX_CENTEC_SWITCH_BURST_SIZE = 32000L;
    private static final int hugeBandwidth = 400000;
    private static final long unicastMeterId = createMeterIdForDefaultRule(VERIFICATION_UNICAST_RULE_COOKIE).getValue();
    private static final long unicastVxlanMeterId =
            createMeterIdForDefaultRule(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue();
    private static final long broadcastMeterId =
            createMeterIdForDefaultRule(VERIFICATION_BROADCAST_RULE_COOKIE).getValue();
    private static final long lldpPreDropMeterId = createMeterIdForDefaultRule(LLDP_INPUT_PRE_DROP_COOKIE).getValue();
    private static final long lldpTransitMeterId = createMeterIdForDefaultRule(LLDP_TRANSIT_COOKIE).getValue();
    private static final long lldpIngressMeterId = createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE).getValue();
    private static final long lldpPostIngressMeterId = createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).getValue();
    private static final long lldpPostIngressVxlanMeterId = createMeterIdForDefaultRule(
            LLDP_POST_INGRESS_VXLAN_COOKIE).getValue();
    private static final long lldpPostIngressOneSwitchMeterId = createMeterIdForDefaultRule(
            LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue();
    private SwitchManager switchManager;
    private IOFSwitchService ofSwitchService;
    private IRestApiService restApiService;
    private FeatureDetectorService featureDetectorService;
    private IOFSwitch iofSwitch;
    private SwitchDescription switchDescription;
    private DatapathId dpid;
    private SwitchManagerConfig config;

    @Before
    public void setUp() throws FloodlightModuleException {
        JdkProxyStaticConfigurationFactory factory = new JdkProxyStaticConfigurationFactory();
        config = factory.createConfiguration(SwitchManagerConfig.class, new MapConfigurationSource(emptyMap()));

        ofSwitchService = createMock(IOFSwitchService.class);
        restApiService = createMock(IRestApiService.class);
        featureDetectorService = createMock(FeatureDetectorService.class);
        iofSwitch = createMock(IOFSwitch.class);

        switchDescription = createMock(SwitchDescription.class);
        dpid = DatapathId.of(SWITCH_ID.toLong());

        PathVerificationServiceConfig config = EasyMock.createMock(PathVerificationServiceConfig.class);
        expect(config.getVerificationBcastPacketDst()).andReturn("00:26:E1:FF:FF:FF").anyTimes();
        replay(config);
        PathVerificationService pathVerificationService = EasyMock.createMock(PathVerificationService.class);
        expect(pathVerificationService.getConfig()).andReturn(config).anyTimes();
        replay(pathVerificationService);

        KildaCore kildaCore = EasyMock.createMock(KildaCore.class);
        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(
                context, KildaCore.class);
        KildaCoreConfig coreConfig = provider.getConfiguration(KildaCoreConfig.class);
        expect(kildaCore.getConfig()).andStubReturn(coreConfig);
        EasyMock.replay(kildaCore);
        context.addService(KildaCore.class, kildaCore);

        SwitchTrackingService switchTracking = createMock(SwitchTrackingService.class);
        switchTracking.setup(context);
        replay(switchTracking);
        context.addService(SwitchTrackingService.class, switchTracking);

        IFloodlightProviderService floodlightProvider = createMock(IFloodlightProviderService.class);
        floodlightProvider.addOFMessageListener(EasyMock.eq(OFType.ERROR), EasyMock.anyObject(SwitchManager.class));
        replay(floodlightProvider);
        context.addService(IFloodlightProviderService.class, floodlightProvider);

        context.addService(IRestApiService.class, restApiService);
        context.addService(IOFSwitchService.class, ofSwitchService);
        context.addService(FeatureDetectorService.class, featureDetectorService);
        context.addService(IPathVerificationService.class, pathVerificationService);

        switchManager = new SwitchManager();
        switchManager.init(context);
        switchManager.startUp(context);
    }

    @Test
    public void installDropRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();
        switchManager.installDropFlow(defaultDpid);
        assertEquals(scheme.installDropFlowRule(), capture.getValue());
    }

    @Test
    public void installVerificationBroadcastRule() throws Exception {
        runInstallVerificationBroadcastRule(true);
    }

    @Test
    public void installVerificationBroadcastWithoutUdpMatchSupportRule() throws Exception {
        runInstallVerificationBroadcastRule(false);
    }

    public void runInstallVerificationBroadcastRule(boolean supportsUdpPortMatch) throws Exception {
        mockGetGroupsRequest(ImmutableList.of(ROUND_TRIP_LATENCY_GROUP_ID));
        mockGetMetersRequest(ImmutableList.of(meterId), true, 10L);
        mockFlowStatsRequest(VERIFICATION_BROADCAST_RULE_COOKIE);
        mockBarrierRequest();

        Capture<OFFlowMod> captureVerificationBroadcast = EasyMock.newCapture();

        expect(ofSwitchService.getActiveSwitch(defaultDpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getId()).andReturn(defaultDpid).times(4);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.write(capture(captureVerificationBroadcast))).andReturn(true).times(2);


        expect(iofSwitch.write(anyObject(OFMeterMod.class))).andReturn(true);
        expect(switchDescription.getManufacturerDescription()).andReturn("").times(2);
        Set<SwitchFeature> features = Sets.newHashSet(BFD, GROUP_PACKET_OUT_CONTROLLER, NOVIFLOW_PUSH_POP_VXLAN);
        if (supportsUdpPortMatch) {
            features.add(MATCH_UDP_PORT);
        }
        expect(featureDetectorService.detectSwitch(iofSwitch)).andStubReturn(features);
        expectLastCall();

        replay(ofSwitchService);
        replay(iofSwitch);
        replay(switchDescription);
        replay(featureDetectorService);

        switchManager.installVerificationRule(defaultDpid, true);
        assertEquals(scheme.installVerificationBroadcastRule(supportsUdpPortMatch),
                captureVerificationBroadcast.getValue());
    }

    @Test
    public void installVerificationUnicastRule() throws Exception {
        mockGetMetersRequest(Lists.newArrayList(broadcastMeterId), true, 10L);
        mockBarrierRequest();
        expect(iofSwitch.write(anyObject(OFMeterMod.class))).andReturn(true).times(1);
        Capture<OFFlowMod> capture = prepareForInstallTest();
        switchManager.installVerificationRule(defaultDpid, false);
        assertEquals(scheme.installVerificationUnicastRule(defaultDpid), capture.getValue());
    }

    @Test
    public void installDropLoopRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installDropLoopRule(dpid);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installDropLoopRule(dpid), result);
    }

    @Test
    public void installDropFlowForTable() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installDropFlowForTable(dpid, 1, DROP_RULE_COOKIE);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installDropFlowForTable(dpid, 1, DROP_RULE_COOKIE), result);
    }

    @Test
    public void installEgressIslVxlanRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installEgressIslVxlanRule(dpid, 1);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installEgressIslVxlanRule(dpid, 1), result);
    }

    @Test
    public void installTransitIslVxlanRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installTransitIslVxlanRule(dpid, 1);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installTransitIslVxlanRule(dpid, 1), result);
    }

    @Test
    public void installEgressIslVlanRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installEgressIslVlanRule(dpid, 1);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installEgressIslVlanRule(dpid, 1), result);
    }

    @Test
    public void installIntermediateIngressRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installIntermediateIngressRule(dpid, 1);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installIntermediateIngressRule(dpid, 1), result);
    }

    @Test
    public void installPreIngressTablePassThroughDefaultRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installPreIngressTablePassThroughDefaultRule(dpid);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installPreIngressTablePassThroughDefaultRule(dpid), result);
    }

    @Test
    public void installEgressTablePassThroughDefaultRule() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installEgressTablePassThroughDefaultRule(dpid);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installEgressTablePassThroughDefaultRule(dpid), result);
    }

    @Test
    public void installRoundTripLatencyFlow() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installRoundTripLatencyFlow(dpid);

        OFFlowMod result = capture.getValue();
        assertEquals(scheme.installRoundTripLatencyRule(dpid), result);
    }

    @Test
    public void installBfdCatchFlow() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();
        switchManager.installBfdCatchFlow(defaultDpid);
        assertEquals(scheme.installBfdCatchRule(defaultDpid), capture.getValue());
    }

    @Test
    public void installUnicastVerificationRuleVxlan() throws Exception {
        mockGetMetersRequest(Lists.newArrayList(broadcastMeterId), true, 10L);
        mockBarrierRequest();
        expect(iofSwitch.write(anyObject(OFMeterMod.class))).andReturn(true).times(1);
        Capture<OFFlowMod> capture = prepareForInstallTest();
        switchManager.installUnicastVerificationRuleVxlan(defaultDpid);
        assertEquals(scheme.installUnicastVerificationRuleVxlan(defaultDpid), capture.getValue());
    }


    @Test
    public void installIngressFlowReplaceActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                inputVlanId, transitVlanId, OutputVlanType.REPLACE, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressReplaceFlowMod(dpid, inputPort, outputPort, inputVlanId, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowReplaceActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                inputVlanId, transitVlanId, OutputVlanType.REPLACE, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressReplaceFlowMod(dpid, inputPort, outputPort, inputVlanId, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowPopActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                inputVlanId, transitVlanId, OutputVlanType.POP, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressPopFlowMod(dpid, inputPort, outputPort, inputVlanId, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowPopActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                inputVlanId, transitVlanId, OutputVlanType.POP, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressPopFlowMod(dpid, inputPort, outputPort, inputVlanId, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowPushActionvUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                0, transitVlanId, OutputVlanType.PUSH, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressPushFlowMod(dpid, inputPort, outputPort, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowPushActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                0, transitVlanId, OutputVlanType.PUSH, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressPushFlowMod(dpid, inputPort, outputPort, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowNoneActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                0, transitVlanId, OutputVlanType.NONE, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressNoneFlowMod(dpid, inputPort, outputPort, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowNoneActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                0, transitVlanId, OutputVlanType.NONE, meterId, encapsulationType, false);

        assertEquals(
                scheme.ingressNoneFlowMod(dpid, inputPort, outputPort, transitVlanId, meterId, cookie,
                        encapsulationType, EGRESS_SWITCH_DP_ID),
                capture.getValue());
    }

    @Test
    public void installIngressFlowWithoutResetCountsFlag() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest(true);

        switchManager.installIngressFlow(dpid, EGRESS_SWITCH_DP_ID, cookieHex, cookie, inputPort, outputPort,
                0, transitVlanId, OutputVlanType.NONE, meterId, FlowEncapsulationType.TRANSIT_VLAN, false);

        final OFFlowMod actual = capture.getValue();
        assertThat(actual.getFlags().isEmpty(), is(true));
    }

    @Test
    public void installEgressFlowNoneActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, 0,
                OutputVlanType.NONE, encapsulationType, false);

        assertEquals(
                scheme.egressNoneFlowMod(dpid, inputPort, outputPort, transitVlanId, cookie, encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowNoneActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, 0,
                OutputVlanType.NONE, encapsulationType, false);

        assertEquals(
                scheme.egressNoneFlowMod(dpid, inputPort, outputPort, transitVlanId, cookie, encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowPushActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, outputVlanId,
                OutputVlanType.PUSH, encapsulationType, false);

        assertEquals(
                scheme.egressPushFlowMod(dpid, inputPort, outputPort, transitVlanId, outputVlanId, cookie,
                        encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowPushActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, outputVlanId,
                OutputVlanType.PUSH, encapsulationType, false);

        assertEquals(
                scheme.egressPushFlowMod(dpid, inputPort, outputPort, transitVlanId, outputVlanId, cookie,
                        encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowPopActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, 0,
                OutputVlanType.POP, encapsulationType, false);

        assertEquals(
                scheme.egressPopFlowMod(dpid, inputPort, outputPort, transitVlanId, cookie, encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowPopActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, 0,
                OutputVlanType.POP, encapsulationType, false);

        assertEquals(
                scheme.egressPopFlowMod(dpid, inputPort, outputPort, transitVlanId, cookie, encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowReplaceActionUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, outputVlanId,
                OutputVlanType.REPLACE, encapsulationType, false);

        assertEquals(
                scheme.egressReplaceFlowMod(dpid, inputPort, outputPort, transitVlanId, outputVlanId, cookie,
                        encapsulationType),
                capture.getValue());
    }

    @Test
    public void installEgressFlowReplaceActionUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installEgressFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId, outputVlanId,
                OutputVlanType.REPLACE, encapsulationType, false);

        assertEquals(
                scheme.egressReplaceFlowMod(dpid, inputPort, outputPort, transitVlanId, outputVlanId, cookie,
                        encapsulationType),
                capture.getValue());
    }

    @Test
    public void installTransitFlowUsingTransitVlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;

        switchManager.installTransitFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId,
                encapsulationType, false);

        assertEquals(
                scheme.transitFlowMod(inputPort, outputPort, transitVlanId, cookie, encapsulationType),
                capture.getValue());
    }

    @Test
    public void installTransitFlowUsingVxlan() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.VXLAN;

        switchManager.installTransitFlow(dpid, cookieHex, cookie, inputPort, outputPort, transitVlanId,
                encapsulationType, false);

        assertEquals(
                scheme.transitFlowMod(inputPort, outputPort, transitVlanId, cookie, encapsulationType),
                capture.getValue());
    }

    @Test
    public void installOneSwitchFlowReplaceAction() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installOneSwitchFlow(dpid, cookieHex, cookie,
                inputPort, outputPort, inputVlanId, outputVlanId, OutputVlanType.REPLACE, meterId, false);

        assertEquals(
                scheme.oneSwitchReplaceFlowMod(inputPort, outputPort, inputVlanId, outputVlanId, meterId, cookie),
                capture.getValue());
    }

    @Test
    public void installOneSwitchFlowPushAction() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installOneSwitchFlow(dpid, cookieHex, cookie,
                inputPort, outputPort, 0, outputVlanId, OutputVlanType.PUSH, meterId, false);

        assertEquals(
                scheme.oneSwitchPushFlowMod(inputPort, outputPort, outputVlanId, meterId, cookie),
                capture.getValue());
    }

    @Test
    public void installOneSwitchFlowPopAction() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installOneSwitchFlow(dpid, cookieHex, cookie,
                inputPort, outputPort, inputVlanId, 0, OutputVlanType.POP, meterId, false);

        assertEquals(
                scheme.oneSwitchPopFlowMod(inputPort, outputPort, inputVlanId, meterId, cookie),
                capture.getValue());
    }

    @Test
    public void installOneSwitchFlowNoneAction() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest();

        switchManager.installOneSwitchFlow(dpid, cookieHex, cookie,
                inputPort, outputPort, 0, 0, OutputVlanType.NONE, meterId, false);

        assertEquals(
                scheme.oneSwitchNoneFlowMod(inputPort, outputPort, meterId, cookie),
                capture.getValue());
    }

    @Test
    public void installOneSwitchFlowNoneActionWithoutResetCountsFlag() throws Exception {
        Capture<OFFlowMod> capture = prepareForInstallTest(true);

        switchManager.installOneSwitchFlow(dpid, cookieHex, cookie,
                inputPort, outputPort, 0, 0, OutputVlanType.NONE, meterId, false);

        final OFFlowMod actual = capture.getValue();
        assertThat(actual.getFlags().isEmpty(), is(true));
    }

    @Test
    public void dumpFlowTable() {
        // TODO
    }

    @Test
    public void dumpMeters() {
        // TODO
    }

    @Test
    public void installBandwidthMeterForCentecSwitch() throws Exception {
        runInstallMeterTest(bandwidth, Math.round(bandwidth * config.getFlowMeterBurstCoefficient()), true, false);
    }

    @Test
    public void installHugeBandwidthMeterForCentecSwitch() throws Exception {
        runInstallMeterTest(hugeBandwidth, MAX_CENTEC_SWITCH_BURST_SIZE, true, false);
    }

    @Test
    public void installBandwidthMeterForNoviFlowSwitch() throws Exception {
        runInstallMeterTest(bandwidth, Math.round(bandwidth * MAX_NOVIFLOW_BURST_COEFFICIENT), false, true);
    }

    @Test
    public void installBandwidthMeter() throws Exception {
        runInstallMeterTest(bandwidth, Math.round(bandwidth * config.getFlowMeterBurstCoefficient()), false, false);
    }

    private void runInstallMeterTest(long bandwidth, long burstSize, boolean isCentecSwitch, boolean isNoviFlowSwitch)
            throws Exception {
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getId()).andReturn(dpid);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(switchDescription.getManufacturerDescription())
                .andStubReturn(isCentecSwitch ? CENTEC_SWITCH_DESCRIPTION : "");
        expect(switchDescription.getSoftwareDescription())
                .andStubReturn(isNoviFlowSwitch ? NOVIFLOW_SWITCH_DESCRIPTION : "");

        expect(iofSwitch.write(scheme.installMeter(bandwidth, burstSize, meterId))).andReturn(true);
        expect(iofSwitch.writeRequest(anyObject(OFBarrierRequest.class)))
                .andReturn(Futures.immediateFuture(createMock(OFBarrierReply.class)));

        replay(ofSwitchService);
        replay(iofSwitch);
        replay(switchDescription);

        switchManager.installMeterForFlow(dpid, bandwidth, meterId);
    }

    @Test
    public void installFlowMeterWithNegativeIdTest() throws SwitchOperationException {
        runInstallMeterWithInvalidId(-1L);
    }

    @Test
    public void installFlowMeterWithIdForDefaultRuleTest() throws SwitchOperationException {
        for (int id = MIN_SYSTEM_RULE_METER_ID; id < MAX_SYSTEM_RULE_METER_ID; id++) {
            runInstallMeterWithInvalidId(id);
        }
    }

    private void runInstallMeterWithInvalidId(long meterId) throws SwitchOperationException {
        SwitchManager mock = mock(SwitchManager.class);
        mock.installMeterForFlow(dpid, bandwidth, meterId);
        expectLastCall().andThrow(new InvalidMeterIdException(null, null));
    }

    @Test
    public void deleteMeter() throws Exception {
        mockBarrierRequest();
        final Capture<OFMeterMod> capture = prepareForMeterTest();
        switchManager.deleteMeter(dpid, meterId);
        final OFMeterMod meterMod = capture.getValue();
        assertEquals(meterMod.getCommand(), OFMeterModCommand.DELETE);
        assertEquals(meterMod.getMeterId(), meterId);
    }

    @Test(expected = InvalidMeterIdException.class)
    public void deleteMeterWithInvalidId() throws SwitchOperationException {
        switchManager.deleteMeter(dpid, -1);
    }

    @Test
    public void shouldDeleteAllNonDefaultRules() throws Exception {
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture();
        expect(iofSwitch.write(capture(capture))).andReturn(true);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        List<Long> deletedRules = switchManager.deleteAllNonDefaultRules(dpid);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertNull(actual.getMatch().get(MatchField.IN_PORT));
        assertNull(actual.getMatch().get(MatchField.VLAN_VID));
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(cookie, actual.getCookie().getValue());
        assertEquals(U64.NO_MASK, actual.getCookieMask());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteDefaultRulesWithoutMeters() throws Exception {
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(new OFFactoryVer12Mock());
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        expect(switchDescription.getManufacturerDescription()).andStubReturn(OVS_MANUFACTURER);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE, CATCH_BFD_RULE_COOKIE,
                ROUND_TRIP_LATENCY_RULE_COOKIE, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE,
                LLDP_INGRESS_COOKIE, LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_ONE_SWITCH_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(18);
        expect(iofSwitch.write(isA(OFGroupDelete.class))).andReturn(true).once();

        mockBarrierRequest();
        mockFlowStatsRequest(cookie);
        expectLastCall();

        replay(ofSwitchService, iofSwitch, switchDescription);

        // when
        List<Long> deletedRules = switchManager.deleteDefaultRules(dpid, Collections.emptyList(),
                Collections.emptyList(), Collections.emptySet(), true, true);

        // then
        final List<OFFlowMod> actual = capture.getValues();
        assertEquals(18, actual.size());
        assertThat(actual, everyItem(hasProperty("command", equalTo(OFFlowModCommand.DELETE))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(DROP_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(VERIFICATION_BROADCAST_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(VERIFICATION_UNICAST_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(DROP_VERIFICATION_LOOP_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(CATCH_BFD_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(ROUND_TRIP_LATENCY_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_INGRESS_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_POST_INGRESS_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_EGRESS_PASS_THROUGH_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_TRANSIT_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_INPUT_PRE_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_TRANSIT_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_INGRESS_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_POST_INGRESS_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_POST_INGRESS_VXLAN_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE)))));

        assertThat(deletedRules, containsInAnyOrder(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE, CATCH_BFD_RULE_COOKIE,
                ROUND_TRIP_LATENCY_RULE_COOKIE, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE,
                LLDP_INGRESS_COOKIE, LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_ONE_SWITCH_COOKIE));
    }

    @Test
    public void shouldDeleteDefaultRulesWithMeters() throws Exception {
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);

        expect(switchDescription.getManufacturerDescription()).andStubReturn(StringUtils.EMPTY);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE, CATCH_BFD_RULE_COOKIE, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE, LLDP_INGRESS_COOKIE,
                LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_VXLAN_COOKIE, LLDP_POST_INGRESS_ONE_SWITCH_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(28);
        expect(iofSwitch.write(isA(OFGroupDelete.class))).andReturn(true).once();

        mockBarrierRequest();
        mockFlowStatsRequest(cookie);
        mockGetMetersRequest(Collections.emptyList(), true, 0);

        replay(ofSwitchService, iofSwitch, switchDescription);

        // when
        List<Long> deletedRules = switchManager.deleteDefaultRules(dpid, Collections.emptyList(),
                Collections.emptyList(), Collections.emptySet(), true, true);

        // then
        assertThat(deletedRules, containsInAnyOrder(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE, CATCH_BFD_RULE_COOKIE, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE,
                LLDP_INGRESS_COOKIE, LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_ONE_SWITCH_COOKIE));

        final List<OFFlowMod> actual = capture.getValues();
        assertEquals(28, actual.size());

        // check rules deletion
        List<OFFlowMod> rulesMod = actual.subList(0, 18);
        assertThat(rulesMod, everyItem(hasProperty("command", equalTo(OFFlowModCommand.DELETE))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(DROP_RULE_COOKIE)))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(VERIFICATION_BROADCAST_RULE_COOKIE)))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(VERIFICATION_UNICAST_RULE_COOKIE)))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(DROP_VERIFICATION_LOOP_RULE_COOKIE)))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(CATCH_BFD_RULE_COOKIE)))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(ROUND_TRIP_LATENCY_RULE_COOKIE)))));
        assertThat(rulesMod, hasItem(hasProperty("cookie", equalTo(U64.of(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_INGRESS_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_POST_INGRESS_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_EGRESS_PASS_THROUGH_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(MULTITABLE_TRANSIT_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_INPUT_PRE_DROP_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_TRANSIT_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_INGRESS_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_POST_INGRESS_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_POST_INGRESS_VXLAN_COOKIE)))));
        assertThat(actual, hasItem(hasProperty("cookie", equalTo(U64.of(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE)))));


        // verify meters deletion
        List<OFFlowMod> metersMod = actual.subList(rulesMod.size(), rulesMod.size() + 9);
        assertThat(metersMod, everyItem(hasProperty("command", equalTo(OFMeterModCommand.DELETE))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(broadcastMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(unicastMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(unicastVxlanMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(lldpPreDropMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(lldpTransitMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(lldpIngressMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(lldpPostIngressMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(lldpPostIngressVxlanMeterId))));
        assertThat(metersMod, hasItem(hasProperty("meterId", equalTo(lldpPostIngressOneSwitchMeterId))));

        // verify group deletion
        List<OFFlowMod> groupMod = actual.subList(rulesMod.size() + metersMod.size(), actual.size());
        assertThat(groupMod, everyItem(hasProperty("command", equalTo(OFGroupModCommand.DELETE))));
        assertThat(groupMod, hasItem(hasProperty("group", equalTo(OFGroup.of(ROUND_TRIP_LATENCY_GROUP_ID)))));
    }

    @Test
    public void shouldDeleteRuleByCookie() throws Exception {
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder().cookie(cookie).build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertNull(actual.getMatch().get(MatchField.IN_PORT));
        assertNull(actual.getMatch().get(MatchField.VLAN_VID));
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(cookie, actual.getCookie().getValue());
        assertEquals(U64.NO_MASK, actual.getCookieMask());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByInPort() throws Exception {
        // given
        final int testInPort = 11;

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder().inPort(testInPort)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .egressSwitchId(SWITCH_ID).build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertEquals(testInPort, actual.getMatch().get(MatchField.IN_PORT).getPortNumber());
        assertNull(actual.getMatch().get(MatchField.VLAN_VID));
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByInVlan() throws Exception {
        // given
        final short testInVlan = 101;
        FlowEncapsulationType flowEncapsulationType = FlowEncapsulationType.TRANSIT_VLAN;
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder().encapsulationId((int) testInVlan)
                .encapsulationType(flowEncapsulationType).build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertEquals(testInVlan, actual.getMatch().get(MatchField.VLAN_VID).getVlan());
        assertNull(actual.getMatch().get(MatchField.IN_PORT));
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByInPortAndVlan() throws Exception {
        // given
        final int testInPort = 11;
        final short testInVlan = 101;

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder()
                .inPort(testInPort)
                .encapsulationId((int) testInVlan)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .egressSwitchId(SWITCH_ID)
                .build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertEquals(testInPort, actual.getMatch().get(MatchField.IN_PORT).getPortNumber());
        assertEquals(testInVlan, actual.getMatch().get(MatchField.VLAN_VID).getVlan());
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByInPortAndVxlanTunnel() throws Exception {
        // given
        final int testInPort = 11;
        final short testInVlan = 101;

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder()
                .inPort(testInPort)
                .encapsulationId((int) testInVlan)
                .encapsulationType(FlowEncapsulationType.VXLAN)
                .egressSwitchId(SWITCH_ID)
                .build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertEquals(testInPort, actual.getMatch().get(MatchField.IN_PORT).getPortNumber());
        assertEquals(testInVlan, actual.getMatch().get(MatchField.TUNNEL_ID).getValue());
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByPriority() throws Exception {
        // given
        final int testPriority = 999;

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder().priority(testPriority).build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertNull(actual.getMatch().get(MatchField.IN_PORT));
        assertNull(actual.getMatch().get(MatchField.VLAN_VID));
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertEquals(testPriority, actual.getPriority());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByInPortVlanAndPriority() throws Exception {
        // given
        final int testInPort = 11;
        final short testInVlan = 101;
        final int testPriority = 999;

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder()
                .inPort(testInPort)
                .encapsulationId((int) testInVlan)
                .priority(testPriority)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .egressSwitchId(SWITCH_ID)
                .build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertEquals(testInPort, actual.getMatch().get(MatchField.IN_PORT).getPortNumber());
        assertEquals(testInVlan, actual.getMatch().get(MatchField.VLAN_VID).getVlan());
        assertEquals("any", actual.getOutPort().toString());
        assertEquals(0, actual.getInstructions().size());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertEquals(testPriority, actual.getPriority());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldDeleteRuleByOutPort() throws Exception {
        // given
        final int testOutPort = 21;

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);

        mockFlowStatsRequest(cookie, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE);

        Capture<OFFlowMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(3);

        mockBarrierRequest();
        mockFlowStatsRequest(DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE, VERIFICATION_UNICAST_RULE_COOKIE);
        expectLastCall();

        replay(ofSwitchService, iofSwitch);

        // when
        DeleteRulesCriteria criteria = DeleteRulesCriteria.builder().outPort(testOutPort).build();
        List<Long> deletedRules = switchManager.deleteRulesByCriteria(dpid, false, null, criteria);

        // then
        final OFFlowMod actual = capture.getValue();
        assertEquals(OFFlowModCommand.DELETE, actual.getCommand());
        assertNull(actual.getMatch().get(MatchField.IN_PORT));
        assertNull(actual.getMatch().get(MatchField.VLAN_VID));
        assertEquals(testOutPort, actual.getOutPort().getPortNumber());
        assertEquals(0L, actual.getCookie().getValue());
        assertEquals(0L, actual.getCookieMask().getValue());
        assertThat(deletedRules, containsInAnyOrder(cookie));
    }

    @Test
    public void shouldInstallMeterWithKbpsFlag() throws Exception {
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        // define that switch is Centec
        expect(switchDescription.getManufacturerDescription()).andStubReturn("Centec Inc.");
        expect(featureDetectorService.detectSwitch(iofSwitch)).andStubReturn(Sets.newHashSet(METERS));
        mockGetMetersRequest(Collections.emptyList(), true, 0);
        mockBarrierRequest();

        Capture<OFMeterMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(1);

        replay(ofSwitchService, iofSwitch, switchDescription, featureDetectorService);

        // when
        switchManager.installMeterForDefaultRule(iofSwitch, unicastMeterId, 100L, new ArrayList<>());

        // then
        final List<OFMeterMod> actual = capture.getValues();
        assertEquals(1, actual.size());

        // verify meters creation
        assertThat(actual, everyItem(hasProperty("command", equalTo(OFMeterModCommand.ADD))));
        assertThat(actual, everyItem(hasProperty("meterId", equalTo(unicastMeterId))));
        assertThat(actual, everyItem(hasProperty("flags",
                contains(OFMeterFlags.KBPS, OFMeterFlags.STATS, OFMeterFlags.BURST))));
        for (OFMeterMod mod : actual) {
            long expectedBurstSize =
                    config.getSystemMeterBurstSizeInPackets() * config.getDiscoPacketSize() * 8 / 1024L;
            assertThat(mod.getMeters(), everyItem(hasProperty("burstSize", is(expectedBurstSize))));
        }
    }

    @Test
    public void shouldInstallMeterWithPktpsFlag() throws Exception {
        long expectedRate = config.getUnicastRateLimit();
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        expect(switchDescription.getManufacturerDescription()).andStubReturn(StringUtils.EMPTY);
        expect(featureDetectorService.detectSwitch(iofSwitch)).andStubReturn(Sets.newHashSet(PKTPS_FLAG));
        mockGetMetersRequest(Collections.emptyList(), true, 0);
        mockBarrierRequest();

        Capture<OFMeterMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(1);

        replay(ofSwitchService, iofSwitch, switchDescription, featureDetectorService);

        // when
        switchManager.installMeterForDefaultRule(iofSwitch, unicastMeterId, expectedRate, new ArrayList<>());

        // verify meters installation
        final List<OFMeterMod> actual = capture.getValues();
        assertEquals(1, actual.size());

        // verify meters creation
        assertThat(actual, everyItem(hasProperty("command", equalTo(OFMeterModCommand.ADD))));
        assertThat(actual, everyItem(hasProperty("meterId", equalTo(unicastMeterId))));
        assertThat(actual, everyItem(hasProperty("flags",
                contains(OFMeterFlags.PKTPS, OFMeterFlags.STATS, OFMeterFlags.BURST))));
        for (OFMeterMod mod : actual) {
            assertThat(mod.getMeters(),
                    everyItem(hasProperty("burstSize", is(config.getSystemMeterBurstSizeInPackets()))));
        }
    }

    @Test
    public void shouldReinstallMeterIfFlagIsIncorrect() throws Exception {
        long expectedRate = config.getUnicastRateLimit();
        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        // define that switch is Centec
        expect(switchDescription.getManufacturerDescription()).andStubReturn("Centec Inc.");
        expect(featureDetectorService.detectSwitch(iofSwitch)).andStubReturn(Sets.newHashSet(METERS));
        mockBarrierRequest();
        mockGetMetersRequest(Lists.newArrayList(unicastMeterId), false, expectedRate);

        Capture<OFMeterMod> capture = EasyMock.newCapture(CaptureType.ALL);
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(2);

        replay(ofSwitchService, iofSwitch, switchDescription, featureDetectorService);

        // when
        switchManager.installMeterForDefaultRule(iofSwitch, unicastMeterId, expectedRate, new ArrayList<>());

        // verify meters installation
        final List<OFMeterMod> actual = capture.getValues();
        assertEquals(2, actual.size());

        // verify meters deletion
        assertThat(actual.get(0), hasProperty("command", equalTo(OFMeterModCommand.DELETE)));
        // verify meter installation
        assertThat(actual.get(1), hasProperty("command", equalTo(OFMeterModCommand.ADD)));
        assertThat(actual.get(1), hasProperty("meterId", equalTo(unicastMeterId)));
        assertThat(actual.get(1), hasProperty("flags",
                containsInAnyOrder(OFMeterFlags.KBPS, OFMeterFlags.STATS, OFMeterFlags.BURST)));
    }

    @Test
    public void shouldRenstallMetersIfRateIsUpdated() throws Exception {
        long unicastMeter = createMeterIdForDefaultRule(VERIFICATION_UNICAST_RULE_COOKIE).getValue();
        long originRate = config.getBroadcastRateLimit();
        long updatedRate = config.getBroadcastRateLimit() + 10;

        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        expect(switchDescription.getManufacturerDescription()).andStubReturn(StringUtils.EMPTY);
        expect(featureDetectorService.detectSwitch(iofSwitch)).andStubReturn(Sets.newHashSet(PKTPS_FLAG));
        Capture<OFMeterMod> capture = EasyMock.newCapture(CaptureType.ALL);
        // 1 meter deletion + 1 meters installation
        expect(iofSwitch.write(capture(capture))).andReturn(true).times(2);

        mockBarrierRequest();
        mockGetMetersRequest(Lists.newArrayList(unicastMeter), true, originRate);
        replay(ofSwitchService, iofSwitch, switchDescription, featureDetectorService);

        switchManager.installMeterForDefaultRule(iofSwitch, unicastMeter, updatedRate, new ArrayList<>());

        final List<OFMeterMod> actual = capture.getValues();
        assertEquals(2, actual.size());

        // verify meters deletion
        assertThat(actual.get(0), hasProperty("command", equalTo(OFMeterModCommand.DELETE)));
        // verify meter installation
        assertThat(actual.get(1), hasProperty("command", equalTo(OFMeterModCommand.ADD)));
        assertThat(actual.get(1), hasProperty("meterId", equalTo(unicastMeter)));
        assertThat(actual.get(1), hasProperty("flags",
                containsInAnyOrder(OFMeterFlags.PKTPS, OFMeterFlags.STATS, OFMeterFlags.BURST)));
    }

    @Test
    public void shouldNotInstallMetersIfAlreadyExists() throws Exception {
        long expectedRate = config.getBroadcastRateLimit();

        // given
        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        expect(switchDescription.getManufacturerDescription()).andStubReturn(StringUtils.EMPTY);
        expect(switchDescription.getSoftwareDescription()).andStubReturn(StringUtils.EMPTY);
        Capture<OFFlowMod> capture = EasyMock.newCapture();
        expect(iofSwitch.write(capture(capture))).andStubReturn(true);
        expect(featureDetectorService.detectSwitch(iofSwitch))
                .andReturn(Sets.newHashSet(GROUP_PACKET_OUT_CONTROLLER, NOVIFLOW_COPY_FIELD, PKTPS_FLAG))
                .times(8);
        mockBarrierRequest();
        mockGetMetersRequest(Lists.newArrayList(unicastMeterId, broadcastMeterId), true, expectedRate);
        mockGetGroupsRequest(Lists.newArrayList(ROUND_TRIP_LATENCY_GROUP_ID));
        replay(ofSwitchService, iofSwitch, switchDescription, featureDetectorService);

        switchManager.installDefaultRules(iofSwitch.getId());
    }

    @Test
    public void validateRoundTripLatencyGroup() {
        OFGroupAdd groupAdd = getOfGroupAddInstruction();
        assertTrue(runValidateRoundTripLatencyGroup(groupAdd.getBuckets()));
    }

    private OFGroupAdd getOfGroupAddInstruction() {
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        replay(iofSwitch);

        return switchManager.getInstallRoundTripLatencyGroupInstruction(iofSwitch);
    }

    private boolean runValidateRoundTripLatencyGroup(List<OFBucket> buckets) {
        return runValidateRoundTripLatencyGroup(ROUND_TRIP_LATENCY_GROUP_ID, buckets);
    }

    private boolean runValidateRoundTripLatencyGroup(int groupId, List<OFBucket> buckets) {
        OFGroupDescStatsEntry entry = ofFactory.buildGroupDescStatsEntry()
                .setGroup(OFGroup.of(groupId))
                .setGroupType(OFGroupType.ALL)
                .setBuckets(buckets)
                .build();

        return switchManager.validateRoundTripLatencyGroup(dpid, entry);
    }


    private void mockBarrierRequest() throws InterruptedException, ExecutionException, TimeoutException {
        OFBarrierReply ofBarrierReply = mock(OFBarrierReply.class);

        ListenableFuture<OFBarrierReply> ofBarrierFuture = mock(ListenableFuture.class);
        expect(ofBarrierFuture.get(anyLong(), anyObject())).andStubReturn(ofBarrierReply);
        replay(ofBarrierFuture);

        expect(iofSwitch.writeRequest(anyObject(OFBarrierRequest.class))).andStubReturn(ofBarrierFuture);
    }

    private void mockFlowStatsRequest(Long... cookies)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<OFFlowStatsEntry> ofFlowStatsEntries = Arrays.stream(cookies)
                .map(cookie -> {
                    OFFlowStatsEntry ofFlowStatsEntry = mock(OFFlowStatsEntry.class);
                    expect(ofFlowStatsEntry.getCookie()).andStubReturn(U64.of(cookie));
                    replay(ofFlowStatsEntry);
                    return ofFlowStatsEntry;
                })
                .collect(Collectors.toList());

        OFFlowStatsEntry ofFlowStatsEntry = mock(OFFlowStatsEntry.class);
        expect(ofFlowStatsEntry.getCookie()).andStubReturn(U64.of(cookie));
        replay(ofFlowStatsEntry);

        OFFlowStatsReply ofFlowStatsReply = mock(OFFlowStatsReply.class);
        expect(ofFlowStatsReply.getXid()).andReturn(0L);
        expect(ofFlowStatsReply.getFlags()).andReturn(emptySet());
        expect(ofFlowStatsReply.getEntries()).andStubReturn(ofFlowStatsEntries);
        replay(ofFlowStatsReply);

        ListenableFuture<List<OFFlowStatsReply>> ofStatsFuture = mock(ListenableFuture.class);
        expect(ofStatsFuture.get(anyLong(), anyObject())).andStubReturn(singletonList(ofFlowStatsReply));
        replay(ofStatsFuture);

        expect(iofSwitch.writeStatsRequest(isA(OFFlowStatsRequest.class))).andReturn(ofStatsFuture);
    }


    private Capture<OFFlowMod> prepareForInstallTest() {
        return prepareForInstallTest(false);
    }

    private Capture<OFFlowMod> prepareForInstallTest(boolean isCentecSwitch) {
        Capture<OFFlowMod> capture = EasyMock.newCapture();

        Set<SwitchFeature> features;
        if (isCentecSwitch) {
            features = Sets.newHashSet(METERS, LIMITED_BURST_SIZE);
        } else {
            features = Sets.newHashSet(BFD, GROUP_PACKET_OUT_CONTROLLER, NOVIFLOW_COPY_FIELD,
                    NOVIFLOW_PUSH_POP_VXLAN, RESET_COUNTS_FLAG);
        }
        expect(featureDetectorService.detectSwitch(iofSwitch)).andStubReturn(features);

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(iofSwitch.getId()).andStubReturn(dpid);
        expect(switchDescription.getManufacturerDescription()).andStubReturn(isCentecSwitch ? "Centec" : "");
        expect(iofSwitch.write(capture(capture))).andReturn(true);
        expectLastCall();

        replay(ofSwitchService);
        replay(iofSwitch);
        replay(switchDescription);
        replay(featureDetectorService);

        return capture;
    }

    private Capture<OFMeterMod> prepareForMeterTest() {
        Capture<OFMeterMod> capture = EasyMock.newCapture();

        expect(ofSwitchService.getActiveSwitch(dpid)).andStubReturn(iofSwitch);
        expect(iofSwitch.getOFFactory()).andStubReturn(ofFactory);
        expect(iofSwitch.getSwitchDescription()).andStubReturn(switchDescription);
        expect(switchDescription.getManufacturerDescription()).andStubReturn("");
        expect(iofSwitch.write(capture(capture))).andReturn(true);
        expectLastCall();

        replay(ofSwitchService);
        replay(iofSwitch);
        replay(switchDescription);

        return capture;
    }

    private void mockGetMetersRequest(List<Long> meterIds, boolean supportsPkts, long rate) throws Exception {
        List<OFMeterConfig> meterConfigs = new ArrayList<>(meterIds.size());
        for (Long meterId : meterIds) {
            OFMeterBandDrop bandDrop = mock(OFMeterBandDrop.class);
            expect(bandDrop.getRate()).andStubReturn(rate);

            OFMeterConfig meterConfig = mock(OFMeterConfig.class);
            expect(meterConfig.getEntries()).andStubReturn(Collections.singletonList(bandDrop));
            expect(meterConfig.getMeterId()).andStubReturn(meterId);

            Set<OFMeterFlags> flags = ImmutableSet.of(OFMeterFlags.STATS,
                    supportsPkts ? OFMeterFlags.PKTPS : OFMeterFlags.KBPS);
            expect(meterConfig.getFlags()).andStubReturn(flags);
            replay(bandDrop, meterConfig);
            meterConfigs.add(meterConfig);
        }

        OFMeterConfigStatsReply statsReply = mock(OFMeterConfigStatsReply.class);
        expect(statsReply.getEntries()).andStubReturn(meterConfigs);

        ListenableFuture<List<OFMeterConfigStatsReply>> ofStatsFuture = mock(ListenableFuture.class);
        expect(ofStatsFuture.get(anyLong(), anyObject())).andStubReturn(Collections.singletonList(statsReply));

        replay(statsReply, ofStatsFuture);
        expect(iofSwitch.writeStatsRequest(isA(OFMeterConfigStatsRequest.class)))
                .andStubReturn(ofStatsFuture);
    }

    private void mockGetGroupsRequest(List<Integer> groupIds) throws Exception {
        List<OFGroupDescStatsEntry> meterConfigs = new ArrayList<>(groupIds.size());
        for (Integer groupId : groupIds) {
            OFBucket firstBucket = mock(OFBucket.class);
            OFBucket secondBucket = mock(OFBucket.class);

            OFActionSetField setDestMacAction = ofFactory.actions()
                    .buildSetField()
                    .setField(ofFactory.oxms()
                            .buildEthDst()
                            .setValue(switchManager.dpIdToMac(dpid))
                            .build())
                    .build();

            OFActionOutput sendToControllerAction = ofFactory.actions().output(OFPort.CONTROLLER, 0xFFFFFFFF);
            TransportPort udpPort = TransportPort.of(LATENCY_PACKET_UDP_PORT);
            OFActionSetField setUdpDstAction = ofFactory.actions().setField(ofFactory.oxms().udpDst(udpPort));
            OFActionOutput sendInPortAction = ofFactory.actions().output(OFPort.IN_PORT, 0xFFFFFFFF);

            expect(firstBucket.getActions()).andStubReturn(
                    Lists.newArrayList(setDestMacAction, sendToControllerAction));
            expect(secondBucket.getActions()).andStubReturn(
                    Lists.newArrayList(setUdpDstAction, sendInPortAction));

            OFGroupDescStatsEntry groupEntry = mock(OFGroupDescStatsEntry.class);
            expect(groupEntry.getGroup()).andStubReturn(OFGroup.of(groupId));
            expect(groupEntry.getBuckets()).andStubReturn(Lists.newArrayList(firstBucket, secondBucket));

            replay(firstBucket, secondBucket, groupEntry);
            meterConfigs.add(groupEntry);
        }

        OFGroupDescStatsReply statsReply = mock(OFGroupDescStatsReply.class);
        expect(statsReply.getEntries()).andStubReturn(meterConfigs);

        ListenableFuture<List<OFGroupDescStatsReply>> ofStatsFuture = mock(ListenableFuture.class);
        expect(ofStatsFuture.get(anyLong(), anyObject())).andStubReturn(Collections.singletonList(statsReply));

        expect(iofSwitch.writeStatsRequest(isA(OFGroupDescStatsRequest.class)))
                .andStubReturn(ofStatsFuture);
        replay(statsReply, ofStatsFuture);
    }
}
