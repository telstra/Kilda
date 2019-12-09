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

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.openkilda.floodlight.pathverification.PathVerificationService.DISCOVERY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.pathverification.PathVerificationService.LATENCY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.pathverification.PathVerificationService.ROUND_TRIP_LATENCY_T1_OFFSET;
import static org.openkilda.floodlight.pathverification.PathVerificationService.ROUND_TRIP_LATENCY_TIMESTAMP_SIZE;
import static org.openkilda.messaging.Utils.ETH_TYPE;
import static org.openkilda.messaging.command.flow.RuleType.POST_INGRESS;
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
import static org.openkilda.model.Cookie.isDefaultRule;
import static org.openkilda.model.Metadata.METADATA_LLDP_MASK;
import static org.openkilda.model.Metadata.METADATA_LLDP_VALUE;
import static org.openkilda.model.MeterId.MIN_FLOW_METER_ID;
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule;
import static org.openkilda.model.SwitchFeature.MATCH_UDP_PORT;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_12;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_13;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_15;

import org.openkilda.floodlight.KildaCore;
import org.openkilda.floodlight.config.provider.FloodlightModuleConfigurationProvider;
import org.openkilda.floodlight.converter.OfPortDescConverter;
import org.openkilda.floodlight.error.InvalidMeterIdException;
import org.openkilda.floodlight.error.OfInstallException;
import org.openkilda.floodlight.error.SwitchNotFoundException;
import org.openkilda.floodlight.error.SwitchOperationException;
import org.openkilda.floodlight.error.UnsupportedSwitchOperationException;
import org.openkilda.floodlight.pathverification.IPathVerificationService;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.switchmanager.web.SwitchManagerWebRoutable;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.floodlight.utils.NewCorrelationContextRequired;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.command.flow.RuleType;
import org.openkilda.messaging.command.switches.ConnectModeRequest;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Cookie;
import org.openkilda.model.FlowApplication;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.Metadata;
import org.openkilda.model.Meter;
import org.openkilda.model.MeterId;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.SwitchFeature;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.util.FlowModUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.projectfloodlight.openflow.protocol.OFActionType;
import org.projectfloodlight.openflow.protocol.OFBarrierReply;
import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFBucket;
import org.projectfloodlight.openflow.protocol.OFErrorMsg;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsRequest;
import org.projectfloodlight.openflow.protocol.OFGroupAdd;
import org.projectfloodlight.openflow.protocol.OFGroupDelete;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsEntry;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsReply;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsRequest;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFMeterConfig;
import org.projectfloodlight.openflow.protocol.OFMeterConfigStatsReply;
import org.projectfloodlight.openflow.protocol.OFMeterConfigStatsRequest;
import org.projectfloodlight.openflow.protocol.OFMeterFlags;
import org.projectfloodlight.openflow.protocol.OFMeterMod;
import org.projectfloodlight.openflow.protocol.OFMeterModCommand;
import org.projectfloodlight.openflow.protocol.OFPortConfig;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFPortMod;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionGotoTable;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionMeter;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionWriteMetadata;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.Match.Builder;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.meterband.OFMeterBandDrop;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by jonv on 29/3/17.
 */
public class SwitchManager implements IFloodlightModule, IFloodlightService, ISwitchManager, IOFMessageListener {
    private static final Logger logger = LoggerFactory.getLogger(SwitchManager.class);

    /**
     * Make sure we clear the top bit .. that is for NON_SYSTEM_MASK. This mask is applied to
     * Cookie IDs when creating a flow.
     */
    public static final long FLOW_COOKIE_MASK = 0x7FFFFFFFFFFFFFFFL;

    public static final int VERIFICATION_RULE_PRIORITY = FlowModUtils.PRIORITY_MAX - 1000;
    public static final int VERIFICATION_RULE_VXLAN_PRIORITY = VERIFICATION_RULE_PRIORITY + 1;
    public static final int DROP_VERIFICATION_LOOP_RULE_PRIORITY = VERIFICATION_RULE_PRIORITY + 1;
    public static final int CATCH_BFD_RULE_PRIORITY = DROP_VERIFICATION_LOOP_RULE_PRIORITY + 1;
    public static final int ROUND_TRIP_LATENCY_RULE_PRIORITY = DROP_VERIFICATION_LOOP_RULE_PRIORITY + 1;
    public static final int FLOW_PRIORITY = FlowModUtils.PRIORITY_HIGH;
    public static final int ISL_EGRESS_VXLAN_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 2;
    public static final int ISL_TRANSIT_VXLAN_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 3;
    public static final int INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 2;
    public static final int ISL_EGRESS_VLAN_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 5;
    public static final int DEFAULT_FLOW_PRIORITY = FLOW_PRIORITY - 1;
    public static final int MINIMAL_POSITIVE_PRIORITY = FlowModUtils.PRIORITY_MIN + 1;

    public static final int LLDP_INPUT_PRE_DROP_PRIORITY = MINIMAL_POSITIVE_PRIORITY + 1;
    public static final int LLDP_TRANSIT_ISL_PRIORITY = FLOW_PRIORITY - 1;
    public static final int LLDP_INPUT_CUSTOMER_PRIORITY = FLOW_PRIORITY - 1;
    public static final int LLDP_INGRESS_PRIORITY = MINIMAL_POSITIVE_PRIORITY + 1;
    public static final int LLDP_POST_INGRESS_PRIORITY = FLOW_PRIORITY - 2;
    public static final int LLDP_POST_INGRESS_VXLAN_PRIORITY = FLOW_PRIORITY - 1;
    public static final int LLDP_POST_INGRESS_ONE_SWITCH_PRIORITY = FLOW_PRIORITY;

    public static final int BDF_DEFAULT_PORT = 3784;
    public static final int ROUND_TRIP_LATENCY_GROUP_ID = 1;
    public static final MacAddress STUB_VXLAN_ETH_DST_MAC = MacAddress.of(0xFFFFFFEDCBA2L);
    public static final IPv4Address STUB_VXLAN_IPV4_SRC = IPv4Address.of("127.0.0.1");
    public static final IPv4Address STUB_VXLAN_IPV4_DST = IPv4Address.of("127.0.0.2");
    public static final int STUB_VXLAN_UDP_SRC = 4500;
    public static final int VXLAN_UDP_DST = 4789;
    public static final int ETH_SRC_OFFSET = 48;
    public static final int INTERNAL_ETH_SRC_OFFSET = 448;
    public static final int MAC_ADDRESS_SIZE_IN_BITS = 48;
    public static final String LLDP_MAC = "01:80:c2:00:00:0e";
    public static final int TABLE_1 = 1;


    public static final int INPUT_TABLE_ID = 0;
    public static final int PRE_INGRESS_TABLE_ID = 1;
    public static final int INGRESS_TABLE_ID = 2;
    public static final int POST_INGRESS_TABLE_ID = 3;
    public static final int EGRESS_TABLE_ID = 4;
    public static final int TRANSIT_TABLE_ID = 5;
    public static final int APPLICATIONS_TABLE_ID = 6;

    public static long METADATA_MASK = 0x1FFFFFL;

    // This is invalid VID mask - it cut of highest bit that indicate presence of VLAN tag on package. But valid mask
    // 0x1FFF lead to rule reject during install attempt on accton based switches.
    private static short OF10_VLAN_MASK = 0x0FFF;

    private IOFSwitchService ofSwitchService;
    private IKafkaProducerService producerService;
    private SwitchTrackingService switchTracking;
    private FeatureDetectorService featureDetectorService;

    private ConnectModeRequest.Mode connectMode;
    private SwitchManagerConfig config;
    private KildaCore kildaCore;

    private String verificationBcastPacketDst;

    /**
     * Create an OFInstructionApplyActions which applies actions.
     *
     * @param ofFactory OF factory for the switch
     * @param actionList OFAction list to apply
     * @return {@link OFInstructionApplyActions}
     */
    private static OFInstructionApplyActions buildInstructionApplyActions(OFFactory ofFactory,
                                                                          List<OFAction> actionList) {
        return ofFactory.instructions().applyActions(actionList).createBuilder().build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableList.of(
                ISwitchManager.class,
                SwitchTrackingService.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.<Class<? extends IFloodlightService>, IFloodlightService>builder()
                .put(ISwitchManager.class, this)
                .put(SwitchTrackingService.class, new SwitchTrackingService())
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return ImmutableList.of(
                IFloodlightProviderService.class,
                IOFSwitchService.class,
                IRestApiService.class,
                KildaCore.class,
                KafkaUtilityService.class,
                IKafkaProducerService.class,
                FeatureDetectorService.class,
                IPathVerificationService.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        ofSwitchService = context.getServiceImpl(IOFSwitchService.class);
        producerService = context.getServiceImpl(IKafkaProducerService.class);
        switchTracking = context.getServiceImpl(SwitchTrackingService.class);
        featureDetectorService = context.getServiceImpl(FeatureDetectorService.class);
        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(context, this);
        config = provider.getConfiguration(SwitchManagerConfig.class);
        String connectModeProperty = config.getConnectMode();

        verificationBcastPacketDst =
                context.getServiceImpl(IPathVerificationService.class).getConfig().getVerificationBcastPacketDst();

        try {
            connectMode = ConnectModeRequest.Mode.valueOf(connectModeProperty);
        } catch (Exception e) {
            logger.error("CONFIG EXCEPTION: connect-mode could not be set to {}, defaulting to AUTO",
                    connectModeProperty);
            connectMode = ConnectModeRequest.Mode.AUTO;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
        logger.info("Module {} - start up", SwitchTrackingService.class.getName());

        context.getServiceImpl(SwitchTrackingService.class).setup(context);

        context.getServiceImpl(IFloodlightProviderService.class).addOFMessageListener(OFType.ERROR, this);
        context.getServiceImpl(IRestApiService.class).addRestletRoutable(new SwitchManagerWebRoutable());

        kildaCore = context.getServiceImpl(KildaCore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NewCorrelationContextRequired
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        logger.debug("OF_ERROR: {}", msg);
        // TODO: track xid for flow id
        if (OFType.ERROR.equals(msg.getType())) {
            ErrorMessage error = new ErrorMessage(
                    new ErrorData(ErrorType.INTERNAL_ERROR, ((OFErrorMsg) msg).getErrType().toString(), null),
                    System.currentTimeMillis(), CorrelationContext.getId(), Destination.WFM_TRANSACTION);
            // TODO: Most/all commands are flow related, but not all. 'kilda.flow' might
            // not be the best place to send a generic error.
            producerService.sendMessageAndTrack("kilda.flow", error);
        }
        return Command.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "KildaSwitchManager";
    }

    // ISwitchManager Methods

    @Override
    public void activate(DatapathId dpid) throws SwitchOperationException {
        if (connectMode == ConnectModeRequest.Mode.SAFE) {
            // the bulk of work below is done as part of the safe protocol
            startSafeMode(dpid);
            return;
        }

        if (connectMode == ConnectModeRequest.Mode.AUTO) {
            installDefaultRules(dpid);
        }
        switchTracking.completeSwitchActivation(dpid);
    }

    @Override
    public void deactivate(DatapathId dpid) {
        stopSafeMode(dpid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        logger.trace("isCallbackOrderingPrereq for {} : {}", type, name);
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        logger.trace("isCallbackOrderingPostreq for {} : {}", type, name);
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConnectModeRequest.Mode connectMode(final ConnectModeRequest.Mode mode) {
        if (mode != null) {
            this.connectMode = mode;
        }
        return this.connectMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void installDefaultRules(final DatapathId dpid) throws SwitchOperationException {
        installDropFlow(dpid);
        installVerificationRule(dpid, true);
        installVerificationRule(dpid, false);
        installDropLoopRule(dpid);
        installBfdCatchFlow(dpid);
        installRoundTripLatencyFlow(dpid);
        installUnicastVerificationRuleVxlan(dpid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long installIngressFlow(DatapathId dpid, DatapathId dstDpid, String flowId, Long cookie, int inputPort,
                                   int outputPort, int inputVlanId, int transitTunnelId, OutputVlanType outputVlanType,
                                   long meterId, FlowEncapsulationType encapsulationType,
                                   boolean multiTable, Set<FlowApplication> applications, Metadata appMetadata)
            throws SwitchOperationException {
        List<OFAction> actionList = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        // build meter instruction
        OFInstructionMeter meter = buildMeterInstruction(meterId, sw, actionList);

        // output action based on encap scheme
        actionList.addAll(inputVlanTypeToOfActionList(ofFactory, transitTunnelId, outputVlanType,
                encapsulationType, dpid, dstDpid));
        if (applications == null || applications.isEmpty()) {
            // transmit packet from outgoing port
            actionList.add(actionSetOutputPort(ofFactory, OFPort.of(outputPort)));
        } else {
            actionList.add(ofFactory.actions().group(installIngressFlowGroup(sw, cookie, transitTunnelId,
                    outputVlanType, encapsulationType, dstDpid, outputPort)));
        }


        // build instruction with action list
        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);

        // build match by input port and input vlan id, it's always transit vlan type, since kilda doesn't allow
        // other flow endpoints
        Match match = matchFlow(ofFactory, inputPort, inputVlanId, FlowEncapsulationType.TRANSIT_VLAN, null);

        int flowPriority = getFlowPriority(inputVlanId);

        List<OFInstruction> instructions = createIngressFlowInstructions(ofFactory, meter, actions,
                multiTable, appMetadata, applications);

        // build FLOW_MOD command with meter
        OFFlowMod.Builder builder = prepareFlowModBuilder(ofFactory, cookie & FLOW_COOKIE_MASK, flowPriority,
                multiTable ? INGRESS_TABLE_ID : INPUT_TABLE_ID)
                .setInstructions(instructions)
                .setMatch(match);

        // centec switches don't support RESET_COUNTS flag
        if (featureDetectorService.detectSwitch(sw).contains(SwitchFeature.RESET_COUNTS_FLAG)) {
            builder.setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS));
        }

        return pushFlow(sw, "--InstallIngressFlow--", builder.build());
    }

    OFGroupAdd getInstallIngressRuleGroupInstruction(IOFSwitch sw, int groupId, int transitTunnelId,
                                                     OutputVlanType outputVlanType,
                                                     FlowEncapsulationType encapsulationType,
                                                     DatapathId dstDpid, int outputPort) {
        OFFactory ofFactory = sw.getOFFactory();
        List<OFBucket> bucketList = new ArrayList<>();
        List<OFAction> actionList = new ArrayList<>();
        actionList.addAll(inputVlanTypeToOfActionList(ofFactory, transitTunnelId, outputVlanType,
                encapsulationType, sw.getId(), dstDpid));
        actionList.add(actionSetOutputPort(ofFactory, OFPort.of(outputPort)));
        bucketList.add(ofFactory
                .buildBucket()
                .setWatchGroup(OFGroup.ANY)
                .setActions(actionList)
                .build());

        return ofFactory.buildGroupAdd()
                .setGroup(OFGroup.of(groupId))
                .setGroupType(OFGroupType.INDIRECT)
                .setBuckets(bucketList)
                .build();
    }

    private OFGroup installIngressFlowGroup(IOFSwitch sw, long cookie, int transitTunnelId,
                                            OutputVlanType outputVlanType,
                                            FlowEncapsulationType encapsulationType,
                                            DatapathId dstDpid, int outPort) throws OfInstallException {
        int groupId = new Cookie(cookie).getShortValue();
        OFGroupAdd groupAdd = getInstallIngressRuleGroupInstruction(sw, groupId, transitTunnelId, outputVlanType,
                encapsulationType, dstDpid, outPort);
        pushFlow(sw, "--InstallGroup--", groupAdd);
        sendBarrierRequest(sw);
        return OFGroup.of(groupId);
    }


    private List<OFInstruction> createIngressFlowInstructions(
            OFFactory ofFactory, OFInstructionMeter meter, OFInstructionApplyActions actions,
            boolean multiTable, Metadata metadata, Set<FlowApplication> applications) {
        List<OFInstruction> instructions = new ArrayList<>();

        if (meter != null) {
            instructions.add(meter);
        }

        instructions.add(actions);

        if (multiTable) {
            instructions.addAll(createMetadataInstructions(ofFactory, metadata, applications));
            if (applications == null || applications.isEmpty()) {
                instructions.add(ofFactory.instructions().gotoTable(TableId.of(POST_INGRESS_TABLE_ID)));
            } else {
                instructions.add(ofFactory.instructions().gotoTable(TableId.of(APPLICATIONS_TABLE_ID)));
            }


        }
        return instructions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long installTelescopeFlow(DatapathId dpid, long cookie, Metadata metadata, int telescopePort,
                                     Integer telescopeVlan, FlowEncapsulationType flowEncapsulationType,
                                     long transitTunnelId, DatapathId srcDpid,
                                     DatapathId dstDpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        if (sw.getOFFactory().getVersion() == OF_12) {
            return null;
        }
        List<OFAction> actions = Lists.newArrayList(actionSetOutputPort(ofFactory, OFPort.of(telescopePort)));
        actions.addAll(inputVlanTypeToOfActionList(ofFactory, (int) transitTunnelId, OutputVlanType.NONE,
                flowEncapsulationType, srcDpid, dstDpid));
        if (telescopeVlan != null) {
            actions.add(actionPushVlan(ofFactory, ETH_TYPE));
            actions.add(actionReplaceVlan(ofFactory, telescopeVlan));
        }
        OFFlowMod flowMod = prepareFlowModBuilder(ofFactory, cookie, 2, APPLICATIONS_TABLE_ID)
                .setMatch(getMetadataMatchBuilder(sw, metadata.getRawValue()).build())
                .setActions(actions)
                .build();

        return pushFlow(sw, "--InstallTelescopeFlow--", flowMod);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long removeTelescopeFlow(DatapathId dpid, long cookie, Metadata metadata) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        if (sw.getOFFactory().getVersion() == OF_12) {
            return null;
        }

        OFFlowDelete flowDelete = sw.getOFFactory().buildFlowDelete()
                .setCookie(U64.of(cookie))
                .setCookieMask(U64.NO_MASK)
                .setTableId(TableId.of(APPLICATIONS_TABLE_ID))
                .setMatch(getMetadataMatchBuilder(sw, metadata.getRawValue()).build())
                .build();

        return pushFlow(sw, "--RemoveTelescopeFlow--", flowDelete);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public long installEgressFlow(DatapathId dpid, String flowId, Long cookie, int inputPort, int outputPort,
                                  int transitTunnelId, int outputVlanId, OutputVlanType outputVlanType,
                                  FlowEncapsulationType encapsulationType,
                                  boolean multiTable, Set<FlowApplication> applications, Metadata appMetadata)
            throws SwitchOperationException {
        List<OFAction> actionList = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        // output action based on encap scheme
        actionList.addAll(egressFlowActions(ofFactory, outputVlanId, outputVlanType, encapsulationType));

        // transmit packet from outgoing port
        actionList.add(actionSetOutputPort(ofFactory, OFPort.of(outputPort)));

        // build instruction with action list
        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);

        // build FLOW_MOD command, no meter
        OFFlowMod flowMod = prepareFlowModBuilder(ofFactory, cookie & FLOW_COOKIE_MASK, FLOW_PRIORITY,
                multiTable ? EGRESS_TABLE_ID : INPUT_TABLE_ID)
                .setMatch(matchFlow(ofFactory, inputPort, transitTunnelId, encapsulationType, dpid))
                .setInstructions(createEgressFlowInstructions(ofFactory, actions, appMetadata,
                        applications))
                .build();

        return pushFlow(sw, "--InstallEgressFlow--", flowMod);
    }

    private List<OFInstruction> createEgressFlowInstructions(
            OFFactory ofFactory, OFInstructionApplyActions actions, Metadata metadata,
            Set<FlowApplication> applications) {
        List<OFInstruction> instructions = new ArrayList<>();

        instructions.add(actions);
        if (applications != null && !applications.isEmpty()) {
            instructions.add(ofFactory.instructions().gotoTable(TableId.of(APPLICATIONS_TABLE_ID)));
            instructions.addAll(createMetadataInstructions(ofFactory, metadata, applications));
        }

        return instructions;
    }

    private List<OFInstruction> createMetadataInstructions(OFFactory ofFactory, Metadata metadata,
                                                              Set<FlowApplication> applications) {
        List<OFInstruction> instructions = new ArrayList<>();
        if (metadata != null && metadata.getRawValue() != 0L) {
            instructions.add(ofFactory.instructions().buildWriteMetadata()
                    .setMetadata(U64.of(metadata.getRawValue()))
                    .setMetadataMask(U64.of(metadata.getMask())).build());
        }
        return instructions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long installTransitFlow(DatapathId dpid, String flowId, Long cookie, int inputPort, int outputPort,
                                   int transitTunnelId, FlowEncapsulationType encapsulationType, boolean multiTable)
            throws SwitchOperationException {
        List<OFAction> actionList = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        // build match by input port and transit vlan id
        Match match = matchFlow(ofFactory, inputPort, transitTunnelId, encapsulationType, null);

        // transmit packet from outgoing port
        actionList.add(actionSetOutputPort(ofFactory, OFPort.of(outputPort)));

        // build instruction with action list
        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);

        // build FLOW_MOD command, no meter
        OFFlowMod flowMod = prepareFlowModBuilder(ofFactory, cookie & FLOW_COOKIE_MASK, FLOW_PRIORITY,
                multiTable ? TRANSIT_TABLE_ID : INPUT_TABLE_ID)
                .setInstructions(ImmutableList.of(actions))
                .setMatch(match)
                .build();

        return pushFlow(sw, flowId, flowMod);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long installOneSwitchFlow(DatapathId dpid, String flowId, Long cookie, int inputPort, int outputPort,
                                     int inputVlanId, int outputVlanId, OutputVlanType outputVlanType, long meterId,
                                     boolean multiTable, Set<FlowApplication> applications,
                                     Metadata appMetadata)
            throws SwitchOperationException {
        // TODO: As per other locations, how different is this to IngressFlow? Why separate code path?
        //          As with any set of tests, the more we test the same code path, the better.
        //          Based on brief glance, this looks 90% the same as IngressFlow.

        List<OFAction> actionList = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        // output action based on encap scheme
        actionList.addAll(pushSchemeOutputVlanTypeToOfActionList(ofFactory, outputVlanId, outputVlanType));
        // transmit packet from outgoing port
        OFPort ofOutputPort = outputPort == inputPort ? OFPort.IN_PORT : OFPort.of(outputPort);
        actionList.add(actionSetOutputPort(ofFactory, ofOutputPort));

        // build instruction with action list
        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);

        // build match by input port and transit vlan id
        Match match = matchFlow(ofFactory, inputPort, inputVlanId, FlowEncapsulationType.TRANSIT_VLAN, null);

        int flowPriority = getFlowPriority(inputVlanId);

        // build meter instruction
        OFInstructionMeter meter = buildMeterInstruction(meterId, sw, actionList);

        appMetadata.setOneSwitchFlow(true);
        List<OFInstruction> instructions = createIngressFlowInstructions(ofFactory, meter, actions,
                multiTable, appMetadata, applications);

        // build FLOW_MOD command with meter
        OFFlowMod.Builder builder = prepareFlowModBuilder(ofFactory, cookie & FLOW_COOKIE_MASK, flowPriority,
                multiTable ? INGRESS_TABLE_ID : INPUT_TABLE_ID)
                .setInstructions(instructions)
                .setMatch(match);

        // centec switches don't support RESET_COUNTS flag
        if (featureDetectorService.detectSwitch(sw).contains(SwitchFeature.RESET_COUNTS_FLAG)) {
            builder.setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS));
        }

        return pushFlow(sw, flowId, builder.build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OFFlowMod> getExpectedDefaultFlows(DatapathId dpid, boolean multiTable, boolean switchLldp)
            throws SwitchOperationException {
        List<OFFlowMod> flows = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        Optional.ofNullable(buildDropFlow(sw, INPUT_TABLE_ID, DROP_RULE_COOKIE)).ifPresent(flows::add);
        if (multiTable) {
            Optional.ofNullable(buildDropFlow(sw, INGRESS_TABLE_ID, MULTITABLE_INGRESS_DROP_COOKIE))
                    .ifPresent(flows::add);
            Optional.ofNullable(buildDropFlow(sw, TRANSIT_TABLE_ID, MULTITABLE_TRANSIT_DROP_COOKIE))
                    .ifPresent(flows::add);
            Optional.ofNullable(buildDropFlow(sw, POST_INGRESS_TABLE_ID, MULTITABLE_POST_INGRESS_DROP_COOKIE))
                    .ifPresent(flows::add);
            Optional.ofNullable(buildTablePassThroughDefaultRule(ofFactory, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                    TRANSIT_TABLE_ID, EGRESS_TABLE_ID)).ifPresent((flows::add));
            Optional.ofNullable(buildTablePassThroughDefaultRule(ofFactory, MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE,
                    INGRESS_TABLE_ID, PRE_INGRESS_TABLE_ID)).ifPresent((flows::add));
            addLldpPostIngressFlow(flows, sw);
            addLldpPostIngressVxlanFlow(flows, sw);
            addLldpPostIngressOneSwitchFlow(flows, sw);

            if (switchLldp) {
                addLldpTransitFlow(flows, sw);
                addLldpInputPreDropFlow(flows, sw);
                addLldpIngressFlow(flows, sw);
            }
        }
        ArrayList<OFAction> actionListBroadcastRule = prepareActionListForBroadcastRule(sw);
        OFInstructionMeter meterBroadcastRule = buildMeterInstructionForBroadcastRule(sw, actionListBroadcastRule);
        Optional.ofNullable(buildVerificationRule(sw, true, ofFactory, VERIFICATION_BROADCAST_RULE_COOKIE,
                meterBroadcastRule, actionListBroadcastRule)).ifPresent(flows::add);

        ArrayList<OFAction> actionListUnicastRule = prepareActionListForUnicastRule(sw);
        OFInstructionMeter meterUnicastRule = buildMeterInstructionForUnicastRule(sw, actionListUnicastRule);
        Optional.ofNullable(buildVerificationRule(sw, false, ofFactory, VERIFICATION_UNICAST_RULE_COOKIE,
                meterUnicastRule, actionListUnicastRule)).ifPresent(flows::add);

        Optional.ofNullable(buildDropLoopRule(sw)).ifPresent(flows::add);
        Optional.ofNullable(buildBfdCatchFlow(sw)).ifPresent(flows::add);
        Optional.ofNullable(buildRoundTripLatencyFlow(sw)).ifPresent(flows::add);

        ArrayList<OFAction> actionListUnicastVxlanRule = new ArrayList<>();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            OFInstructionMeter meter = buildMeterInstructionForUnicastVxlanRule(sw, actionListUnicastVxlanRule);
            Optional.ofNullable(buildUnicastVerificationRuleVxlan(sw, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                    meter, actionListUnicastVxlanRule)).ifPresent(flows::add);
        }
        return flows;
    }

    private void addLldpTransitFlow(List<OFFlowMod> flows, IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = buildMeterInstructionForLldpTransitFlow(sw, actionList);
        Optional.ofNullable(buildLldpTransitFlow(sw, meter, actionList)).ifPresent((flows::add));
    }

    private void addLldpInputPreDropFlow(List<OFFlowMod> flows, IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = buildMeterInstructionForLldpIngressPreDropFlow(sw, actionList);
        Optional.ofNullable(buildLldpInputPreDropRule(sw, meter, actionList)).ifPresent((flows::add));
    }

    private void addLldpIngressFlow(List<OFFlowMod> flows, IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = buildMeterInstructionForLldpIngressFlow(sw, actionList);
        Optional.ofNullable(buildLldpIngressFlow(sw, meter, actionList)).ifPresent((flows::add));
    }

    private void addLldpPostIngressFlow(List<OFFlowMod> flows, IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = buildMeterInstructionForLldpPostIngressFlow(sw, actionList);
        Optional.ofNullable(buildLldpPostIngressFlow(sw, meter, actionList)).ifPresent((flows::add));
    }

    private void addLldpPostIngressVxlanFlow(List<OFFlowMod> flows, IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = buildMeterInstructionForLldpPostIngressVxlanFlow(sw, actionList);
        Optional.ofNullable(buildLldpPostIngressVxlanFlow(sw, meter, actionList)).ifPresent((flows::add));
    }

    private void addLldpPostIngressOneSwitchFlow(List<OFFlowMod> flows, IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = buildMeterInstructionForLldpPostIngressOneSwitchFlow(sw, actionList);
        Optional.ofNullable(buildLldpPostIngressOneSwitchFlow(sw, meter, actionList)).ifPresent((flows::add));
    }

    @Override
    public List<OFFlowMod> getExpectedIslFlowsForPort(DatapathId dpid, int port) throws SwitchOperationException {
        List<OFFlowMod> flows = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            flows.add(buildEgressIslVxlanRule(ofFactory, dpid, port));
            flows.add(buildTransitIslVxlanRule(ofFactory, dpid, port));
        }
        flows.add(buildEgressIslVlanRule(ofFactory, dpid, port));
        return flows;
    }

    private ArrayList<OFAction> prepareActionListForBroadcastRule(IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        if (featureDetectorService.detectSwitch(sw).contains(SwitchFeature.GROUP_PACKET_OUT_CONTROLLER)) {
            actionList.add(sw.getOFFactory().actions().group(OFGroup.of(ROUND_TRIP_LATENCY_GROUP_ID)));
        } else {
            addStandardDiscoveryActions(sw, actionList);
        }
        return actionList;
    }

    private OFInstructionMeter buildMeterInstructionForBroadcastRule(IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(createMeterIdForDefaultRule(VERIFICATION_BROADCAST_RULE_COOKIE).getValue(),
                sw, actionList);
    }

    private ArrayList<OFAction> prepareActionListForUnicastRule(IOFSwitch sw) {
        ArrayList<OFAction> actionList = new ArrayList<>();
        addStandardDiscoveryActions(sw, actionList);
        return actionList;
    }

    private OFInstructionMeter buildMeterInstructionForUnicastRule(IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(createMeterIdForDefaultRule(VERIFICATION_UNICAST_RULE_COOKIE).getValue(),
                sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForUnicastVxlanRule(IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(createMeterIdForDefaultRule(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue(),
                sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForLldpTransitFlow(IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(
                createMeterIdForDefaultRule(Cookie.LLDP_TRANSIT_COOKIE).getValue(), sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForLldpIngressPreDropFlow(
            IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(
                createMeterIdForDefaultRule(Cookie.LLDP_INPUT_PRE_DROP_COOKIE).getValue(), sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForLldpIngressFlow(
            IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(
                createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE).getValue(), sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForLldpPostIngressFlow(
            IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(
                createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).getValue(), sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForLldpPostIngressVxlanFlow(
            IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(
                createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE).getValue(), sw, actionList);
    }

    private OFInstructionMeter buildMeterInstructionForLldpPostIngressOneSwitchFlow(
            IOFSwitch sw, ArrayList<OFAction> actionList) {
        return buildMeterInstruction(
                createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue(), sw, actionList);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OFFlowStatsEntry> dumpFlowTable(final DatapathId dpid) throws SwitchNotFoundException {
        List<OFFlowStatsEntry> entries = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);

        OFFactory ofFactory = sw.getOFFactory();
        OFFlowStatsRequest flowRequest = ofFactory.buildFlowStatsRequest()
                .setOutGroup(OFGroup.ANY)
                .setCookieMask(U64.ZERO)
                .build();

        try {
            Future<List<OFFlowStatsReply>> future = sw.writeStatsRequest(flowRequest);
            List<OFFlowStatsReply> values = future.get(10, TimeUnit.SECONDS);
            if (values != null) {
                entries = values.stream()
                        .map(OFFlowStatsReply::getEntries)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
            }
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not get flow stats for {}.", dpid, e);
            throw new SwitchNotFoundException(dpid);
        } catch (InterruptedException e) {
            logger.error("Could not get flow stats for {}.", dpid, e);
            Thread.currentThread().interrupt();
            throw new SwitchNotFoundException(dpid);
        }

        return entries;
    }

    private List<OFFlowStatsEntry> dumpFlowTable(final DatapathId dpid, final int tableId)
            throws SwitchNotFoundException {
        List<OFFlowStatsEntry> entries = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);

        OFFactory ofFactory = sw.getOFFactory();
        OFFlowStatsRequest flowRequest = ofFactory.buildFlowStatsRequest()
                .setOutGroup(OFGroup.ANY)
                .setCookieMask(U64.ZERO)
                .setTableId(TableId.of(tableId))
                .build();

        try {
            Future<List<OFFlowStatsReply>> future = sw.writeStatsRequest(flowRequest);
            List<OFFlowStatsReply> values = future.get(10, TimeUnit.SECONDS);
            if (values != null) {
                entries = values.stream()
                        .map(OFFlowStatsReply::getEntries)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
            }
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not get flow stats for {}.", dpid, e);
            throw new SwitchNotFoundException(dpid);
        } catch (InterruptedException e) {
            logger.error("Could not get flow stats for {}.", dpid, e);
            Thread.currentThread().interrupt();
            throw new SwitchNotFoundException(dpid);
        }

        return entries;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OFMeterConfig> dumpMeters(final DatapathId dpid) throws SwitchOperationException {
        List<OFMeterConfig> result = null;
        IOFSwitch sw = lookupSwitch(dpid);
        if (sw == null) {
            throw new IllegalArgumentException(format("Switch %s was not found", dpid));
        }

        verifySwitchSupportsMeters(sw);

        OFFactory ofFactory = sw.getOFFactory();
        OFMeterConfigStatsRequest meterRequest = ofFactory.buildMeterConfigStatsRequest()
                .setMeterId(0xffffffff)
                .build();

        try {
            ListenableFuture<List<OFMeterConfigStatsReply>> future = sw.writeStatsRequest(meterRequest);
            List<OFMeterConfigStatsReply> values = future.get(10, TimeUnit.SECONDS);
            if (values != null) {
                result = values.stream()
                        .map(OFMeterConfigStatsReply::getEntries)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
            }
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not get meter config stats for {}.", dpid, e);
        } catch (InterruptedException e) {
            logger.error("Could not get meter config stats for {}.", dpid, e);
            Thread.currentThread().interrupt();
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OFMeterConfig dumpMeterById(final DatapathId dpid, final long meterId) throws SwitchOperationException {
        OFMeterConfig meterConfig = null;
        IOFSwitch sw = lookupSwitch(dpid);
        if (sw == null) {
            throw new IllegalArgumentException(format("Switch %s was not found", dpid));
        }

        verifySwitchSupportsMeters(sw);
        OFFactory ofFactory = sw.getOFFactory();
        OFMeterConfigStatsRequest meterRequest = ofFactory.buildMeterConfigStatsRequest()
                .setMeterId(meterId)
                .build();

        try {
            ListenableFuture<List<OFMeterConfigStatsReply>> future = sw.writeStatsRequest(meterRequest);
            List<OFMeterConfigStatsReply> values = future.get(10, TimeUnit.SECONDS);
            if (values != null) {
                List<OFMeterConfig> result = values.stream()
                        .map(OFMeterConfigStatsReply::getEntries)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
                meterConfig = result.size() >= 1 ? result.get(0) : null;
            }
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not get meter config stats for {}.", dpid, e);
        } catch (InterruptedException e) {
            logger.error("Could not get meter config stats for {}.", dpid, e);
            Thread.currentThread().interrupt();
            throw new SwitchNotFoundException(dpid);
        }

        return meterConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void installMeterForFlow(DatapathId dpid, long bandwidth, final long meterId)
            throws SwitchOperationException {
        if (meterId >= MIN_FLOW_METER_ID) {
            IOFSwitch sw = lookupSwitch(dpid);
            verifySwitchSupportsMeters(sw);
            long burstSize = Meter.calculateBurstSize(bandwidth, config.getFlowMeterMinBurstSizeInKbits(),
                    config.getFlowMeterBurstCoefficient(), sw.getSwitchDescription().getManufacturerDescription(),
                    sw.getSwitchDescription().getSoftwareDescription());

            Set<OFMeterFlags> flags = Arrays.stream(Meter.getMeterKbpsFlags())
                    .map(OFMeterFlags::valueOf)
                    .collect(Collectors.toSet());
            installMeter(sw, flags, bandwidth, burstSize, meterId);
        } else {
            throw new InvalidMeterIdException(dpid, format("Meter id must be greater than %d.", MIN_FLOW_METER_ID));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modifyMeterForFlow(DatapathId dpid, long meterId, long bandwidth)
            throws SwitchOperationException {
        if (meterId > 0L) {
            IOFSwitch sw = lookupSwitch(dpid);
            verifySwitchSupportsMeters(sw);

            long burstSize = Meter.calculateBurstSize(bandwidth, config.getFlowMeterMinBurstSizeInKbits(),
                    config.getFlowMeterBurstCoefficient(), sw.getSwitchDescription().getManufacturerDescription(),
                    sw.getSwitchDescription().getSoftwareDescription());

            Set<OFMeterFlags> flags = Arrays.stream(Meter.getMeterKbpsFlags())
                    .map(OFMeterFlags::valueOf)
                    .collect(Collectors.toSet());

            modifyMeter(sw, bandwidth, burstSize, meterId, flags);
        } else {
            String message = meterId <= 0
                    ? "Meter id must be positive." : "Meter IDs from 1 to 31 inclusively are for default rules.";

            throw new InvalidMeterIdException(dpid,
                    format("Could not install meter '%d' onto switch '%s'. %s", meterId, dpid, message));
        }

    }

    @Override
    public Map<DatapathId, IOFSwitch> getAllSwitchMap() {
        return ofSwitchService.getAllSwitchMap();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteMeter(final DatapathId dpid, final long meterId) throws SwitchOperationException {
        if (meterId > 0L) {
            IOFSwitch sw = lookupSwitch(dpid);
            verifySwitchSupportsMeters(sw);
            buildAndDeleteMeter(sw, dpid, meterId);

            // to ensure that we have completed meter deletion, because we might have remove/create meter in a row
            sendBarrierRequest(sw);
        } else {
            throw new InvalidMeterIdException(dpid, "Meter id must be positive.");
        }
    }

    @Override
    public List<Long> deleteAllNonDefaultRules(final DatapathId dpid) throws SwitchOperationException {
        List<OFFlowStatsEntry> flowStatsBefore = dumpFlowTable(dpid);
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        Set<Long> removedRules = new HashSet<>();

        for (OFFlowStatsEntry flowStatsEntry : flowStatsBefore) {
            long flowCookie = flowStatsEntry.getCookie().getValue();
            if (!isDefaultRule(flowCookie)) {
                OFFlowDelete flowDelete = ofFactory.buildFlowDelete()
                        .setCookie(U64.of(flowCookie))
                        .setCookieMask(U64.NO_MASK)
                        .setTableId(TableId.ALL)
                        .build();
                pushFlow(sw, "--DeleteFlow--", flowDelete);

                logger.info("Rule with cookie {} is to be removed from switch {}.", flowCookie, dpid);

                removedRules.add(flowCookie);
            }
        }

        // Wait for OFFlowDelete to be processed.
        sendBarrierRequest(sw);

        List<OFFlowStatsEntry> flowStatsAfter = dumpFlowTable(dpid);
        Set<Long> cookiesAfter = flowStatsAfter.stream()
                .map(entry -> entry.getCookie().getValue())
                .collect(Collectors.toSet());

        flowStatsBefore.stream()
                .map(entry -> entry.getCookie().getValue())
                .filter(cookie -> !cookiesAfter.contains(cookie))
                .filter(cookie -> !removedRules.contains(cookie))
                .forEach(cookie -> {
                    logger.warn("Rule with cookie {} has been removed although not requested. Switch {}.", cookie,
                            dpid);
                    removedRules.add(cookie);
                });

        cookiesAfter.stream()
                .filter(removedRules::contains)
                .forEach(cookie -> {
                    logger.warn("Rule with cookie {} was requested to be removed, but it still remains. Switch {}.",
                            cookie, dpid);
                    removedRules.remove(cookie);
                });

        return new ArrayList<>(removedRules);
    }


    @Override
    public List<Long> deleteRulesByCriteria(DatapathId dpid, boolean multiTable, RuleType ruleType,
                                            DeleteRulesCriteria... criteria)
            throws SwitchOperationException {
        List<OFFlowStatsEntry> flowStatsBefore = dumpFlowTable(dpid);

        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        for (DeleteRulesCriteria criteriaEntry : criteria) {
            OFFlowDelete dropFlowDelete = buildFlowDeleteByCriteria(ofFactory, criteriaEntry, multiTable,
                    ruleType);
            logger.info("Rules by criteria {} are to be removed from switch {}.", criteria, dpid);

            pushFlow(sw, "--DeleteFlow--", dropFlowDelete);
        }

        // Wait for OFFlowDelete to be processed.
        sendBarrierRequest(sw);

        List<OFFlowStatsEntry> flowStatsAfter = dumpFlowTable(dpid);
        Set<Long> cookiesAfter = flowStatsAfter.stream()
                .map(entry -> entry.getCookie().getValue())
                .collect(Collectors.toSet());

        return flowStatsBefore.stream()
                .map(entry -> entry.getCookie().getValue())
                .filter(cookie -> !cookiesAfter.contains(cookie))
                .peek(cookie -> logger.info("Rule with cookie {} has been removed from switch {}.", cookie, dpid))
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> deleteDefaultRules(DatapathId dpid, List<Integer> islPorts,
                                         List<Integer> flowPorts, Set<Integer> flowLldpPorts, boolean multiTable,
                                         boolean switchLldp) throws SwitchOperationException {

        List<Long> deletedRules = deleteRulesWithCookie(dpid, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE, CATCH_BFD_RULE_COOKIE,
                ROUND_TRIP_LATENCY_RULE_COOKIE, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE,
                LLDP_INGRESS_COOKIE, LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_ONE_SWITCH_COOKIE);
        if (multiTable) {
            for (int islPort : islPorts) {
                deletedRules.addAll(removeMultitableEndpointIslRules(dpid, islPort));
            }

            for (int flowPort : flowPorts) {
                deletedRules.add(removeIntermediateIngressRule(dpid, flowPort));
            }

            for (int flowLldpPort : flowLldpPorts) {
                deletedRules.add(removeLldpInputCustomerFlow(dpid, flowLldpPort));
            }
        }


        try {
            deleteMeter(dpid, createMeterIdForDefaultRule(VERIFICATION_BROADCAST_RULE_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(VERIFICATION_UNICAST_RULE_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue());

            if (switchLldp) {
                deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_INPUT_PRE_DROP_COOKIE).getValue());
                deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_TRANSIT_COOKIE).getValue());
                deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE).getValue());
            }
        } catch (UnsupportedSwitchOperationException e) {
            logger.info("Skip meters deletion from switch {} due to lack of meters support", dpid);
        }

        try {
            deleteGroup(lookupSwitch(dpid), ROUND_TRIP_LATENCY_GROUP_ID);
        } catch (OfInstallException e) {
            logger.info("Couldn't delete round trip latency group from switch {}. {}", dpid, e.getOfMessage());
        }

        return deletedRules;
    }

    @Override
    public Long installUnicastVerificationRuleVxlan(final DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);

        // NOTE(tdurakov): reusing copy field feature here, since only switches with it supports pop/push vxlan's
        // should be replaced with fair feature detection based on ActionId's during handshake
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            logger.debug("Skip installation of unicast verification vxlan rule for switch {}", dpid);
            return null;
        }

        ArrayList<OFAction> actionList = new ArrayList<>();
        long cookie = VERIFICATION_UNICAST_VXLAN_RULE_COOKIE;
        long meterId = createMeterIdForDefaultRule(cookie).getValue();
        long meterRate = config.getUnicastRateLimit();
        OFInstructionMeter meter = installMeterForDefaultRule(sw, meterId, meterRate, actionList);

        OFFlowMod flowMod = buildUnicastVerificationRuleVxlan(sw, cookie, meter, actionList);
        String flowname = "Unicast Vxlan";
        flowname += "--VerificationFlowVxlan--" + dpid.toString();
        pushFlow(sw, flowname, flowMod);
        return cookie;
    }

    private OFFlowMod buildUnicastVerificationRuleVxlan(IOFSwitch sw, long cookie, OFInstructionMeter meter,
                                                        ArrayList<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        actionList.add(ofFactory.actions().noviflowPopVxlanTunnel());
        actionList.add(actionSendToController(sw));

        actionList.add(actionSetDstMac(sw, dpIdToMac(sw.getId())));
        List<OFInstruction> instructions = new ArrayList<>(2);
        if (meter != null) {
            instructions.add(meter);
        }
        instructions.add(ofFactory.instructions().applyActions(actionList));

        MacAddress srcMac = MacAddress.of(kildaCore.getConfig().getFlowPingMagicSrcMacAddress());
        Builder builder = sw.getOFFactory().buildMatch();
        builder.setMasked(MatchField.ETH_SRC, srcMac, MacAddress.NO_MASK);
        builder.setExact(MatchField.ETH_TYPE, EthType.IPv4);
        builder.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
        builder.setExact(MatchField.UDP_SRC, TransportPort.of(STUB_VXLAN_UDP_SRC));
        return prepareFlowModBuilder(ofFactory, cookie, VERIFICATION_RULE_VXLAN_PRIORITY, INPUT_TABLE_ID)
                .setInstructions(instructions)
                .setMatch(builder.build())
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long installVerificationRule(final DatapathId dpid, final boolean isBroadcast)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);

        ArrayList<OFAction> actionList = new ArrayList<>();
        long cookie = isBroadcast ? VERIFICATION_BROADCAST_RULE_COOKIE : VERIFICATION_UNICAST_RULE_COOKIE;
        long meterId = createMeterIdForDefaultRule(cookie).getValue();
        long meterRate = isBroadcast ? config.getBroadcastRateLimit() : config.getUnicastRateLimit();
        OFInstructionMeter meter = installMeterForDefaultRule(sw, meterId, meterRate, actionList);

        OFFactory ofFactory = sw.getOFFactory();

        if (isBroadcast && featureDetectorService.detectSwitch(sw)
                .contains(SwitchFeature.GROUP_PACKET_OUT_CONTROLLER)) {
            logger.debug("Installing round trip latency group actions on switch {}", dpid);

            try {
                OFGroup group = installRoundTripLatencyGroup(sw);
                actionList.add(ofFactory.actions().group(group));
                logger.debug("Round trip latency group was installed on switch {}", dpid);
            } catch (OfInstallException | UnsupportedOperationException e) {
                String message = String.format(
                        "Couldn't install round trip latency group on switch %s. "
                                + "Standard discovery actions will be installed instead. Error: %s", dpid,
                        e.getMessage());
                logger.warn(message, e);

                addStandardDiscoveryActions(sw, actionList);
            }
        } else {
            addStandardDiscoveryActions(sw, actionList);
        }

        OFFlowMod flowMod = buildVerificationRule(sw, isBroadcast, ofFactory, cookie, meter, actionList);

        if (flowMod == null) {
            logger.debug("Not installing unicast verification match for {}", dpid);
            return cookie;
        } else {
            if (!isBroadcast) {
                logger.debug("Installing unicast verification match for {}", dpid);
            }

            String flowName = (isBroadcast) ? "Broadcast" : "Unicast";
            flowName += "--VerificationFlow--" + dpid.toString();
            pushFlow(sw, flowName, flowMod);
            return cookie;
        }
    }

    private void addStandardDiscoveryActions(IOFSwitch sw, ArrayList<OFAction> actionList) {
        actionList.add(actionSendToController(sw));
        actionList.add(actionSetDstMac(sw, dpIdToMac(sw.getId())));
    }

    private OFFlowMod buildVerificationRule(IOFSwitch sw, boolean isBroadcast, OFFactory ofFactory, long cookie,
                                            OFInstructionMeter meter, ArrayList<OFAction> actionList) {

        if (!isBroadcast && ofFactory.getVersion().compareTo(OF_12) <= 0) {
            return null;
        }

        OFInstructionApplyActions actions = ofFactory.instructions()
                .applyActions(actionList).createBuilder().build();

        Match match = matchVerification(sw, isBroadcast);
        return prepareFlowModBuilder(ofFactory, cookie, VERIFICATION_RULE_PRIORITY, INPUT_TABLE_ID)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .setMatch(match)
                .build();
    }

    private OFGroup installRoundTripLatencyGroup(IOFSwitch sw) throws OfInstallException {
        Optional<OFGroupDescStatsEntry> groupDesc = getGroup(sw, ROUND_TRIP_LATENCY_GROUP_ID);

        if (groupDesc.isPresent()) {
            if (validateRoundTripLatencyGroup(sw.getId(), groupDesc.get())) {
                logger.debug("Skip installation of round trip latency group on switch {}. Group exists.", sw.getId());
                return groupDesc.get().getGroup();
            } else {
                logger.debug("Found invalid round trip latency group on switch {}. Need to be deleted.", sw.getId());
                deleteGroup(sw, ROUND_TRIP_LATENCY_GROUP_ID);
            }
        }

        OFGroupAdd groupAdd = getInstallRoundTripLatencyGroupInstruction(sw);

        pushFlow(sw, "--InstallGroup--", groupAdd);
        sendBarrierRequest(sw);

        return OFGroup.of(ROUND_TRIP_LATENCY_GROUP_ID);
    }

    private void deleteGroup(IOFSwitch sw, int groupId) throws OfInstallException {
        OFGroupDelete groupDelete = sw.getOFFactory().buildGroupDelete()
                .setGroup(OFGroup.of(groupId))
                .setGroupType(OFGroupType.ALL)
                .build();

        pushFlow(sw, "--DeleteGroup--", groupDelete);
        sendBarrierRequest(sw);
    }

    @VisibleForTesting
    OFGroupAdd getInstallRoundTripLatencyGroupInstruction(IOFSwitch sw) {
        OFFactory ofFactory = sw.getOFFactory();
        List<OFBucket> bucketList = new ArrayList<>();
        bucketList.add(ofFactory
                .buildBucket()
                .setActions(Lists.newArrayList(
                        actionSetDstMac(sw, dpIdToMac(sw.getId())),
                        actionSendToController(sw)))
                .setWatchGroup(OFGroup.ANY)
                .build());

        TransportPort udpPort = TransportPort.of(LATENCY_PACKET_UDP_PORT);
        List<OFAction> latencyActions = ImmutableList.of(
                ofFactory.actions().setField(ofFactory.oxms().udpDst(udpPort)),
                actionSetOutputPort(ofFactory, OFPort.IN_PORT));

        bucketList.add(ofFactory
                .buildBucket()
                .setActions(latencyActions)
                .setWatchGroup(OFGroup.ANY)
                .build());

        return ofFactory.buildGroupAdd()
                .setGroup(OFGroup.of(ROUND_TRIP_LATENCY_GROUP_ID))
                .setGroupType(OFGroupType.ALL)
                .setBuckets(bucketList)
                .build();
    }

    @VisibleForTesting
    boolean validateRoundTripLatencyGroup(DatapathId dpId, OFGroupDescStatsEntry groupDesc) {
        return groupDesc.getBuckets().size() == 2
                && validateRoundTripSendToControllerBucket(dpId, groupDesc.getBuckets().get(0))
                && validateRoundTripSendBackBucket(groupDesc.getBuckets().get(1));
    }

    private boolean validateRoundTripSendToControllerBucket(DatapathId dpId, OFBucket bucket) {
        List<OFAction> actions = bucket.getActions();
        return actions.size() == 2
                && actions.get(0).getType() == OFActionType.SET_FIELD       // first action is set Dst mac address
                && dpIdToMac(dpId).equals(((OFActionSetField) actions.get(0)).getField().getValue())
                && actions.get(1).getType() == OFActionType.OUTPUT          // second action is send to controller
                && OFPort.CONTROLLER.equals(((OFActionOutput) actions.get(1)).getPort());
    }

    private boolean validateRoundTripSendBackBucket(OFBucket bucket) {
        List<OFAction> actions = bucket.getActions();
        TransportPort udpPort = TransportPort.of(LATENCY_PACKET_UDP_PORT);

        return actions.size() == 2
                && actions.get(0).getType() == OFActionType.SET_FIELD
                && udpPort.equals(((OFActionSetField) actions.get(0)).getField().getValue())
                && actions.get(1).getType() == OFActionType.OUTPUT
                && OFPort.IN_PORT.equals(((OFActionOutput) actions.get(1)).getPort());
    }

    private List<OFGroupDescStatsEntry> dumpGroups(IOFSwitch sw) {
        OFFactory ofFactory = sw.getOFFactory();
        OFGroupDescStatsRequest groupRequest = ofFactory.buildGroupDescStatsRequest().build();

        List<OFGroupDescStatsReply> replies;

        try {
            ListenableFuture<List<OFGroupDescStatsReply>> future = sw.writeStatsRequest(groupRequest);
            replies = future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not dump groups on switch {}.", sw.getId(), e);
            return Collections.emptyList();
        } catch (InterruptedException e) {
            logger.error("Could not dump groups on switch {}.", sw.getId(), e);
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }

        return replies.stream()
                .map(OFGroupDescStatsReply::getEntries)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    private Optional<OFGroupDescStatsEntry> getGroup(IOFSwitch sw, int groupId) {
        return dumpGroups(sw).stream()
                .filter(groupDesc -> groupDesc.getGroup().getGroupNumber() == groupId)
                .findFirst();
    }

    /**
     * Installs custom drop rule .. ie cookie, priority, match
     *
     * @param dpid datapathId of switch
     * @param dstMac Destination Mac address to match on
     * @param dstMask Destination Mask to match on
     * @param cookie Cookie to use for this rule
     * @param priority Priority of the rule
     * @throws SwitchOperationException switch operation exception
     */
    @Override
    public void installDropFlowCustom(final DatapathId dpid, String dstMac, String dstMask,
                                      final long cookie, final int priority) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        Match match = simpleDstMatch(ofFactory, dstMac, dstMask);
        OFFlowMod flowMod = prepareFlowModBuilder(ofFactory, cookie, priority, INPUT_TABLE_ID)
                .setMatch(match)
                .build();
        String flowName = "--CustomDropRule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
    }

    /**
     * Create an action to place the RxTimestamp in the packet.
     *
     * @param sw Switch object
     * @param offset Offset within packet to copy timstamp at
     * @return {@link OFAction}
     */
    private OFAction actionAddRxTimestamp(final IOFSwitch sw, int offset) {
        OFOxms oxms = sw.getOFFactory().oxms();
        OFActions actions = sw.getOFFactory().actions();
        return actions.buildNoviflowCopyField()
                .setNBits(ROUND_TRIP_LATENCY_TIMESTAMP_SIZE)
                .setSrcOffset(0)
                .setDstOffset(offset)
                .setOxmSrcHeader(oxms.buildNoviflowRxtimestamp().getTypeLen())
                .setOxmDstHeader(oxms.buildNoviflowPacketOffset().getTypeLen())
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long installDropFlow(final DatapathId dpid) throws SwitchOperationException {
        // TODO: leverage installDropFlowCustom
        IOFSwitch sw = lookupSwitch(dpid);

        OFFlowMod flowMod = buildDropFlow(sw, INPUT_TABLE_ID, DROP_RULE_COOKIE);

        if (flowMod == null) {
            logger.debug("Skip installation of drop flow for switch {}", dpid);
        } else {
            logger.debug("Installing drop flow for switch {}", dpid);
            String flowName = "--DropRule--" + dpid.toString();
            pushFlow(sw, flowName, flowMod);
        }
        return DROP_RULE_COOKIE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long installDropFlowForTable(final DatapathId dpid, final int tableId,
                                        final long cookie) throws SwitchOperationException {
        // TODO: leverage installDropFlowCustom
        IOFSwitch sw = lookupSwitch(dpid);

        OFFlowMod flowMod = buildDropFlow(sw, tableId, cookie);

        if (flowMod == null) {
            logger.debug("Skip installation of drop flow for switch {}", dpid);
            return null;
        } else {
            logger.debug("Installing drop flow for switch {}", dpid);
            String flowName = "--DropRule--" + dpid.toString();
            pushFlow(sw, flowName, flowMod);
            return cookie;
        }
    }

    private OFFlowMod buildDropFlow(IOFSwitch sw, int tableId, long cookie) {
        OFFactory ofFactory = sw.getOFFactory();

        if (ofFactory.getVersion() == OF_12) {
            return null;
        }

        return prepareFlowModBuilder(ofFactory, cookie, MINIMAL_POSITIVE_PRIORITY, tableId)
                .build();
    }

    private OFFlowMod buildDropFlowForTable(IOFSwitch sw, int tableId) {
        OFFactory ofFactory = sw.getOFFactory();

        if (ofFactory.getVersion() == OF_12) {
            return null;
        }

        return prepareFlowModBuilder(ofFactory, DROP_RULE_COOKIE, 1, tableId)
                .setTableId(TableId.of(tableId))
                .build();
    }

    private OFFlowMod buildExclusionDropFlow(IOFSwitch sw, int tableId, Long cookie, IPv4Address srcIp,
                                             Integer srcPort, IPv4Address dstIp, Integer dstPort,
                                             IpProtocol proto, EthType ethType,
                                             long metadata, int timeout) {
        OFFactory ofFactory = sw.getOFFactory();
        if (sw.getOFFactory().getVersion() == OF_12) {
            return null;
        }

        return prepareFlowModBuilder(ofFactory, cookie, 10, tableId)
                .setTableId(TableId.of(tableId))
                .setMatch(buildExclusionMatch(sw, srcIp, srcPort, dstIp, dstPort, proto, ethType, metadata))
                .setIdleTimeout(timeout)
                .setFlags(Sets.newHashSet(OFFlowModFlags.SEND_FLOW_REM))
                .build();
    }

    private OFFlowDelete buildExclusionDeleteCommand(IOFSwitch sw, int tableId, Long cookie, IPv4Address srcIp,
                                                     Integer srcPort, IPv4Address dstIp, Integer dstPort,
                                                     IpProtocol proto, EthType ethType, long metadata) {
        if (sw.getOFFactory().getVersion() == OF_12) {
            return null;
        }

        return sw.getOFFactory().buildFlowDelete()
                .setCookie(U64.of(cookie))
                .setCookieMask(U64.NO_MASK)
                .setMatch(buildExclusionMatch(sw, srcIp, srcPort, dstIp, dstPort, proto, ethType, metadata))
                .setTableId(TableId.of(tableId))
                .build();
    }

    private Match buildExclusionMatch(IOFSwitch sw, IPv4Address srcIp, Integer srcPort,
                                      IPv4Address dstIp, Integer dstPort,
                                      IpProtocol proto, EthType ethType, long metadata) {
        Match.Builder match = getMetadataMatchBuilder(sw, metadata);
        Optional.ofNullable(srcIp).ifPresent(ip -> match.setExact(MatchField.IPV4_SRC, ip));
        Optional.ofNullable(dstIp).ifPresent(ip -> match.setExact(MatchField.IPV4_DST, ip));
        Optional.ofNullable(proto).ifPresent(protocol -> match.setExact(MatchField.IP_PROTO, protocol));
        Optional.ofNullable(ethType).ifPresent(type -> match.setExact(MatchField.ETH_TYPE, type));
        setMatchPorts(match, proto, srcPort, dstPort);
        return match.build();
    }

    private Match.Builder getMetadataMatchBuilder(IOFSwitch sw, long metadata) {
        return sw.getOFFactory().buildMatch()
                .setMasked(MatchField.METADATA, OFMetadata.ofRaw(metadata), OFMetadata.ofRaw(METADATA_MASK));
    }

    private void setMatchPorts(Match.Builder match, IpProtocol proto, Integer srcPort, Integer dstPort) {
        if (proto == null) {
            return;
        }

        if (IpProtocol.TCP.equals(proto)) {
            Optional.ofNullable(srcPort).ifPresent(port -> match.setExact(MatchField.TCP_SRC, TransportPort.of(port)));
            Optional.ofNullable(dstPort).ifPresent(port -> match.setExact(MatchField.TCP_DST, TransportPort.of(port)));

        } else if (IpProtocol.UDP.equals(proto)) {
            Optional.ofNullable(srcPort).ifPresent(port -> match.setExact(MatchField.UDP_SRC, TransportPort.of(port)));
            Optional.ofNullable(dstPort).ifPresent(port -> match.setExact(MatchField.UDP_DST, TransportPort.of(port)));
        } else {
            logger.debug("Unexpected IP protocol {}", proto);
        }
    }

    @Override
    public Long installBfdCatchFlow(DatapathId dpid) throws SwitchOperationException {

        IOFSwitch sw = lookupSwitch(dpid);

        OFFlowMod flowMod = buildBfdCatchFlow(sw);
        if (flowMod == null) {
            logger.debug("Skip installation of universal BFD catch flow for switch {}", dpid);
            return null;
        } else {
            String flowName = "--CatchBfdRule--" + dpid.toString();
            pushFlow(sw, flowName, flowMod);
            return CATCH_BFD_RULE_COOKIE;
        }
    }

    private OFFlowMod buildBfdCatchFlow(IOFSwitch sw) {
        Set<SwitchFeature> features = featureDetectorService.detectSwitch(sw);
        if (!features.contains(SwitchFeature.BFD)) {
            return null;
        }

        OFFactory ofFactory = sw.getOFFactory();

        Match match = catchRuleMatch(sw.getId(), ofFactory);
        return prepareFlowModBuilder(ofFactory, CATCH_BFD_RULE_COOKIE, CATCH_BFD_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setActions(ImmutableList.of(
                        ofFactory.actions().buildOutput()
                                .setPort(OFPort.LOCAL)
                                .build()))
                .build();
    }

    @Override
    public Long installRoundTripLatencyFlow(DatapathId dpid) throws SwitchOperationException {
        logger.info("Installing round trip default rule on {}", dpid);
        IOFSwitch sw = lookupSwitch(dpid);

        OFFlowMod flowMod = buildRoundTripLatencyFlow(sw);
        if (flowMod == null) {
            logger.debug("Skip installation of round-trip latency rule for switch {}", dpid);
            return null;
        } else {
            String flowName = "--RoundTripLatencyRule--" + dpid.toString();
            pushFlow(sw, flowName, flowMod);
            return ROUND_TRIP_LATENCY_RULE_COOKIE;
        }
    }

    private List<Long> removeFlowByOfFlowDelete(DatapathId dpid, int tableId,
                                                OFFlowDelete dropFlowDelete)
            throws SwitchOperationException {
        List<OFFlowStatsEntry> flowStatsBefore = dumpFlowTable(dpid, tableId);

        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();


        pushFlow(sw, "--DeleteFlow--", dropFlowDelete);

        // Wait for OFFlowDelete to be processed.
        sendBarrierRequest(sw);

        List<OFFlowStatsEntry> flowStatsAfter = dumpFlowTable(dpid, tableId);
        Set<Long> cookiesAfter = flowStatsAfter.stream()
                .map(entry -> entry.getCookie().getValue())
                .collect(Collectors.toSet());

        return flowStatsBefore.stream()
                .map(entry -> entry.getCookie().getValue())
                .filter(cookie -> !cookiesAfter.contains(cookie))
                .peek(cookie -> logger.info("Rule with cookie {} has been removed from switch {}.", cookie, dpid))
                .collect(Collectors.toList());
    }

    @Override
    public long installEgressIslVxlanRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowMod flowMod = buildEgressIslVxlanRule(ofFactory, dpid, port);
        String flowName = "--Isl egress rule for VXLAN--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildEgressIslVxlanRule(OFFactory ofFactory, DatapathId dpid, int port) {
        Match match = buildEgressIslVxlanRuleMatch(dpid, port, ofFactory);
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(EGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeIslVxlanEgress(port),
                ISL_EGRESS_VXLAN_RULE_PRIORITY_MULTITABLE, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable)).build();
    }

    @Override
    public long removeEgressIslVxlanRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        long cookie = Cookie.encodeIslVxlanEgress(port);
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);
        Match match = buildEgressIslVxlanRuleMatch(dpid, port, ofFactory);
        builder.setMatch(match);
        builder.setPriority(ISL_EGRESS_VXLAN_RULE_PRIORITY_MULTITABLE);

        removeFlowByOfFlowDelete(dpid, INPUT_TABLE_ID, builder.build());
        return cookie;
    }

    private Match buildEgressIslVxlanRuleMatch(DatapathId dpid, int port, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.ETH_DST, dpIdToMac(dpid))
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.IN_PORT, OFPort.of(port))
                .setExact(MatchField.UDP_SRC, TransportPort.of(STUB_VXLAN_UDP_SRC))
                .setExact(MatchField.UDP_DST, TransportPort.of(VXLAN_UDP_DST))
                .build();
    }


    @Override
    public long installTransitIslVxlanRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowMod flowMod = buildTransitIslVxlanRule(ofFactory, dpid, port);
        String flowName = "--Isl transit rule for VXLAN--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildTransitIslVxlanRule(OFFactory ofFactory, DatapathId dpid, int port) {
        Match match = buildTransitIslVxlanRuleMatch(dpid, port, ofFactory);
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(TRANSIT_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeIslVxlanTransit(port),
                ISL_TRANSIT_VXLAN_RULE_PRIORITY_MULTITABLE, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable)).build();
    }

    @Override
    public long removeTransitIslVxlanRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        long cookie = Cookie.encodeIslVxlanTransit(port);
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);
        Match match = buildTransitIslVxlanRuleMatch(dpid, port, ofFactory);
        builder.setMatch(match);
        builder.setPriority(ISL_TRANSIT_VXLAN_RULE_PRIORITY_MULTITABLE);
        removeFlowByOfFlowDelete(dpid, INPUT_TABLE_ID, builder.build());
        return cookie;
    }

    private Match buildTransitIslVxlanRuleMatch(DatapathId dpid, int port, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.IN_PORT, OFPort.of(port))
                .setExact(MatchField.UDP_SRC, TransportPort.of(STUB_VXLAN_UDP_SRC))
                .setExact(MatchField.UDP_DST, TransportPort.of(VXLAN_UDP_DST))
                .build();
    }

    @Override
    public long installEgressIslVlanRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowMod flowMod = buildEgressIslVlanRule(ofFactory, dpid, port);
        String flowName = "--Isl egress rule for VLAN--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildEgressIslVlanRule(OFFactory ofFactory, DatapathId dpid, int port) {
        Match match = buildInPortMatch(port, ofFactory);
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(EGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeIslVlanEgress(port),
                ISL_EGRESS_VLAN_RULE_PRIORITY_MULTITABLE, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable)).build();
    }

    @Override
    public long installLldpTransitFlow(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = installMeterForLldpRule(
                sw, MeterId.createMeterIdForDefaultRule(Cookie.LLDP_TRANSIT_COOKIE).getValue(), actionList);

        OFFlowMod flowMod = buildLldpTransitFlow(sw, meter, actionList);
        String flowName = "--Isl LLDP transit rule for VLAN--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildLldpTransitFlow(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
                .setExact(MatchField.ETH_DST, MacAddress.of(LLDP_MAC))
                .setExact(MatchField.ETH_TYPE, EthType.LLDP)
                .build();

        actionList.add(actionSendToController(sw));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();

        return prepareFlowModBuilder(
                ofFactory, Cookie.LLDP_TRANSIT_COOKIE,
                LLDP_TRANSIT_ISL_PRIORITY, TRANSIT_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    public long installLldpInputPreDropFlow(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = installMeterForLldpRule(
                sw, MeterId.createMeterIdForDefaultRule(Cookie.LLDP_INPUT_PRE_DROP_COOKIE).getValue(), actionList);

        OFFlowMod flowMod = buildLldpInputPreDropRule(sw, meter, actionList);
        String flowName = "--Isl LLDP input pre drop rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildLldpInputPreDropRule(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
                .setExact(MatchField.ETH_DST, MacAddress.of(LLDP_MAC))
                .setExact(MatchField.ETH_TYPE, EthType.LLDP)
                .build();

        actionList.add(actionSendToController(sw));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();
        return prepareFlowModBuilder(
                ofFactory, Cookie.LLDP_INPUT_PRE_DROP_COOKIE,
                LLDP_INPUT_PRE_DROP_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    public long removeEgressIslVlanRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        long cookie = Cookie.encodeIslVlanEgress(port);
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);
        Match match = buildInPortMatch(port, ofFactory);
        builder.setMatch(match);
        builder.setPriority(ISL_EGRESS_VLAN_RULE_PRIORITY_MULTITABLE);
        removeFlowByOfFlowDelete(dpid, EGRESS_TABLE_ID, builder.build());
        return cookie;
    }

    @Override
    public long installIntermediateIngressRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFlowMod flowMod = buildIntermediateIngressRule(dpid, port);
        String flowName = "--Customer Port intermediate rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    @Override
    public long removeIntermediateIngressRule(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        long cookie = Cookie.encodeIngressRulePassThrough(port);
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);
        Match match = buildInPortMatch(port, ofFactory);
        builder.setMatch(match);
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));
        builder.setInstructions(ImmutableList.of(goToTable));
        builder.setPriority(INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE);
        removeFlowByOfFlowDelete(dpid, INPUT_TABLE_ID, builder.build());
        return cookie;
    }

    @Override
    public long removeLldpInputCustomerFlow(DatapathId dpid, int port) throws SwitchOperationException {
        long cookie = Cookie.encodeLldpInputCustomer(port);
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(INGRESS_TABLE_ID));

        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);

        builder.setMatch(buildInPortMatch(port, ofFactory));

        builder.setInstructions(ImmutableList.of(goToTable));
        builder.setPriority(LLDP_INPUT_CUSTOMER_PRIORITY);

        removeFlowByOfFlowDelete(dpid, INPUT_TABLE_ID, builder.build());
        return cookie;
    }

    @Override
    public OFFlowMod buildIntermediateIngressRule(DatapathId dpid, int port) throws SwitchNotFoundException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        Match match = buildInPortMatch(port, ofFactory);
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeIngressRulePassThrough(port),
                INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable)).build();
    }

    @Override
    public long installLldpInputCustomerFlow(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFlowMod flowMod = buildLldpInputCustomerFlow(dpid, port);
        String flowName = "--Customer Port LLDP input rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    @Override
    public OFFlowMod buildLldpInputCustomerFlow(DatapathId dpid, int port) throws SwitchNotFoundException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        Match match = ofFactory.buildMatch()
                .setExact(MatchField.IN_PORT, OFPort.of(port))
                .setExact(MatchField.ETH_DST, MacAddress.of(LLDP_MAC))
                .setExact(MatchField.ETH_TYPE, EthType.LLDP)
                .build();

        OFInstructionWriteMetadata writeMetadata = ofFactory.instructions().buildWriteMetadata()
                .setMetadata(U64.of(METADATA_LLDP_VALUE))
                .setMetadataMask(U64.of(METADATA_LLDP_MASK)).build();

        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(INGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeLldpInputCustomer(port),
                LLDP_INPUT_CUSTOMER_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable, writeMetadata)).build();
    }

    @Override
    public long installLldpIngressFlow(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = installMeterForLldpRule(
                sw, MeterId.createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE).getValue(), actionList);
        OFFlowMod flowMod = buildLldpIngressFlow(sw, meter, actionList);

        String flowName = "--LLDP ingress rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildLldpIngressFlow(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
                .setMasked(MatchField.METADATA, OFMetadata.ofRaw(METADATA_LLDP_VALUE),
                        OFMetadata.ofRaw(METADATA_LLDP_MASK))
                .build();

        actionList.add(actionSendToController(sw));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();

        return prepareFlowModBuilder(ofFactory, LLDP_INGRESS_COOKIE, LLDP_INGRESS_PRIORITY, INGRESS_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    public long installLldpPostIngressFlow(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = installMeterForLldpRule(
                sw, MeterId.createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).getValue(), actionList);
        OFFlowMod flowMod = buildLldpPostIngressFlow(sw, meter, actionList);

        String flowName = "--LLDP post ingress rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildLldpPostIngressFlow(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
                .setMasked(MatchField.METADATA, OFMetadata.ofRaw(METADATA_LLDP_VALUE),
                        OFMetadata.ofRaw(METADATA_LLDP_MASK))
                .build();

        actionList.add(actionSendToController(sw));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();

        return prepareFlowModBuilder(ofFactory, LLDP_POST_INGRESS_COOKIE,
                LLDP_POST_INGRESS_PRIORITY, POST_INGRESS_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    public Long installLldpPostIngressVxlanFlow(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = installMeterForLldpRule(
                sw, MeterId.createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE).getValue(), actionList);
        OFFlowMod flowMod = buildLldpPostIngressVxlanFlow(sw, meter, actionList);
        if (flowMod == null) {
            logger.debug("Skip installation of LLDP post ingress vxlan flow for switch {}", dpid);
            return null;
        }

        String flowName = "--LLDP post ingress vxlan rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildLldpPostIngressVxlanFlow(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_PUSH_POP_VXLAN)) {
            return null;
        }

        Match match = ofFactory.buildMatch()
                .setMasked(MatchField.METADATA, OFMetadata.ofRaw(METADATA_LLDP_VALUE),
                        OFMetadata.ofRaw(METADATA_LLDP_MASK))
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_SRC, TransportPort.of(STUB_VXLAN_UDP_SRC))
                .setExact(MatchField.UDP_DST, TransportPort.of(VXLAN_UDP_DST))
                .build();

        actionList.add(ofFactory.actions().noviflowPopVxlanTunnel());
        actionList.add(actionSendToController(sw));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();

        return prepareFlowModBuilder(ofFactory, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_VXLAN_PRIORITY, POST_INGRESS_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    public long installLldpPostIngressOneSwitchFlow(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<OFAction> actionList = new ArrayList<>();
        OFInstructionMeter meter = installMeterForLldpRule(
                sw, MeterId.createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue(), actionList);
        OFFlowMod flowMod = buildLldpPostIngressOneSwitchFlow(sw, meter, actionList);

        String flowName = "--LLDP post ingress one switch rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildLldpPostIngressOneSwitchFlow(
            IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
                .setMasked(MatchField.METADATA, OFMetadata.ofRaw(Metadata.getOneSwitchFlowLldpValue()),
                        OFMetadata.ofRaw(Metadata.getOneSwitchFlowLldpMask()))
                .build();

        actionList.add(actionSendToController(sw));
        OFInstructionApplyActions actions = ofFactory.instructions().applyActions(actionList).createBuilder().build();

        return prepareFlowModBuilder(ofFactory, LLDP_POST_INGRESS_ONE_SWITCH_COOKIE,
                LLDP_POST_INGRESS_ONE_SWITCH_PRIORITY, POST_INGRESS_TABLE_ID)
                .setMatch(match)
                .setInstructions(meter != null ? ImmutableList.of(meter, actions) : ImmutableList.of(actions))
                .build();
    }

    @Override
    public Long installPreIngressTablePassThroughDefaultRule(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowMod flowMod = buildTablePassThroughDefaultRule(ofFactory, MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE,
                INGRESS_TABLE_ID, PRE_INGRESS_TABLE_ID);
        String flowName = "--Pass Through Pre Ingress Default Rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE;
    }

    @Override
    public Long installEgressTablePassThroughDefaultRule(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFFlowMod flowMod = buildTablePassThroughDefaultRule(ofFactory, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                TRANSIT_TABLE_ID, EGRESS_TABLE_ID);
        String flowName = "--Pass Through Egress Default Rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return MULTITABLE_EGRESS_PASS_THROUGH_COOKIE;
    }

    private OFFlowMod buildTablePassThroughDefaultRule(OFFactory ofFactory, long cookie, int goToTableId, int tableId) {
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(goToTableId));
        return prepareFlowModBuilder(
                ofFactory, cookie,
                FlowModUtils.PRIORITY_MIN + 1, tableId)
                .setInstructions(ImmutableList.of(goToTable)).build();
    }

    @Override
    public List<Long> installMultitableEndpointIslRules(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<Long> installedRules = new ArrayList<>();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            installedRules.add(installEgressIslVxlanRule(dpid, port));
            installedRules.add(installTransitIslVxlanRule(dpid, port));
        } else {
            logger.info("Skip installation of isl multitable vxlan rule for switch {} {}", dpid, port);
        }
        installedRules.add(installEgressIslVlanRule(dpid, port));
        return installedRules;
    }

    @Override
    public List<Long> removeMultitableEndpointIslRules(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<Long> removedFlows = new ArrayList<>();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            removedFlows.add(removeEgressIslVxlanRule(dpid, port));
            removedFlows.add(removeTransitIslVxlanRule(dpid, port));
        } else {
            logger.info("Skip removing of isl multitable vxlan rule for switch {} {}", dpid, port);
        }
        removedFlows.add(removeEgressIslVlanRule(dpid, port));
        return removedFlows;
    }

    private Match buildInPortMatch(int port, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.IN_PORT, OFPort.of(port))
                .build();
    }

    private OFFlowMod buildRoundTripLatencyFlow(IOFSwitch sw) {
        if (!featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_COPY_FIELD)) {
            return null;
        }

        OFFactory ofFactory = sw.getOFFactory();
        Match match = roundTripLatencyRuleMatch(sw.getId(), ofFactory);
        List<OFAction> actions = ImmutableList.of(
                actionAddRxTimestamp(sw, ROUND_TRIP_LATENCY_T1_OFFSET),
                actionSendToController(sw));
        return prepareFlowModBuilder(
                ofFactory, ROUND_TRIP_LATENCY_RULE_COOKIE, ROUND_TRIP_LATENCY_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setActions(actions)
                .build();

    }

    private Match catchRuleMatch(DatapathId dpid, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.ETH_DST, dpIdToMac(dpid))
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_DST, TransportPort.of(BDF_DEFAULT_PORT))
                .build();
    }

    private Match roundTripLatencyRuleMatch(DatapathId dpid, OFFactory ofFactory) {
        return ofFactory.buildMatch()
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.ETH_SRC, dpIdToMac(dpid))
                .setExact(MatchField.ETH_DST, MacAddress.of(verificationBcastPacketDst))
                .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                .setExact(MatchField.UDP_DST, TransportPort.of(LATENCY_PACKET_UDP_PORT))
                .build();
    }

    @Override
    public Long installDropLoopRule(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);

        OFFlowMod flowMod = buildDropLoopRule(sw);
        if (flowMod == null) {
            logger.debug("Skip installation of drop loop rule for switch {}", dpid);
            return null;
        } else {
            String flowName = "--DropLoopRule--" + dpid.toString();
            pushFlow(sw, flowName, flowMod);
            return DROP_VERIFICATION_LOOP_RULE_COOKIE;
        }
    }

    private OFFlowMod buildDropLoopRule(IOFSwitch sw) {
        OFFactory ofFactory = sw.getOFFactory();
        if (ofFactory.getVersion() == OF_12) {
            return null;
        }

        Builder builder = ofFactory.buildMatch();
        builder.setExact(MatchField.ETH_DST, MacAddress.of(verificationBcastPacketDst));
        builder.setExact(MatchField.ETH_SRC, dpIdToMac(sw.getId()));
        Match match = builder.build();

        return prepareFlowModBuilder(ofFactory,
                DROP_VERIFICATION_LOOP_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .build();

    }

    private void verifySwitchSupportsMeters(IOFSwitch sw) throws UnsupportedSwitchOperationException {
        if (!config.isOvsMetersEnabled() && isOvs(sw)) {
            throw new UnsupportedSwitchOperationException(sw.getId(),
                    format("Meters are not supported on OVS switch %s", sw.getId()));
        }

        if (sw.getOFFactory().getVersion().compareTo(OF_12) <= 0) {
            throw new UnsupportedSwitchOperationException(sw.getId(),
                    format("Meters are not supported on switch %s because of OF version %s",
                            sw.getId(), sw.getOFFactory().getVersion()));
        }
    }

    private void installMeter(IOFSwitch sw, Set<OFMeterFlags> flags, long bandwidth, long burstSize, long meterId)
            throws OfInstallException {
        logger.info("Installing meter {} on switch {} with bandwidth {}", meterId, sw.getId(), bandwidth);

        OFMeterMod meterMod = buildMeterMode(sw, OFMeterModCommand.ADD, bandwidth, burstSize, meterId, flags);

        pushFlow(sw, "--InstallMeter--", meterMod);

        // All cases when we're installing meters require that we wait until the command is processed and
        // the meter is installed.
        sendBarrierRequest(sw);
    }

    private void modifyMeter(IOFSwitch sw, long bandwidth, long burstSize, long meterId, Set<OFMeterFlags> flags)
            throws OfInstallException {
        logger.info("Updating meter {} on Switch {}", meterId, sw.getId());

        OFMeterMod meterMod = buildMeterMode(sw, OFMeterModCommand.MODIFY, bandwidth, burstSize, meterId, flags);

        pushFlow(sw, "--ModifyMeter--", meterMod);
    }

    private OFMeterMod buildMeterMode(IOFSwitch sw, OFMeterModCommand command, long bandwidth, long burstSize,
                                      long meterId, Set<OFMeterFlags> flags) {
        OFFactory ofFactory = sw.getOFFactory();

        OFMeterBandDrop.Builder bandBuilder = ofFactory.meterBands()
                .buildDrop()
                .setRate(bandwidth)
                .setBurstSize(burstSize);

        OFMeterMod.Builder meterModBuilder = ofFactory.buildMeterMod()
                .setMeterId(meterId)
                .setCommand(command)
                .setFlags(flags);

        if (sw.getOFFactory().getVersion().compareTo(OF_13) > 0) {
            meterModBuilder.setBands(singletonList(bandBuilder.build()));
        } else {
            meterModBuilder.setMeters(singletonList(bandBuilder.build()));
        }

        return meterModBuilder.build();
    }

    private void buildAndDeleteMeter(IOFSwitch sw, final DatapathId dpid, final long meterId)
            throws OfInstallException {
        logger.debug("deleting meter {} from switch {}", meterId, dpid);

        OFFactory ofFactory = sw.getOFFactory();

        OFMeterMod.Builder meterDeleteBuilder = ofFactory.buildMeterMod()
                .setMeterId(meterId)
                .setCommand(OFMeterModCommand.DELETE);

        if (sw.getOFFactory().getVersion().compareTo(OF_13) > 0) {
            meterDeleteBuilder.setBands(emptyList());
        } else {
            meterDeleteBuilder.setMeters(emptyList());
        }

        OFMeterMod meterDelete = meterDeleteBuilder.build();

        pushFlow(sw, "--DeleteMeter--", meterDelete);
    }

    private OFFlowDelete buildFlowDeleteByCriteria(OFFactory ofFactory, DeleteRulesCriteria criteria,
                                                   boolean multiTable, RuleType ruleType) {
        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        if (criteria.getCookie() != null) {
            builder.setCookie(U64.of(criteria.getCookie()));
            builder.setCookieMask(U64.NO_MASK);
        }

        if (criteria.getInPort() != null) {
            DatapathId egressSwitchId = criteria.getEgressSwitchId() != null
                    ? DatapathId.of(criteria.getEgressSwitchId().toLong())
                    : null;
            // Match either In Port or both Port & Vlan criteria.
            Match match = matchFlow(ofFactory, criteria.getInPort(),
                    Optional.ofNullable(criteria.getEncapsulationId()).orElse(0), criteria.getEncapsulationType(),
                    egressSwitchId);
            builder.setMatch(match);

        } else if (criteria.getEncapsulationId() != null) {
            // Match In Vlan criterion if In Port is not specified
            Match.Builder matchBuilder = ofFactory.buildMatch();
            MacAddress egressSwitchMac = criteria.getEgressSwitchId() != null
                    ? dpIdToMac(DatapathId.of(criteria.getEgressSwitchId().toLong()))
                    : null;
            switch (criteria.getEncapsulationType()) {
                case TRANSIT_VLAN:
                    matchVlan(ofFactory, matchBuilder, criteria.getEncapsulationId());
                    break;
                case VXLAN:
                    matchVxlan(ofFactory, matchBuilder, criteria.getEncapsulationId(),
                            egressSwitchMac);
                    break;
                default:
                    throw new UnsupportedOperationException(
                            String.format("Unknown encapsulation type: %s", criteria.getEncapsulationType()));
            }
            builder.setMatch(matchBuilder.build());
        }

        if (criteria.getPriority() != null) {
            // Match Priority criterion.
            builder.setPriority(criteria.getPriority());
        }

        if (criteria.getOutPort() != null) {
            // Match only Out Vlan criterion.
            builder.setOutPort(OFPort.of(criteria.getOutPort()));
        }
        if (multiTable) {
            switch (ruleType) {
                case TRANSIT:
                    builder.setTableId(TableId.of(TRANSIT_TABLE_ID));
                    break;
                case EGRESS:
                    builder.setTableId(TableId.of(EGRESS_TABLE_ID));
                    break;
                case INGRESS:
                    builder.setTableId(TableId.of(INGRESS_TABLE_ID));
                    break;
                default:
                    builder.setTableId(TableId.of(INPUT_TABLE_ID));
                    break;
            }
        } else {
            if (POST_INGRESS.equals(ruleType)) {
                builder.setTableId(TableId.of(TABLE_1));
            } else {
                builder.setTableId(TableId.ALL);
            }
        }

        return builder.setTableId(TableId.ALL).build();
    }

    private OFBarrierReply sendBarrierRequest(IOFSwitch sw) {
        OFFactory ofFactory = sw.getOFFactory();
        OFBarrierRequest barrierRequest = ofFactory.buildBarrierRequest().build();

        OFBarrierReply result = null;
        try {
            ListenableFuture<OFBarrierReply> future = sw.writeRequest(barrierRequest);
            result = future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not get a barrier reply for {}.", sw.getId(), e);
        } catch (InterruptedException e) {
            logger.error("Could not get a barrier reply for {}.", sw.getId(), e);
            Thread.currentThread().interrupt();
        }
        return result;
    }


    private List<Long> deleteRulesWithCookie(final DatapathId dpid, Long... cookiesToRemove)
            throws SwitchOperationException {
        DeleteRulesCriteria[] criteria = Stream.of(cookiesToRemove)
                .map(cookie -> DeleteRulesCriteria.builder().cookie(cookie).build())
                .toArray(DeleteRulesCriteria[]::new);

        return deleteRulesByCriteria(dpid, false, null, criteria);
    }

    /**
     * Creates a Match based on an inputPort and VlanID.
     * NB1: that this match only matches on the outer most tag which must be of ether-type 0x8100.
     * NB2: vlanId of 0 means match on port, not vlan
     *
     * @param ofFactory OF factory for the switch
     * @param inputPort input port for the match
     * @param tunnelId tunnel id to match on; 0 means match on port
     * @param egressSwitchId id of egress flow switch
     * @return {@link Match}
     */
    private Match matchFlow(OFFactory ofFactory, int inputPort, int tunnelId, FlowEncapsulationType encapsulationType,
                            DatapathId egressSwitchId) {
        Match.Builder mb = ofFactory.buildMatch();
        addMatchFlowToBuilder(mb, ofFactory, inputPort, tunnelId, encapsulationType, egressSwitchId);
        return mb.build();
    }

    private Match getLldpMatch(OFFactory ofFactory, int inputPort, int transitTunnelId,
                               FlowEncapsulationType encapsulationType) {
        Builder mb = ofFactory.buildMatch();
        addMatchFlowToBuilder(mb, ofFactory, inputPort, transitTunnelId, encapsulationType, null);
        mb.setExact(MatchField.ETH_DST, MacAddress.of(LLDP_MAC));
        mb.setExact(MatchField.ETH_TYPE, EthType.LLDP);
        return mb.build();
    }

    private void addMatchFlowToBuilder(Builder builder, OFFactory ofFactory, int inputPort, int tunnelId,
                                       FlowEncapsulationType encapsulationType, DatapathId egressSwitchId) {
        builder.setExact(MatchField.IN_PORT, OFPort.of(inputPort));
        MacAddress ethDstMac = null;
        if (egressSwitchId != null) {
            ethDstMac = dpIdToMac(egressSwitchId);
        }
        // NOTE: vlan of 0 means match on port on not VLAN.
        if (tunnelId > 0) {
            switch (encapsulationType) {
                case TRANSIT_VLAN:
                    matchVlan(ofFactory, builder, tunnelId);

                    break;
                case VXLAN:
                    matchVxlan(ofFactory, builder, tunnelId, ethDstMac);
                    break;
                default:
                    throw new UnsupportedOperationException(
                            String.format("Unknown encapsulation type: %s", encapsulationType));
            }
        }
    }

    private void matchVlan(final OFFactory ofFactory, final Match.Builder matchBuilder, final int vlanId) {
        if (0 <= OF_12.compareTo(ofFactory.getVersion())) {
            matchBuilder.setMasked(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(vlanId),
                    OFVlanVidMatch.ofRawVid(OF10_VLAN_MASK));
        } else {
            matchBuilder.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(vlanId));
        }
    }

    private void matchVxlan(OFFactory ofFactory, Match.Builder matchBuilder, long tunnelId,
                            MacAddress ethDst) {
        if (ethDst != null) {
            matchBuilder.setExact(MatchField.ETH_DST, ethDst);
        }
        if (OF_12.compareTo(ofFactory.getVersion()) >= 0) {
            throw new UnsupportedOperationException("Switch doesn't support tunnel_id match");
        } else {
            matchBuilder.setExact(MatchField.TUNNEL_ID, U64.of(tunnelId));
        }
    }

    /**
     * Builds OFAction list based on flow parameters for replace scheme.
     *
     * @param ofFactory OF factory for the switch
     * @param outputVlanId set vlan on packet before forwarding via outputPort; 0 means not to set
     * @param outputVlanType type of action to apply to the outputVlanId if greater than 0
     * @return list of {@link OFAction}
     */
    private List<OFAction> getOutputActionsForVlan(OFFactory ofFactory, int outputVlanId,
                                                   OutputVlanType outputVlanType) {
        List<OFAction> actionList;

        switch (outputVlanType) {
            case PUSH:
            case REPLACE:
                actionList = singletonList(actionReplaceVlan(ofFactory, outputVlanId));
                break;
            case POP:
            case NONE:
                actionList = singletonList(actionPopVlan(ofFactory));
                break;
            default:
                actionList = emptyList();
                logger.error("Unknown OutputVlanType: " + outputVlanType);
        }

        return actionList;
    }

    private List<OFAction> getOutputActionsForVxlan(OFFactory ofFactory, int outputVlanId,
                                                    OutputVlanType outputVlanType) {
        List<OFAction> actionList = new ArrayList<>(2);
        actionList.add(ofFactory.actions().noviflowPopVxlanTunnel());
        if (outputVlanType == OutputVlanType.PUSH) {
            actionList.add(actionPushVlan(ofFactory, ETH_TYPE));
            actionList.add(actionReplaceVlan(ofFactory, outputVlanId));
        } else if (outputVlanType == OutputVlanType.REPLACE) {
            actionList.add(actionReplaceVlan(ofFactory, outputVlanId));
        } else if (outputVlanType == OutputVlanType.POP) {
            actionList.add(actionPopVlan(ofFactory));
        }
        return actionList;
    }

    /**
     * Builds OFAction list based on flow parameters for push scheme.
     *
     * @param ofFactory OF factory for the switch
     * @param outputVlanId set vlan on packet before forwarding via outputPort; 0 means not to set
     * @param outputVlanType type of action to apply to the outputVlanId if greater than 0
     * @return list of {@link OFAction}
     */
    private List<OFAction> pushSchemeOutputVlanTypeToOfActionList(OFFactory ofFactory, int outputVlanId,
                                                                  OutputVlanType outputVlanType) {
        List<OFAction> actionList = new ArrayList<>(2);

        switch (outputVlanType) {
            case PUSH:      // No VLAN on packet so push a new one
                actionList.add(actionPushVlan(ofFactory, ETH_TYPE));
                actionList.add(actionReplaceVlan(ofFactory, outputVlanId));
                break;
            case REPLACE:   // VLAN on packet but needs to be replaced
                actionList.add(actionReplaceVlan(ofFactory, outputVlanId));
                break;
            case POP:       // VLAN on packet, so remove it
                // TODO:  can i do this?  pop two vlan's back to back...
                actionList.add(actionPopVlan(ofFactory));
                break;
            case NONE:
                break;
            default:
                logger.error("Unknown OutputVlanType: " + outputVlanType);
        }

        return actionList;
    }

    /**
     * Chooses encapsulation scheme for building OFAction list.
     *
     * @param ofFactory OF factory for the switch
     * @param outputVlanId set vlan on packet before forwarding via outputPort; 0 means not to set
     * @param outputVlanType type of action to apply to the outputVlanId if greater than 0
     * @return list of {@link OFAction}
     */
    private List<OFAction> egressFlowActions(OFFactory ofFactory, int outputVlanId,
                                             OutputVlanType outputVlanType,
                                             FlowEncapsulationType encapsulationType) {
        switch (encapsulationType) {
            case TRANSIT_VLAN:
                return getOutputActionsForVlan(ofFactory, outputVlanId, outputVlanType);
            case VXLAN:
                return getOutputActionsForVxlan(ofFactory, outputVlanId, outputVlanType);
            default:
                throw new UnsupportedOperationException(
                        String.format("Unknown encapsulation type: %s", encapsulationType));
        }
    }

    /**
     * Chooses encapsulation scheme for building OFAction list.
     *
     * @param ofFactory OF factory for the switch
     * @param transitTunnelId set vlan on packet or replace it before forwarding via outputPort; 0 means not to set
     * @return list of {@link OFAction}
     */
    private List<OFAction> inputVlanTypeToOfActionList(OFFactory ofFactory, int transitTunnelId,
                                                       OutputVlanType outputVlanType,
                                                       FlowEncapsulationType encapsulationType,
                                                       DatapathId ethSrc, DatapathId ethDst) {
        List<OFAction> actionList = new ArrayList<>(3);
        switch (encapsulationType) {
            case TRANSIT_VLAN:
                if (OutputVlanType.PUSH.equals(outputVlanType) || OutputVlanType.NONE.equals(outputVlanType)) {
                    actionList.add(actionPushVlan(ofFactory, ETH_TYPE));
                }
                actionList.add(actionReplaceVlan(ofFactory, transitTunnelId));
                break;
            case VXLAN:
                actionList.add(actionPushVxlan(ofFactory, transitTunnelId, dpIdToMac(ethSrc), dpIdToMac((ethDst))));
                actionList.add(actionVxlanEthSrcCopyField(ofFactory));
                break;
            default:
                throw new UnsupportedOperationException(
                        String.format("Unknown encapsulation type: %s", encapsulationType));
        }

        return actionList;
    }

    /**
     * Create an OFAction which sets the output port.
     *
     * @param ofFactory OF factory for the switch
     * @param outputPort port to set in the action
     * @return {@link OFAction}
     */
    private OFAction actionSetOutputPort(final OFFactory ofFactory, final OFPort outputPort) {
        OFActions actions = ofFactory.actions();
        return actions.buildOutput().setMaxLen(0xFFFFFFFF).setPort(outputPort).build();
    }

    /**
     * Create an OFAction to change the outer most vlan.
     *
     * @param factory OF factory for the switch
     * @param newVlan final VLAN to be set on the packet
     * @return {@link OFAction}
     */
    private OFAction actionReplaceVlan(final OFFactory factory, final int newVlan) {
        OFOxms oxms = factory.oxms();
        OFActions actions = factory.actions();

        if (OF_12.compareTo(factory.getVersion()) == 0) {
            return actions.buildSetField().setField(oxms.buildVlanVid()
                    .setValue(OFVlanVidMatch.ofRawVid((short) newVlan))
                    .build()).build();
        } else {
            return actions.buildSetField().setField(oxms.buildVlanVid()
                    .setValue(OFVlanVidMatch.ofVlan(newVlan))
                    .build()).build();
        }
    }

    /**
     * Create an OFAction to add a VLAN header.
     *
     * @param ofFactory OF factory for the switch
     * @param etherType ethernet type of the new VLAN header
     * @return {@link OFAction}
     */
    private OFAction actionPushVlan(final OFFactory ofFactory, final int etherType) {
        OFActions actions = ofFactory.actions();
        return actions.buildPushVlan().setEthertype(EthType.of(etherType)).build();
    }

    private OFAction actionPushVxlan(OFFactory ofFactory, long tunnelId, MacAddress ethSrc, MacAddress ethDst) {
        OFActions actions = ofFactory.actions();
        return actions.buildNoviflowPushVxlanTunnel()
                .setVni(tunnelId)
                .setEthSrc(ethSrc)
                .setEthDst(ethDst)
                .setUdpSrc(STUB_VXLAN_UDP_SRC)
                .setIpv4Src(STUB_VXLAN_IPV4_SRC)
                .setIpv4Dst(STUB_VXLAN_IPV4_DST)
                .setFlags((short) 0x01)
                .build();
    }

    private OFAction actionVxlanEthSrcCopyField(OFFactory ofFactory) {
        OFOxms oxms = ofFactory.oxms();
        return ofFactory.actions().buildNoviflowCopyField()
                .setNBits(MAC_ADDRESS_SIZE_IN_BITS)
                .setSrcOffset(INTERNAL_ETH_SRC_OFFSET)
                .setDstOffset(ETH_SRC_OFFSET)
                .setOxmSrcHeader(oxms.buildNoviflowPacketOffset().getTypeLen())
                .setOxmDstHeader(oxms.buildNoviflowPacketOffset().getTypeLen())
                .build();
    }

    /**
     * Create an OFAction to remove the outer most VLAN.
     *
     * @param ofFactory OF factory for the switch
     * @return {@link OFAction}
     */
    private OFAction actionPopVlan(final OFFactory ofFactory) {
        OFActions actions = ofFactory.actions();
        return actions.popVlan();
    }

    /**
     * Create an OFFlowMod builder and set required fields.
     *
     * @param ofFactory OF factory for the switch
     * @param cookie cookie for the flow
     * @param priority priority to set on the flow
     * @return {@link OFFlowMod}
     */
    private OFFlowMod.Builder prepareFlowModBuilder(final OFFactory ofFactory, final long cookie, final int priority,
                                                    final int tableId) {
        OFFlowMod.Builder fmb = ofFactory.buildFlowAdd();
        fmb.setIdleTimeout(FlowModUtils.INFINITE_TIMEOUT);
        fmb.setHardTimeout(FlowModUtils.INFINITE_TIMEOUT);
        fmb.setBufferId(OFBufferId.NO_BUFFER);
        fmb.setCookie(U64.of(cookie));
        fmb.setPriority(priority);
        fmb.setTableId(TableId.of(tableId));

        return fmb;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MacAddress dpIdToMac(DatapathId dpId) {
        return convertDpIdToMac(dpId);
    }

    public static MacAddress convertDpIdToMac(DatapathId dpId) {
        return MacAddress.of(Arrays.copyOfRange(dpId.getBytes(), 2, 8));
    }

    /**
     * Create a match object for the verification packets.
     *
     * @param sw switch object
     * @param isBroadcast if broadcast then set a generic match; else specific to switch Id
     * @return {@link Match}
     */
    private Match matchVerification(final IOFSwitch sw, final boolean isBroadcast) {
        MacAddress dstMac = isBroadcast ? MacAddress.of(verificationBcastPacketDst) : dpIdToMac(sw.getId());
        Builder builder = sw.getOFFactory().buildMatch();
        if (isBroadcast) {
            builder.setMasked(MatchField.ETH_DST, dstMac, MacAddress.NO_MASK);
            if (featureDetectorService.detectSwitch(sw).contains(MATCH_UDP_PORT)) {
                builder.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
                builder.setExact(MatchField.ETH_TYPE, EthType.IPv4);
                builder.setExact(MatchField.UDP_DST, TransportPort.of(DISCOVERY_PACKET_UDP_PORT));
            }
        } else {
            MacAddress srcMac = MacAddress.of(kildaCore.getConfig().getFlowPingMagicSrcMacAddress());
            builder.setMasked(MatchField.ETH_SRC, srcMac, MacAddress.NO_MASK);
            builder.setMasked(MatchField.ETH_DST, dstMac, MacAddress.NO_MASK);
        }
        return builder.build();
    }

    /**
     * Create an action to send packet to the controller.
     *
     * @param sw switch object
     * @return {@link OFAction}
     */
    private OFAction actionSendToController(final IOFSwitch sw) {
        OFActions actions = sw.getOFFactory().actions();
        return actions.buildOutput().setMaxLen(0xFFffFFff).setPort(OFPort.CONTROLLER)
                .build();
    }

    /**
     * Create an action to set the DstMac of a packet.
     *
     * @param sw switch object
     * @param macAddress MacAddress to set
     * @return {@link OFAction}
     */
    private OFAction actionSetDstMac(final IOFSwitch sw, final MacAddress macAddress) {
        OFOxms oxms = sw.getOFFactory().oxms();
        OFActions actions = sw.getOFFactory().actions();
        return actions.buildSetField()
                .setField(oxms.buildEthDst().setValue(macAddress).build()).build();
    }

    /**
     * A simple Match rule based on destination mac address and mask.
     * TODO: Could be generalized
     *
     * @param ofFactory OF factory for the switch
     * @param dstMac Destination Mac address to match on
     * @param dstMask Destination Mask to match on
     * @return Match
     */
    private Match simpleDstMatch(OFFactory ofFactory, String dstMac, String dstMask) {
        Match match = null;
        if (dstMac != null && dstMask != null && dstMac.length() > 0 && dstMask.length() > 0) {
            Builder builder = ofFactory.buildMatch();
            builder.setMasked(MatchField.ETH_DST, MacAddress.of(dstMac), MacAddress.NO_MASK);
            match = builder.build();
        }
        return match;
    }

    /**
     * Pushes a single flow modification command to the switch with the given datapath ID.
     *
     * @param sw open flow switch descriptor
     * @param flowId flow name, for logging
     * @param flowMod command to send
     * @return OF transaction Id (???)
     * @throws OfInstallException openflow install exception
     */
    private long pushFlow(final IOFSwitch sw, final String flowId, final OFMessage flowMod) throws OfInstallException {
        logger.info("installing {} flow: {}", flowId, flowMod);

        if (!sw.write(flowMod)) {
            throw new OfInstallException(sw.getId(), flowMod);
        }

        return flowMod.getXid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IOFSwitch lookupSwitch(DatapathId dpId) throws SwitchNotFoundException {
        IOFSwitch sw = ofSwitchService.getActiveSwitch(dpId);
        if (sw == null) {
            throw new SwitchNotFoundException(dpId);
        }
        return sw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InetAddress getSwitchIpAddress(IOFSwitch sw) {
        return ((InetSocketAddress) sw.getInetAddress()).getAddress();
    }

    @Override
    public List<OFPortDesc> getEnabledPhysicalPorts(DatapathId dpId) throws SwitchNotFoundException {
        return getPhysicalPorts(dpId).stream()
                .filter(OFPortDesc::isEnabled)
                .collect(Collectors.toList());
    }

    @Override
    public List<OFPortDesc> getPhysicalPorts(DatapathId dpId) throws SwitchNotFoundException {
        return this.getPhysicalPorts(lookupSwitch(dpId));
    }

    @Override
    public List<OFPortDesc> getPhysicalPorts(IOFSwitch sw) {
        final Collection<OFPortDesc> ports = sw.getPorts();
        if (ports == null) {
            return ImmutableList.of();
        }

        return ports.stream()
                .filter(entry -> !OfPortDescConverter.INSTANCE.isReservedPort(entry.getPortNo()))
                .collect(Collectors.toList());
    }

    private OFInstructionMeter installMeterForLldpRule(IOFSwitch sw, long meterId, List<OFAction> actionList) {
        return installOrReinstallMeter(sw, meterId, config.getLldpRateLimit(), config.getLldpPacketSize(),
                config.getLldpMeterBurstSizeInPackets(), actionList);
    }

    @VisibleForTesting
    OFInstructionMeter installMeterForDefaultRule(IOFSwitch sw, long meterId, long ratePkts,
                                                  List<OFAction> actionList) {
        return installOrReinstallMeter(sw, meterId, ratePkts, config.getDiscoPacketSize(),
                config.getSystemMeterBurstSizeInPackets(), actionList);
    }

    private OFInstructionMeter installOrReinstallMeter(IOFSwitch sw, long meterId, long rateInPackets,
                                                       long packetSizeInBytes, long burstSizeInPackets,
                                                       List<OFAction> actionList) {
        OFMeterConfig meterConfig;
        try {
            meterConfig = getMeter(sw.getId(), meterId);
        } catch (SwitchOperationException e) {
            logger.warn("Meter {} won't be installed on the switch {}: {}", meterId, sw.getId(), e.getMessage());
            return null;
        }

        OFMeterBandDrop meterBandDrop = Optional.ofNullable(meterConfig)
                .map(OFMeterConfig::getEntries)
                .flatMap(entries -> entries.stream().findFirst())
                .map(OFMeterBandDrop.class::cast)
                .orElse(null);

        try {
            long rate;
            long burstSize;
            Set<OFMeterFlags> flags;

            if (featureDetectorService.detectSwitch(sw).contains(SwitchFeature.PKTPS_FLAG)) {
                flags = ImmutableSet.of(OFMeterFlags.PKTPS, OFMeterFlags.STATS, OFMeterFlags.BURST);
                // With PKTPS flag rate and burst size is in packets
                rate = rateInPackets;
                burstSize = burstSizeInPackets;
            } else {
                flags = ImmutableSet.of(OFMeterFlags.KBPS, OFMeterFlags.STATS, OFMeterFlags.BURST);
                // With KBPS flag rate and burst size is in Kbits
                rate = Meter.convertRateToKiloBits(rateInPackets, packetSizeInBytes);
                burstSize = Meter.convertBurstSizeToKiloBits(burstSizeInPackets, packetSizeInBytes);
            }

            if (meterBandDrop != null && meterBandDrop.getRate() == rate
                    && CollectionUtils.isEqualCollection(meterConfig.getFlags(), flags)) {
                logger.debug("Meter {} won't be reinstalled on switch {}. It already exists", meterId, sw.getId());
                return buildMeterInstruction(meterId, sw, actionList);
            }

            if (meterBandDrop != null) {
                logger.info("Meter {} with origin rate {} will be reinstalled on {} switch.",
                        meterId, sw.getId(), meterBandDrop.getRate());
                buildAndDeleteMeter(sw, sw.getId(), meterId);
                sendBarrierRequest(sw);
            }

            installMeter(sw, flags, rate, burstSize, meterId);
        } catch (SwitchOperationException e) {
            logger.warn("Failed to (re)install meter {} on switch {}: {}", meterId, sw.getId(), e.getMessage());
            return null;
        }

        return buildMeterInstruction(meterId, sw, actionList);
    }

    /**
     * Creates meter instruction for OF versions 1.3 and 1.4 or adds meter to actions.
     *
     * @param meterId meter to be installed.
     * @param sw switch information.
     * @param actionList actions for the flow.
     * @return built {@link OFInstructionMeter} for OF 1.3 and 1.4, otherwise returns null.
     */
    private OFInstructionMeter buildMeterInstruction(long meterId, IOFSwitch sw, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        OFInstructionMeter meterInstruction = null;
        if (meterId != 0L && (config.isOvsMetersEnabled() || !isOvs(sw))) {
            if (ofFactory.getVersion().compareTo(OF_12) <= 0) {
                /* FIXME: Since we can't read/validate meters from switches with OF 1.2 we should not install them
                actionList.add(legacyMeterAction(ofFactory, meterId));
                */
            } else if (ofFactory.getVersion().compareTo(OF_15) == 0) {
                actionList.add(ofFactory.actions().buildMeter().setMeterId(meterId).build());
            } else /* OF_13, OF_14 */ {
                meterInstruction = ofFactory.instructions().buildMeter().setMeterId(meterId).build();
            }
        }

        return meterInstruction;
    }

    /**
     * A struct to collect all the data necessary to manage the safe application of base rules.
     */
    private static final class SafeData {
        // Any switch rule with a priority less than this will be ignored
        static final int PRIORITY_IGNORE_THRESHOLD = 100;
        private static final int window = 5;
        // Used to filter out rules with low packet counts .. only test rules with more packets than this
        private static final int PACKET_COUNT_MIN = 5;

        private final DatapathId dpid;

        /**
         * The time of data collections may be inconsistent .. so if we try to see whether the rate
         * of data is different .. then use the captured timestamps to get an average.
         */
        List<Long> timestamps;
        Map<Long, List<Long>> ruleByteCounts; // counter per cookie per timestamp
        Map<Long, List<Long>> rulePktCounts;  // counter per cookie per timestamp
        // Stages - 0 = not started; 1 = applied; 2 = okay; 3 = removed (too many errors)
        int dropRuleStage;
        int broadcastRuleStage;
        int unicastRuleStage;

        SafeData(DatapathId dpid) {
            this.dpid = dpid;
        }

        void consumeData(long timestamp, List<OFFlowStatsEntry> flowEntries) {
            timestamps.add(timestamp);

            for (OFFlowStatsEntry flowStatsEntry : flowEntries) {
                if (flowStatsEntry.getPriority() <= PRIORITY_IGNORE_THRESHOLD) {
                    continue;
                }

                long flowCookie = flowStatsEntry.getCookie().getValue();
                if (!ruleByteCounts.containsKey(flowCookie)) {
                    ruleByteCounts.put(flowCookie, new ArrayList<>());
                    rulePktCounts.put(flowCookie, new ArrayList<>());
                }
                ruleByteCounts.get(flowCookie).add(flowStatsEntry.getByteCount().getValue());
                rulePktCounts.get(flowCookie).add(flowStatsEntry.getPacketCount().getValue());
            }
        }

        // collect 2 windows per stage .. apply rule after first window
        boolean shouldApplyRule(int stage) {
            return timestamps.size() == ((stage - 1) * 2 + 1) * window;
        }

        boolean shouldTestRule(int stage) {
            return timestamps.size() == ((stage - 1) * 2 + 2) * window;
        }

        // Starting with just the effect on packet count
        List<Integer> getRuleEffect(int stage) {
            int start = (stage - 1) * 2;
            int middle = start + 1;
            int end = middle + 1;
            int goodCounts = 0;
            int badCounts = 0;

            for (List<Long> packets : rulePktCounts.values()) {
                long packetsBefore = packets.get(middle) - packets.get(start);
                // We shouldn't start at the middle .. since we wouldn't have applied the rule yet.
                // So, start at middle+1 .. that is the first data point after applying the rule.
                long packetsAfter = packets.get(end) - packets.get(middle + 1);
                boolean ruleHadNoEffect = (packetsBefore > PACKET_COUNT_MIN && packetsAfter > 0);
                if (ruleHadNoEffect) {
                    goodCounts++;
                } else {
                    badCounts++;
                }
            }
            return asList(badCounts, goodCounts);
        }

        boolean isRuleOkay(List<Integer> ruleEffect) {
            // Initial algorithm: if any rule was sending data and then stopped, then applied rule
            // is not okay.
            // The first array element has the count of "bad_counts" .. ie packet count before rule
            // wasn't zero, but was zero after.
            int badCounts = ruleEffect.get(0);
            return badCounts == 0;
        }
    }

    private Map<DatapathId, SafeData> safeSwitches = new HashMap<>();
    private long lastRun = 0L;

    private void startSafeMode(final DatapathId dpid) {
        // Don't create a new object if one already exists .. ie, don't restart the process of
        // installing base rules.
        safeSwitches.computeIfAbsent(dpid, SafeData::new);
    }

    private void stopSafeMode(final DatapathId dpid) {
        safeSwitches.remove(dpid);
    }

    private static final long tick_length = 1000;
    private static final boolean BROADCAST = true;
    private static final int DROP_STAGE = 1;
    private static final int BROADCAST_STAGE = 2;
    private static final int UNICAST_STAGE = 3;
    // NB: The logic in safeModeTick relies on these RULE_* numbers. Mostly, it relies on the
    // IS_GOOD and NO_GOOD being greater that TESTED. And in reality, TESTED is just the lower
    // of IS_GOOD and NO_GOOD.
    private static final int RULE_APPLIED = 1;
    private static final int RULE_TESTED = 2;
    private static final int RULE_IS_GOOD = 2;
    private static final int RULE_NO_GOOD = 3;

    @Override
    public void safeModeTick() {
        // this may be called sporadically, so we'll need to measure the time between calls ..
        long time = System.currentTimeMillis();
        if (time - lastRun < tick_length) {
            return;
        }

        lastRun = time;

        Collection<SafeData> values = safeSwitches.values();
        for (SafeData safeData : values) {
            // Grab switch rule stats .. X pre and post .. X for 0, X for 1 .. make a decision.
            try {
                safeData.consumeData(time, dumpFlowTable(safeData.dpid));
                int datapoints = safeData.timestamps.size();

                if (safeData.dropRuleStage < RULE_TESTED) {

                    logger.debug("SAFE MODE: Collected Data during Drop Rule Stage for '{}' ", safeData.dpid);
                    if (safeData.shouldApplyRule(DROP_STAGE)) {
                        logger.info("SAFE MODE: APPLY Drop Rule for '{}' ", safeData.dpid);
                        safeData.dropRuleStage = RULE_APPLIED;
                        installDropFlow(safeData.dpid);
                    } else if (safeData.shouldTestRule(DROP_STAGE)) {
                        List<Integer> ruleEffect = safeData.getRuleEffect(DROP_STAGE);
                        if (safeData.isRuleOkay(ruleEffect)) {
                            logger.info("SAFE MODE: Drop Rule is GOOD for '{}' ", safeData.dpid);
                            safeData.dropRuleStage = RULE_IS_GOOD;
                        } else {
                            logger.warn("SAFE MODE: Drop Rule is BAD for '{}'. "
                                            + "Good Packet Count: {}. Bad Packet Count: {} ",
                                    safeData.dpid, ruleEffect.get(0), ruleEffect.get(1));
                            safeData.dropRuleStage = RULE_NO_GOOD;
                            deleteRulesWithCookie(safeData.dpid, DROP_RULE_COOKIE);
                        }
                    }

                } else if (safeData.broadcastRuleStage < RULE_TESTED) {

                    logger.debug("SAFE MODE: Collected Data during Broadcast Verification Rule "
                            + "Stage for '{}' ", safeData.dpid);
                    if (safeData.shouldApplyRule(BROADCAST_STAGE)) {
                        logger.info("SAFE MODE: APPLY Broadcast Verification Rule for '{}' ", safeData.dpid);
                        safeData.broadcastRuleStage = RULE_APPLIED;
                        installVerificationRule(safeData.dpid, BROADCAST);
                    } else if (safeData.shouldTestRule(BROADCAST_STAGE)) {
                        List<Integer> ruleEffect = safeData.getRuleEffect(BROADCAST_STAGE);
                        if (safeData.isRuleOkay(ruleEffect)) {
                            logger.info("SAFE MODE: Broadcast Verification Rule is GOOD for '{}' ", safeData.dpid);
                            safeData.broadcastRuleStage = RULE_IS_GOOD;
                        } else {
                            logger.warn("SAFE MODE: Broadcast Verification Rule is BAD for '{}'. "
                                            + "Good Packet Count: {}. Bad Packet Count: {} ",
                                    safeData.dpid, ruleEffect.get(0), ruleEffect.get(1));
                            safeData.broadcastRuleStage = RULE_NO_GOOD;
                            deleteRulesWithCookie(safeData.dpid, VERIFICATION_BROADCAST_RULE_COOKIE);
                        }
                    }
                } else if (safeData.unicastRuleStage < RULE_TESTED) {

                    // TODO: make this smarter and advance the unicast if unicast not applied.
                    logger.debug("SAFE MODE: Collected Data during Unicast Verification Rule Stage "
                            + "for '{}' ", safeData.dpid);
                    if (safeData.shouldApplyRule(UNICAST_STAGE)) {
                        logger.info("SAFE MODE: APPLY Unicast Verification Rule for '{}' ", safeData.dpid);
                        safeData.unicastRuleStage = RULE_APPLIED;
                        installVerificationRule(safeData.dpid, !BROADCAST);
                    } else if (safeData.shouldTestRule(UNICAST_STAGE)) {
                        List<Integer> ruleEffect = safeData.getRuleEffect(UNICAST_STAGE);
                        if (safeData.isRuleOkay(ruleEffect)) {
                            logger.info("SAFE MODE: Unicast Verification Rule is GOOD for '{}' ", safeData.dpid);
                            safeData.unicastRuleStage = RULE_IS_GOOD;
                        } else {
                            logger.warn("SAFE MODE: Unicast Verification Rule is BAD for '{}'. "
                                            + "Good Packet Count: {}. Bad Packet Count: {} ",
                                    safeData.dpid, ruleEffect.get(0), ruleEffect.get(1));
                            safeData.unicastRuleStage = RULE_NO_GOOD;
                            deleteRulesWithCookie(safeData.dpid, VERIFICATION_UNICAST_RULE_COOKIE);
                        }
                    }

                } else {
                    // once done with installing rules, we need to notify kilda that the switch is up
                    // and that ports up.
                    logger.info("SAFE MODE: COMPLETED base rules for '{}' ", safeData.dpid);
                    IOFSwitch sw = lookupSwitch(safeData.dpid);
                    switchTracking.completeSwitchActivation(sw.getId());
                    // WE ARE DONE!! Remove ourselves from the list.
                    values.remove(safeData);  // will be reflected in safeSwitches
                }
            } catch (SwitchOperationException e) {
                logger.error("Error while switch {} was in safe mode. Removing switch from safe "
                        + "mode and NOT SENDING ACTIVATION. \nERROR: {}", safeData.dpid, e);
                values.remove(safeData);
            }
        }
    }

    // TODO(surabujin): this method can/should be moved to the RecordHandler level
    @Override
    public void configurePort(DatapathId dpId, int portNumber, Boolean portAdminDown) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpId);

        boolean makeChanges = false;
        if (portAdminDown != null) {
            makeChanges = true;
            updatePortStatus(sw, portNumber, portAdminDown);
        }

        if (makeChanges) {
            sendBarrierRequest(sw);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OFPortDesc> dumpPortsDescription(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);

        return new ArrayList<>(sw.getPorts());
    }

    @Override
    public boolean isTrackingEnabled() {
        return config.isTrackingEnabled();
    }

    @Override
    public long installExclusion(DatapathId dpid, Long cookie,
                                 IPv4Address srcIp, Integer srcPort, IPv4Address dstIp, Integer dstPort,
                                 IpProtocol proto, EthType ethType, Metadata metadata, int timeout)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);

        return pushFlow(sw, "--InstallExclusion--", buildExclusionDropFlow(sw, APPLICATIONS_TABLE_ID, cookie,
                srcIp, srcPort, dstIp, dstPort, proto, ethType, metadata.getRawValue(), timeout));
    }

    @Override
    public long removeExclusion(DatapathId dpid, Long cookie,
                                IPv4Address srcIp, Integer srcPort, IPv4Address dstIp, Integer dstPort,
                                IpProtocol proto, EthType ethType, Metadata metadata)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);

        return pushFlow(sw, "--DeleteExclusion--", buildExclusionDeleteCommand(sw, APPLICATIONS_TABLE_ID, cookie,
                srcIp, srcPort, dstIp, dstPort, proto, ethType, metadata.getRawValue()));
    }

    private void updatePortStatus(IOFSwitch sw, int portNumber, boolean isAdminDown) throws SwitchOperationException {
        Set<OFPortConfig> config = new HashSet<>(1);
        if (isAdminDown) {
            config.add(OFPortConfig.PORT_DOWN);
        }

        Set<OFPortConfig> portMask = ImmutableSet.of(OFPortConfig.PORT_DOWN);

        final OFFactory ofFactory = sw.getOFFactory();
        OFPortMod ofPortMod = ofFactory.buildPortMod()
                .setPortNo(OFPort.of(portNumber))
                // switch can argue against empty HWAddress (BAD_HW_ADDR) :(
                .setHwAddr(getPortHwAddress(sw, portNumber))
                .setConfig(config)
                .setMask(portMask)
                .build();

        if (!sw.write(ofPortMod)) {
            throw new SwitchOperationException(sw.getId(),
                    format("Unable to update port configuration: %s", ofPortMod));
        }

        logger.debug("Successfully updated port status {}", ofPortMod);
    }

    private MacAddress getPortHwAddress(IOFSwitch sw, int portNumber) throws SwitchOperationException {
        OFPortDesc portDesc = sw.getPort(OFPort.of(portNumber));
        if (portDesc == null) {
            throw new SwitchOperationException(sw.getId(),
                    format("Unable to get port by number %d on the switch %s",
                            portNumber, sw.getId()));
        }
        return portDesc.getHwAddr();
    }

    private boolean isOvs(IOFSwitch sw) {
        return OVS_MANUFACTURER.equals(sw.getSwitchDescription().getManufacturerDescription());
    }

    private OFMeterConfig getMeter(DatapathId dpid, long meter) throws SwitchOperationException {
        return dumpMeters(dpid).stream()
                .filter(meterConfig -> meterConfig.getMeterId() == meter)
                .findFirst()
                .orElse(null);
    }

    private int getFlowPriority(int inputVlanId) {
        return inputVlanId == 0 ? DEFAULT_FLOW_PRIORITY : FLOW_PRIORITY;
    }
}
