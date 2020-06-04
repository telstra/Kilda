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
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.openkilda.floodlight.pathverification.PathVerificationService.LATENCY_PACKET_UDP_PORT;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionPushVlan;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.actionReplaceVlan;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.buildInstructionApplyActions;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.convertDpIdToMac;
import static org.openkilda.floodlight.switchmanager.SwitchFlowUtils.isOvs;
import static org.openkilda.floodlight.switchmanager.factory.generator.server42.Server42InputFlowGenerator.buildServer42CopyFirstTimestamp;
import static org.openkilda.messaging.Utils.ETH_TYPE;
import static org.openkilda.messaging.command.flow.RuleType.POST_INGRESS;
import static org.openkilda.model.MeterId.MIN_FLOW_METER_ID;
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN;
import static org.openkilda.model.cookie.Cookie.ARP_INGRESS_COOKIE;
import static org.openkilda.model.cookie.Cookie.ARP_INPUT_PRE_DROP_COOKIE;
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_COOKIE;
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_ONE_SWITCH_COOKIE;
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_VXLAN_COOKIE;
import static org.openkilda.model.cookie.Cookie.ARP_TRANSIT_COOKIE;
import static org.openkilda.model.cookie.Cookie.CATCH_BFD_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.DROP_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.DROP_VERIFICATION_LOOP_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.LLDP_INGRESS_COOKIE;
import static org.openkilda.model.cookie.Cookie.LLDP_INPUT_PRE_DROP_COOKIE;
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_COOKIE;
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE;
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_VXLAN_COOKIE;
import static org.openkilda.model.cookie.Cookie.LLDP_TRANSIT_COOKIE;
import static org.openkilda.model.cookie.Cookie.MULTITABLE_EGRESS_PASS_THROUGH_COOKIE;
import static org.openkilda.model.cookie.Cookie.MULTITABLE_INGRESS_DROP_COOKIE;
import static org.openkilda.model.cookie.Cookie.MULTITABLE_POST_INGRESS_DROP_COOKIE;
import static org.openkilda.model.cookie.Cookie.MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE;
import static org.openkilda.model.cookie.Cookie.MULTITABLE_TRANSIT_DROP_COOKIE;
import static org.openkilda.model.cookie.Cookie.ROUND_TRIP_LATENCY_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.SERVER_42_OUTPUT_VLAN_COOKIE;
import static org.openkilda.model.cookie.Cookie.SERVER_42_OUTPUT_VXLAN_COOKIE;
import static org.openkilda.model.cookie.Cookie.SERVER_42_TURNING_COOKIE;
import static org.openkilda.model.cookie.Cookie.VERIFICATION_BROADCAST_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.VERIFICATION_UNICAST_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE;
import static org.openkilda.model.cookie.Cookie.isDefaultRule;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_12;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_13;
import static org.projectfloodlight.openflow.protocol.OFVersion.OF_15;

import org.openkilda.floodlight.KildaCore;
import org.openkilda.floodlight.config.provider.FloodlightModuleConfigurationProvider;
import org.openkilda.floodlight.converter.OfMeterConverter;
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
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowFactory;
import org.openkilda.floodlight.switchmanager.factory.SwitchFlowTuple;
import org.openkilda.floodlight.switchmanager.factory.generator.SwitchFlowGenerator;
import org.openkilda.floodlight.switchmanager.web.SwitchManagerWebRoutable;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.floodlight.utils.NewCorrelationContextRequired;
import org.openkilda.floodlight.utils.metadata.RoutingMetadata;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.command.flow.RuleType;
import org.openkilda.messaging.command.switches.ConnectModeRequest;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.meter.MeterEntry;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.Meter;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.Cookie;
import org.openkilda.model.cookie.FlowSharedSegmentCookie;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
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
import java.util.Objects;
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
    public static final int FLOW_LOOP_PRIORITY = FLOW_PRIORITY + 100;
    public static final int ISL_EGRESS_VXLAN_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 2;
    public static final int ISL_TRANSIT_VXLAN_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 3;
    public static final int INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 2;
    public static final int ISL_EGRESS_VLAN_RULE_PRIORITY_MULTITABLE = FLOW_PRIORITY - 5;
    public static final int DEFAULT_FLOW_PRIORITY = FLOW_PRIORITY - 1;
    public static final int MINIMAL_POSITIVE_PRIORITY = FlowModUtils.PRIORITY_MIN + 1;

    public static final int SERVER_42_INPUT_PRIORITY = INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE;
    public static final int SERVER_42_TURNING_PRIORITY = VERIFICATION_RULE_PRIORITY;
    public static final int SERVER_42_OUTPUT_VLAN_PRIORITY = VERIFICATION_RULE_PRIORITY;
    public static final int SERVER_42_OUTPUT_VXLAN_PRIORITY = VERIFICATION_RULE_PRIORITY;

    public static final int LLDP_INPUT_PRE_DROP_PRIORITY = MINIMAL_POSITIVE_PRIORITY + 1;
    public static final int LLDP_TRANSIT_ISL_PRIORITY = FLOW_PRIORITY - 1;
    public static final int LLDP_INPUT_CUSTOMER_PRIORITY = FLOW_PRIORITY - 1;
    public static final int LLDP_INGRESS_PRIORITY = MINIMAL_POSITIVE_PRIORITY + 1;
    public static final int LLDP_POST_INGRESS_PRIORITY = FLOW_PRIORITY - 2;
    public static final int LLDP_POST_INGRESS_VXLAN_PRIORITY = FLOW_PRIORITY - 1;
    public static final int LLDP_POST_INGRESS_ONE_SWITCH_PRIORITY = FLOW_PRIORITY;

    public static final int ARP_INPUT_PRE_DROP_PRIORITY = MINIMAL_POSITIVE_PRIORITY + 1;
    public static final int ARP_TRANSIT_ISL_PRIORITY = FLOW_PRIORITY - 1;
    public static final int ARP_INPUT_CUSTOMER_PRIORITY = FLOW_PRIORITY - 1;
    public static final int ARP_INGRESS_PRIORITY = MINIMAL_POSITIVE_PRIORITY + 1;
    public static final int ARP_POST_INGRESS_PRIORITY = FLOW_PRIORITY - 2;
    public static final int ARP_POST_INGRESS_VXLAN_PRIORITY = FLOW_PRIORITY - 1;
    public static final int ARP_POST_INGRESS_ONE_SWITCH_PRIORITY = FLOW_PRIORITY;

    public static final int SERVER_42_INGRESS_DEFAULT_FLOW_PRIORITY_OFFSET = -10;
    public static final int SERVER_42_INGRESS_DOUBLE_VLAN_FLOW_PRIORITY_OFFSET = 10;
    public static final int SERVER_42_INGRESS_DEFAULT_FLOW_PRIORITY = FLOW_PRIORITY
            + SERVER_42_INGRESS_DEFAULT_FLOW_PRIORITY_OFFSET;
    public static final int SERVER_42_INGRESS_DOUBLE_VLAN_FLOW_PRIORITY = FLOW_PRIORITY
            + SERVER_42_INGRESS_DOUBLE_VLAN_FLOW_PRIORITY_OFFSET;

    public static final int BDF_DEFAULT_PORT = 3784;
    public static final int ROUND_TRIP_LATENCY_GROUP_ID = 1;
    public static final MacAddress STUB_VXLAN_ETH_DST_MAC = MacAddress.of(0xFFFFFFEDCBA2L);
    public static final IPv4Address STUB_VXLAN_IPV4_SRC = IPv4Address.of("127.0.0.1");
    public static final IPv4Address STUB_VXLAN_IPV4_DST = IPv4Address.of("127.0.0.2");
    public static final int STUB_VXLAN_UDP_SRC = 4500;
    public static final int ARP_VXLAN_UDP_SRC = 4501;
    public static final int SERVER_42_FORWARD_UDP_PORT = 4700;
    public static final int SERVER_42_REVERSE_UDP_PORT = 4701;
    public static final int VXLAN_UDP_DST = 4789;
    public static final int ETH_SRC_OFFSET = 48;
    public static final int INTERNAL_ETH_SRC_OFFSET = 448;
    public static final int MAC_ADDRESS_SIZE_IN_BITS = 48;
    public static final int TABLE_1 = 1;

    public static final int INPUT_TABLE_ID = 0;
    public static final int PRE_INGRESS_TABLE_ID = 1;
    public static final int INGRESS_TABLE_ID = 2;
    public static final int POST_INGRESS_TABLE_ID = 3;
    public static final int EGRESS_TABLE_ID = 4;
    public static final int TRANSIT_TABLE_ID = 5;

    public static final int NOVIFLOW_TIMESTAMP_SIZE_IN_BITS = 64;

    // This is invalid VID mask - it cut of highest bit that indicate presence of VLAN tag on package. But valid mask
    // 0x1FFF lead to rule reject during install attempt on accton based switches.
    private static short OF10_VLAN_MASK = 0x0FFF;

    private IOFSwitchService ofSwitchService;
    private IKafkaProducerService producerService;
    private SwitchTrackingService switchTracking;
    private FeatureDetectorService featureDetectorService;
    private SwitchFlowFactory switchFlowFactory;

    private ConnectModeRequest.Mode connectMode;
    private SwitchManagerConfig config;

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableList.of(
                ISwitchManager.class,
                SwitchTrackingService.class,
                SwitchFlowFactory.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.<Class<? extends IFloodlightService>, IFloodlightService>builder()
                .put(ISwitchManager.class, this)
                .put(SwitchTrackingService.class, new SwitchTrackingService())
                .put(SwitchFlowFactory.class, new SwitchFlowFactory())
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
        switchFlowFactory = context.getServiceImpl(SwitchFlowFactory.class);
        String connectModeProperty = config.getConnectMode();

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
        logger.info("Module {} - start up", SwitchFlowFactory.class.getName());
        context.getServiceImpl(SwitchFlowFactory.class).setup(context);

        context.getServiceImpl(IFloodlightProviderService.class).addOFMessageListener(OFType.ERROR, this);
        context.getServiceImpl(IRestApiService.class).addRestletRoutable(new SwitchManagerWebRoutable());
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
            producerService.sendMessageAndTrackWithZk("kilda.flow", error);
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
    public List<Long> installDefaultRules(final DatapathId dpid) throws SwitchOperationException {
        List<Long> rules = new ArrayList<>();
        rules.add(installDropFlow(dpid));
        rules.add(installVerificationRule(dpid, true));
        rules.add(installVerificationRule(dpid, false));
        rules.add(installDropLoopRule(dpid));
        rules.add(installBfdCatchFlow(dpid));
        rules.add(installRoundTripLatencyFlow(dpid));
        rules.add(installUnicastVerificationRuleVxlan(dpid));
        return rules;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long installIngressFlow(DatapathId dpid, DatapathId dstDpid, String flowId, Long cookie, int inputPort,
                                   int outputPort, int inputVlanId, int transitTunnelId, OutputVlanType outputVlanType,
                                   long meterId, FlowEncapsulationType encapsulationType, boolean multiTable)
            throws SwitchOperationException {
        List<OFAction> actionList = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        // build meter instruction
        OFInstructionMeter meter = buildMeterInstruction(meterId, sw, actionList);

        // output action based on encap scheme
        actionList.addAll(inputVlanTypeToOfActionList(ofFactory, transitTunnelId, outputVlanType,
                encapsulationType, dpid, dstDpid));

        // transmit packet from outgoing port
        actionList.add(actionSetOutputPort(ofFactory, OFPort.of(outputPort)));

        // build instruction with action list
        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);

        // build match by input port and input vlan id, it's always transit vlan type, since kilda doesn't allow
        // other flow endpoints
        Match match = matchFlow(ofFactory, inputPort, inputVlanId, FlowEncapsulationType.TRANSIT_VLAN);

        int flowPriority = getFlowPriority(inputVlanId);

        List<OFInstruction> instructions = createIngressFlowInstructions(ofFactory, meter, actions, multiTable);

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

    private List<OFInstruction> createIngressFlowInstructions(
            OFFactory ofFactory, OFInstructionMeter meter, OFInstructionApplyActions actions, boolean goToPostIngress) {
        List<OFInstruction> instructions = new ArrayList<>();

        if (meter != null) {
            instructions.add(meter);
        }

        instructions.add(actions);

        if (goToPostIngress) {
            instructions.add(ofFactory.instructions().gotoTable(TableId.of(POST_INGRESS_TABLE_ID)));
        }

        return instructions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long installServer42IngressFlow(
            DatapathId dpid, DatapathId dstDpid, Long cookie, org.openkilda.model.MacAddress server42MacAddress,
            int server42Port, int outputPort, int customerPort, int inputVlanId, int transitTunnelId,
            OutputVlanType outputVlanType, FlowEncapsulationType encapsulationType, boolean multiTable)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        Set<SwitchFeature> features = featureDetectorService.detectSwitch(sw);
        OFFactory ofFactory = sw.getOFFactory();
        List<OFAction> actionList = new ArrayList<>();

        actionList.addAll(pushTransitEncapsulationForServer42IngressFlow(ofFactory, transitTunnelId, outputVlanType,
                encapsulationType, dpid, dstDpid, features, multiTable));

        actionList.add(actionSetOutputPort(ofFactory, OFPort.of(outputPort)));

        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);
        // build match by server 42 port (input port), input vlan id, customer port (metadata match) and mac address
        Match match = matchServer42IngressFlow(
                sw, server42MacAddress, server42Port, customerPort, inputVlanId, multiTable);

        int flowPriority = inputVlanId == 0 ? SERVER_42_INGRESS_DEFAULT_FLOW_PRIORITY : FLOW_PRIORITY;
        int tableId = multiTable ? INGRESS_TABLE_ID : INPUT_TABLE_ID;

        List<OFInstruction> instructions = createIngressFlowInstructions(ofFactory, null, actions, false);

        OFFlowMod.Builder builder = prepareFlowModBuilder(ofFactory, cookie & FLOW_COOKIE_MASK, flowPriority,
                tableId)
                .setInstructions(instructions)
                .setMatch(match);

        if (features.contains(SwitchFeature.RESET_COUNTS_FLAG)) {
            builder.setFlags(ImmutableSet.of(OFFlowModFlags.RESET_COUNTS));
        }
        return pushFlow(sw, "--InstallServer42IngressFlow--", builder.build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long installEgressFlow(DatapathId dpid, String flowId, Long cookie, int inputPort, int outputPort,
                                  int transitTunnelId, int outputVlanId, OutputVlanType outputVlanType,
                                  FlowEncapsulationType encapsulationType,
                                  boolean multiTable) throws SwitchOperationException {
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
                .setMatch(matchFlow(ofFactory, inputPort, transitTunnelId, encapsulationType))
                .setInstructions(ImmutableList.of(actions))
                .build();

        return pushFlow(sw, "--InstallEgressFlow--", flowMod);
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
        Match match = matchFlow(ofFactory, inputPort, transitTunnelId, encapsulationType);

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
                                     boolean multiTable) throws SwitchOperationException {
        // TODO: As per other locations, how different is this to IngressFlow? Why separate code path?
        //          As with any set of tests, the more we test the same code path, the better.
        //          Based on brief glance, this looks 90% the same as IngressFlow.

        List<OFAction> actionList = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();


        // build meter instruction
        OFInstructionMeter meter = buildMeterInstruction(meterId, sw, actionList);

        // output action based on encap scheme
        actionList.addAll(pushSchemeOutputVlanTypeToOfActionList(ofFactory, outputVlanId, outputVlanType));
        // transmit packet from outgoing port
        OFPort ofOutputPort = outputPort == inputPort ? OFPort.IN_PORT : OFPort.of(outputPort);
        actionList.add(actionSetOutputPort(ofFactory, ofOutputPort));

        // build instruction with action list
        OFInstructionApplyActions actions = buildInstructionApplyActions(ofFactory, actionList);

        // build match by input port and transit vlan id
        Match match = matchFlow(ofFactory, inputPort, inputVlanId, FlowEncapsulationType.TRANSIT_VLAN);

        int flowPriority = getFlowPriority(inputVlanId);
        List<OFInstruction> instructions = createIngressFlowInstructions(ofFactory, meter, actions, multiTable);

        if (multiTable) {
            // to distinguish LLDP packets in one switch flow and in common flow
            RoutingMetadata metadata = buildMetadata(RoutingMetadata.builder().oneSwitchFlowFlag(true), sw);
            OFInstructionWriteMetadata writeMetadata = ofFactory.instructions().buildWriteMetadata()
                    .setMetadata(metadata.getValue())
                    .setMetadataMask(metadata.getMask())
                    .build();
            instructions.add(writeMetadata);
        }

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

    @Override
    public void installOuterVlanMatchSharedFlow(SwitchId switchId, String flowId, FlowSharedSegmentCookie cookie)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(DatapathId.of(switchId.toLong()));
        OFFactory of = sw.getOFFactory();

        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(cookie.getVlanId())
                .build(featureDetectorService.detectSwitch(sw));
        ImmutableList<OFInstruction> instructions = ImmutableList.of(
                of.instructions().applyActions(ImmutableList.of(of.actions().popVlan())),
                of.instructions().writeMetadata(metadata.getValue(), metadata.getMask()),
                of.instructions().gotoTable(TableId.of(SwitchManager.INGRESS_TABLE_ID)));

        OFFlowMod flow = prepareFlowModBuilder(of, cookie.getValue(), FLOW_PRIORITY, PRE_INGRESS_TABLE_ID)
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(cookie.getPortNumber()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(cookie.getVlanId()))
                        .build())
                .setInstructions(instructions)
                .build();
        pushFlow(sw, flowId, flow);
    }

    @Override
    public void makeServer42OuterVlanMatchSharedMessage(DatapathId dpid, FlowSharedSegmentCookie cookie)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory of = sw.getOFFactory();

        RoutingMetadata metadata = RoutingMetadata.builder()
                .outerVlanId(cookie.getVlanId())
                .build(featureDetectorService.detectSwitch(sw));
        ImmutableList<OFInstruction> instructions = ImmutableList.of(
                of.instructions().applyActions(ImmutableList.of(of.actions().popVlan())),
                of.instructions().writeMetadata(metadata.getValue(), metadata.getMask()),
                of.instructions().gotoTable(TableId.of(SwitchManager.INGRESS_TABLE_ID)));

        OFFlowMod flow = prepareFlowModBuilder(of, cookie.getValue(), FLOW_PRIORITY, PRE_INGRESS_TABLE_ID)
                .setCookie(U64.of(cookie.getValue()))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(cookie.getPortNumber()))
                        .setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(cookie.getVlanId()))
                        .build())
                .setInstructions(instructions)
                .build();

        pushFlow(sw, "--server 42 shared rule--", flow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OFFlowMod> getExpectedDefaultFlows(DatapathId dpid, boolean multiTable, boolean switchLldp,
                                                   boolean switchArp)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<SwitchFlowGenerator> defaultFlowGenerators = getDefaultSwitchFlowGenerators(
                multiTable, switchLldp, switchArp);

        return defaultFlowGenerators.stream()
                .map(g -> g.generateFlow(sw))
                .map(SwitchFlowTuple::getFlow)
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MeterEntry> getExpectedDefaultMeters(DatapathId dpid, boolean multiTable, boolean switchLldp,
                                                     boolean switchArp)
            throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<SwitchFlowGenerator> defaultFlowGenerators = getDefaultSwitchFlowGenerators(
                multiTable, switchLldp, switchArp);

        return defaultFlowGenerators.stream()
                .map(g -> g.generateFlow(sw))
                .map(SwitchFlowTuple::getMeter)
                .filter(Objects::nonNull)
                .map(OfMeterConverter::toMeterEntry)
                .collect(toList());
    }

    private List<SwitchFlowGenerator> getDefaultSwitchFlowGenerators(boolean multiTable, boolean switchLldp,
                                                                     boolean switchArp) {
        List<SwitchFlowGenerator> defaultFlowGenerators = new ArrayList<>();
        defaultFlowGenerators.add(switchFlowFactory.getDropFlowGenerator(DROP_RULE_COOKIE, INPUT_TABLE_ID));
        defaultFlowGenerators.add(switchFlowFactory.getVerificationFlow(true));
        defaultFlowGenerators.add(switchFlowFactory.getVerificationFlow(false));
        defaultFlowGenerators.add(switchFlowFactory.getDropLoopFlowGenerator());
        defaultFlowGenerators.add(switchFlowFactory.getBfdCatchFlowGenerator());
        defaultFlowGenerators.add(switchFlowFactory.getRoundTripLatencyFlowGenerator());
        defaultFlowGenerators.add(switchFlowFactory.getUnicastVerificationVxlanFlowGenerator());

        if (multiTable) {
            defaultFlowGenerators.add(
                    switchFlowFactory.getDropFlowGenerator(MULTITABLE_INGRESS_DROP_COOKIE, INGRESS_TABLE_ID));
            defaultFlowGenerators.add(
                    switchFlowFactory.getDropFlowGenerator(MULTITABLE_TRANSIT_DROP_COOKIE, TRANSIT_TABLE_ID));
            defaultFlowGenerators.add(
                    switchFlowFactory.getDropFlowGenerator(MULTITABLE_POST_INGRESS_DROP_COOKIE, POST_INGRESS_TABLE_ID));
            defaultFlowGenerators.add(switchFlowFactory.getTablePassThroughDefaultFlowGenerator(
                    MULTITABLE_EGRESS_PASS_THROUGH_COOKIE, TRANSIT_TABLE_ID, EGRESS_TABLE_ID));
            defaultFlowGenerators.add(switchFlowFactory.getTablePassThroughDefaultFlowGenerator(
                    MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, INGRESS_TABLE_ID, PRE_INGRESS_TABLE_ID));
            defaultFlowGenerators.add(switchFlowFactory.getLldpPostIngressFlowGenerator());
            defaultFlowGenerators.add(switchFlowFactory.getLldpPostIngressVxlanFlowGenerator());
            defaultFlowGenerators.add(switchFlowFactory.getLldpPostIngressOneSwitchFlowGenerator());
            defaultFlowGenerators.add(switchFlowFactory.getArpPostIngressFlowGenerator());
            defaultFlowGenerators.add(switchFlowFactory.getArpPostIngressVxlanFlowGenerator());
            defaultFlowGenerators.add(switchFlowFactory.getArpPostIngressOneSwitchFlowGenerator());

            if (switchLldp) {
                defaultFlowGenerators.add(switchFlowFactory.getLldpTransitFlowGenerator());
                defaultFlowGenerators.add(switchFlowFactory.getLldpInputPreDropFlowGenerator());
                defaultFlowGenerators.add(switchFlowFactory.getLldpIngressFlowGenerator());
            }
            if (switchArp) {
                defaultFlowGenerators.add(switchFlowFactory.getArpTransitFlowGenerator());
                defaultFlowGenerators.add(switchFlowFactory.getArpInputPreDropFlowGenerator());
                defaultFlowGenerators.add(switchFlowFactory.getArpIngressFlowGenerator());
            }
        }
        return defaultFlowGenerators;
    }

    @Override
    public List<OFFlowMod> getExpectedIslFlowsForPort(DatapathId dpid, int port) throws SwitchOperationException {
        List<OFFlowMod> flows = new ArrayList<>();
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_PUSH_POP_VXLAN)) {
            flows.add(buildEgressIslVxlanRule(ofFactory, dpid, port));
            flows.add(buildTransitIslVxlanRule(ofFactory, port));
        }
        flows.add(buildEgressIslVlanRule(ofFactory, port));
        return flows;
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
                        .collect(toList());
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
                        .collect(toList());
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
        List<OFMeterConfig> result = new ArrayList<>();
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
                        .collect(toList());
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
                        .collect(toList());
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
    public Map<DatapathId, IOFSwitch> getAllSwitchMap(boolean visible) {
        return ofSwitchService.getAllSwitchMap().entrySet()
                .stream()
                .filter(e -> visible == e.getValue().getStatus().isVisible())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
                .collect(toList());
    }

    @Override
    public List<Long> deleteDefaultRules(DatapathId dpid, List<Integer> islPorts,
                                         List<Integer> flowPorts, Set<Integer> flowLldpPorts,
                                         Set<Integer> flowArpPorts, Set<Integer> server42FlowRttPorts,
                                         boolean multiTable, boolean switchLldp, boolean switchArp,
                                         boolean server42FlowRtt) throws SwitchOperationException {

        List<Long> deletedRules = deleteRulesWithCookie(dpid, DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE, CATCH_BFD_RULE_COOKIE,
                ROUND_TRIP_LATENCY_RULE_COOKIE, VERIFICATION_UNICAST_VXLAN_RULE_COOKIE,
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE,
                LLDP_INGRESS_COOKIE, LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_VXLAN_COOKIE,
                LLDP_POST_INGRESS_ONE_SWITCH_COOKIE, ARP_INPUT_PRE_DROP_COOKIE, ARP_TRANSIT_COOKIE,
                ARP_INGRESS_COOKIE, ARP_POST_INGRESS_COOKIE, ARP_POST_INGRESS_VXLAN_COOKIE,
                ARP_POST_INGRESS_ONE_SWITCH_COOKIE, SERVER_42_OUTPUT_VLAN_COOKIE, SERVER_42_OUTPUT_VXLAN_COOKIE,
                SERVER_42_TURNING_COOKIE);
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
            for (int flowArpPort : flowArpPorts) {
                deletedRules.add(removeArpInputCustomerFlow(dpid, flowArpPort));
            }
            if (server42FlowRtt) {
                for (Integer port : server42FlowRttPorts) {
                    deletedRules.add(removeServer42InputFlow(dpid, port));
                }
            }
        }


        try {
            deleteMeter(dpid, createMeterIdForDefaultRule(VERIFICATION_BROADCAST_RULE_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(VERIFICATION_UNICAST_RULE_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(ARP_POST_INGRESS_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(ARP_POST_INGRESS_VXLAN_COOKIE).getValue());
            deleteMeter(dpid, createMeterIdForDefaultRule(ARP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue());

            if (switchLldp) {
                deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_INPUT_PRE_DROP_COOKIE).getValue());
                deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_TRANSIT_COOKIE).getValue());
                deleteMeter(dpid, createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE).getValue());
            }
            if (switchArp) {
                deleteMeter(dpid, createMeterIdForDefaultRule(ARP_INPUT_PRE_DROP_COOKIE).getValue());
                deleteMeter(dpid, createMeterIdForDefaultRule(ARP_TRANSIT_COOKIE).getValue());
                deleteMeter(dpid, createMeterIdForDefaultRule(ARP_INGRESS_COOKIE).getValue());
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
        return installDefaultFlow(dpid, switchFlowFactory.getUnicastVerificationVxlanFlowGenerator(),
                "--VerificationFlowVxlan--");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long installVerificationRule(final DatapathId dpid, final boolean isBroadcast)
            throws SwitchOperationException {
        String ruleName = (isBroadcast) ? "Broadcast" : "Unicast";
        String flowName = ruleName + "--VerificationFlow--";
        return installDefaultFlow(dpid, switchFlowFactory.getVerificationFlow(isBroadcast), flowName);
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
                        actionSetDstMac(sw, convertDpIdToMac(sw.getId())),
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
                && convertDpIdToMac(dpId).equals(((OFActionSetField) actions.get(0)).getField().getValue())
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

    @Override
    public List<OFGroupDescStatsEntry> dumpGroups(DatapathId dpid) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        return dumpGroups(sw);
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
                .collect(toList());
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
     * {@inheritDoc}
     */
    @Override
    public Long installDropFlow(final DatapathId dpid) throws SwitchOperationException {
        // TODO: leverage installDropFlowCustom
        return installDropFlowForTable(dpid, INPUT_TABLE_ID, DROP_RULE_COOKIE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long installDropFlowForTable(final DatapathId dpid, final int tableId,
                                        final long cookie) throws SwitchOperationException {
        // TODO: leverage installDropFlowCustom
        return installDefaultFlow(dpid, switchFlowFactory.getDropFlowGenerator(cookie, tableId), "--DropRule--");
    }

    @Override
    public Long installBfdCatchFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getBfdCatchFlowGenerator(), "--CatchBfdRule--");
    }

    @Override
    public Long installRoundTripLatencyFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getRoundTripLatencyFlowGenerator(),
                "--RoundTripLatencyRule--");
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
                .collect(toSet());

        return flowStatsBefore.stream()
                .map(entry -> entry.getCookie().getValue())
                .filter(cookie -> !cookiesAfter.contains(cookie))
                .peek(cookie -> logger.info("Rule with cookie {} has been removed from switch {}.", cookie, dpid))
                .collect(toList());
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
                .setExact(MatchField.ETH_DST, convertDpIdToMac(dpid))
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
        OFFlowMod flowMod = buildTransitIslVxlanRule(ofFactory, port);
        String flowName = "--Isl transit rule for VXLAN--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildTransitIslVxlanRule(OFFactory ofFactory, int port) {
        Match match = buildTransitIslVxlanRuleMatch(port, ofFactory);
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
        Match match = buildTransitIslVxlanRuleMatch(port, ofFactory);
        builder.setMatch(match);
        builder.setPriority(ISL_TRANSIT_VXLAN_RULE_PRIORITY_MULTITABLE);
        removeFlowByOfFlowDelete(dpid, INPUT_TABLE_ID, builder.build());
        return cookie;
    }

    private Match buildTransitIslVxlanRuleMatch(int port, OFFactory ofFactory) {
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
        OFFlowMod flowMod = buildEgressIslVlanRule(ofFactory, port);
        String flowName = "--Isl egress rule for VLAN--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    private OFFlowMod buildEgressIslVlanRule(OFFactory ofFactory, int port) {
        Match match = buildInPortMatch(port, ofFactory);
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(EGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeIslVlanEgress(port),
                ISL_EGRESS_VLAN_RULE_PRIORITY_MULTITABLE, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable)).build();
    }

    private OFFlowMod buildLldpTransitFlow(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
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

    private OFFlowMod buildLldpInputPreDropRule(IOFSwitch sw, OFInstructionMeter meter, List<OFAction> actionList) {
        OFFactory ofFactory = sw.getOFFactory();
        Match match = ofFactory.buildMatch()
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

    public Long installLldpTransitFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getLldpTransitFlowGenerator(),
                "--Isl LLDP transit rule for VLAN--");
    }

    @Override
    public Long installLldpInputPreDropFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getLldpInputPreDropFlowGenerator(),
                "--Isl LLDP input pre drop rule--");
    }

    @Override
    public Long installArpTransitFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getArpTransitFlowGenerator(),
                "--Isl ARP transit rule for VLAN--");
    }

    @Override
    public Long installArpInputPreDropFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getArpInputPreDropFlowGenerator(),
                "--Isl ARP input pre drop rule--");
    }

    @Override
    public Long installServer42InputFlow(DatapathId dpid, int server42Port, int customerPort,
                                         org.openkilda.model.MacAddress server42macAddress)
            throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getServer42InputFlowGenerator(
                server42Port, customerPort, server42macAddress), "--server 42 input rule--");
    }

    @Override
    public Long installServer42TurningFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getServer42TurningFlowGenerator(),
                "--server 42 turning rule--");
    }

    @Override
    public Long installServer42OutputVlanFlow(
            DatapathId dpid, int port, int vlan, org.openkilda.model.MacAddress macAddress)
            throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getServer42OutputVlanFlowGenerator(
                port, vlan, macAddress), "--server 42 output vlan rule--");
    }

    @Override
    public Long installServer42OutputVxlanFlow(
            DatapathId dpid, int port, int vlan, org.openkilda.model.MacAddress macAddress)
            throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getServer42OutputVxlanFlowGenerator(
                port, vlan, macAddress), "--server 42 output VXLAN rule--");
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
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));

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
    public Long removeArpInputCustomerFlow(DatapathId dpid, int port) throws SwitchOperationException {
        long cookie = Cookie.encodeArpInputCustomer(port);
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));

        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);

        builder.setMatch(buildInPortMatch(port, ofFactory));

        builder.setInstructions(ImmutableList.of(goToTable));
        builder.setPriority(ARP_INPUT_CUSTOMER_PRIORITY);

        removeFlowByOfFlowDelete(dpid, INPUT_TABLE_ID, builder.build());
        return cookie;
    }

    @Override
    public Long removeServer42InputFlow(DatapathId dpid, int port) throws SwitchOperationException {
        long cookie = Cookie.encodeServer42InputInput(port);
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();
        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));

        OFFlowDelete.Builder builder = ofFactory.buildFlowDelete();
        builder.setCookie(U64.of(cookie));
        builder.setCookieMask(U64.NO_MASK);

        builder.setInstructions(ImmutableList.of(goToTable));
        builder.setPriority(SERVER_42_INPUT_PRIORITY);

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
                .setExact(MatchField.ETH_TYPE, EthType.LLDP)
                .build();

        RoutingMetadata metadata = buildMetadata(RoutingMetadata.builder().lldpFlag(true), sw);
        OFInstructionWriteMetadata writeMetadata = ofFactory.instructions().buildWriteMetadata()
                .setMetadata(metadata.getValue())
                .setMetadataMask(metadata.getMask()).build();

        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeLldpInputCustomer(port),
                LLDP_INPUT_CUSTOMER_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable, writeMetadata)).build();
    }

    @Override
    public Long installLldpIngressFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getLldpIngressFlowGenerator(), "--LLDP ingress rule--");
    }

    @Override
    public Long installLldpPostIngressFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getLldpPostIngressFlowGenerator(),
                "--LLDP post ingress rule--");
    }

    @Override
    public Long installLldpPostIngressVxlanFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getLldpPostIngressVxlanFlowGenerator(),
                "--LLDP post ingress VXLAN rule--");
    }

    @Override
    public Long installLldpPostIngressOneSwitchFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getLldpPostIngressOneSwitchFlowGenerator(),
                "--LLDP post ingress one switch rule--");
    }

    @Override
    public Long installArpInputCustomerFlow(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFlowMod flowMod = buildArpInputCustomerFlow(dpid, port);
        String flowName = "--Customer Port ARP input rule--" + dpid.toString();
        pushFlow(sw, flowName, flowMod);
        return flowMod.getCookie().getValue();
    }

    @Override
    public OFFlowMod buildArpInputCustomerFlow(DatapathId dpid, int port) throws SwitchNotFoundException {
        IOFSwitch sw = lookupSwitch(dpid);
        OFFactory ofFactory = sw.getOFFactory();

        Match match = ofFactory.buildMatch()
                .setExact(MatchField.IN_PORT, OFPort.of(port))
                .setExact(MatchField.ETH_TYPE, EthType.ARP)
                .build();

        RoutingMetadata metadata = buildMetadata(RoutingMetadata.builder().arpFlag(true), sw);
        OFInstructionWriteMetadata writeMetadata = ofFactory.instructions().buildWriteMetadata()
                .setMetadata(metadata.getValue())
                .setMetadataMask(metadata.getMask())
                .build();

        OFInstructionGotoTable goToTable = ofFactory.instructions().gotoTable(TableId.of(PRE_INGRESS_TABLE_ID));
        return prepareFlowModBuilder(
                ofFactory, Cookie.encodeArpInputCustomer(port),
                ARP_INPUT_CUSTOMER_PRIORITY, INPUT_TABLE_ID)
                .setMatch(match)
                .setInstructions(ImmutableList.of(goToTable, writeMetadata)).build();
    }

    @Override
    public List<OFFlowMod> buildExpectedServer42Flows(
            DatapathId dpid, boolean server42FlowRttFeatureToggle, boolean server42FlowRttSwitchProperty,
            Integer server42Port, Integer server42Vlan, org.openkilda.model.MacAddress server42MacAddress,
            Set<Integer> customerPorts) throws SwitchNotFoundException {

        List<SwitchFlowGenerator> generators = new ArrayList<>();
        if (server42FlowRttFeatureToggle) {
            generators.add(switchFlowFactory.getServer42TurningFlowGenerator());

            if (server42FlowRttSwitchProperty) {
                for (Integer port : customerPorts) {
                    generators.add(switchFlowFactory.getServer42InputFlowGenerator(
                            server42Port, port, server42MacAddress));
                }
                generators.add(switchFlowFactory.getServer42OutputVlanFlowGenerator(
                        server42Port, server42Vlan, server42MacAddress));
                generators.add(switchFlowFactory.getServer42OutputVxlanFlowGenerator(
                        server42Port, server42Vlan, server42MacAddress));
            }
        }

        IOFSwitch sw = lookupSwitch(dpid);
        return generators.stream()
                .map(g -> g.generateFlow(sw))
                .map(SwitchFlowTuple::getFlow)
                .filter(Objects::nonNull)
                .collect(toList());
    }

    @Override
    public Long installArpIngressFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getArpIngressFlowGenerator(), "--ARP ingress rule--");
    }

    @Override
    public Long installArpPostIngressFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getArpPostIngressFlowGenerator(),
                "--ARP post ingress rule--");
    }

    @Override
    public Long installArpPostIngressVxlanFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getArpPostIngressVxlanFlowGenerator(),
                "--ARP post ingress VXLAN rule--");
    }

    @Override
    public Long installArpPostIngressOneSwitchFlow(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getArpPostIngressOneSwitchFlowGenerator(),
                "--ARP post ingress one switch rule--");
    }

    @Override
    public Long installPreIngressTablePassThroughDefaultRule(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getTablePassThroughDefaultFlowGenerator(
                MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, INGRESS_TABLE_ID, PRE_INGRESS_TABLE_ID),
                "--Pass Through Pre Ingress Default Rule--");
    }

    @Override
    public Long installEgressTablePassThroughDefaultRule(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getTablePassThroughDefaultFlowGenerator(
                MULTITABLE_EGRESS_PASS_THROUGH_COOKIE, TRANSIT_TABLE_ID, EGRESS_TABLE_ID),
                "--Pass Through Egress Default Rule--");
    }

    @Override
    public List<Long> installMultitableEndpointIslRules(DatapathId dpid, int port) throws SwitchOperationException {
        IOFSwitch sw = lookupSwitch(dpid);
        List<Long> installedRules = new ArrayList<>();
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_PUSH_POP_VXLAN)) {
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
        if (featureDetectorService.detectSwitch(sw).contains(NOVIFLOW_PUSH_POP_VXLAN)) {
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

    @Override
    public Long installDropLoopRule(DatapathId dpid) throws SwitchOperationException {
        return installDefaultFlow(dpid, switchFlowFactory.getDropLoopFlowGenerator(), "--DropLoopRule--");
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
            // Match either In Port or both Port & Vlan criteria.
            Match match = matchFlow(ofFactory, criteria.getInPort(),
                    Optional.ofNullable(criteria.getEncapsulationId()).orElse(0), criteria.getEncapsulationType());
            builder.setMatch(match);

        } else if (criteria.getEncapsulationId() != null) {
            // Match In Vlan criterion if In Port is not specified
            Match.Builder matchBuilder = ofFactory.buildMatch();
            MacAddress egressSwitchMac = criteria.getEgressSwitchId() != null
                    ? convertDpIdToMac(DatapathId.of(criteria.getEgressSwitchId().toLong()))
                    : null;
            switch (criteria.getEncapsulationType()) {
                case TRANSIT_VLAN:
                    matchVlan(ofFactory, matchBuilder, criteria.getEncapsulationId());
                    break;
                case VXLAN:
                    matchVxlan(ofFactory, matchBuilder, criteria.getEncapsulationId());
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
     * @return {@link Match}
     */
    private Match matchFlow(OFFactory ofFactory, int inputPort, int tunnelId, FlowEncapsulationType encapsulationType) {
        Match.Builder mb = ofFactory.buildMatch();
        addMatchFlowToBuilder(mb, ofFactory, inputPort, tunnelId, encapsulationType);
        return mb.build();
    }

    private void addMatchFlowToBuilder(Builder builder, OFFactory ofFactory, int inputPort, int tunnelId,
                                       FlowEncapsulationType encapsulationType) {
        builder.setExact(MatchField.IN_PORT, OFPort.of(inputPort));
        // NOTE: vlan of 0 means match on port on not VLAN.
        if (tunnelId > 0) {
            switch (encapsulationType) {
                case TRANSIT_VLAN:
                    matchVlan(ofFactory, builder, tunnelId);

                    break;
                case VXLAN:
                    matchVxlan(ofFactory, builder, tunnelId);
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

    private void matchVxlan(OFFactory ofFactory, Match.Builder matchBuilder, long tunnelId) {
        if (OF_12.compareTo(ofFactory.getVersion()) >= 0) {
            throw new UnsupportedOperationException("Switch doesn't support tunnel_id match");
        } else {
            matchBuilder.setExact(MatchField.TUNNEL_ID, U64.of(tunnelId));
        }
    }

    private Match matchServer42IngressFlow(
            IOFSwitch sw, org.openkilda.model.MacAddress server42MacAddress, int server42Port, int customerPort,
            int vlanId, boolean multiTable) {
        OFFactory ofFactory = sw.getOFFactory();
        Match.Builder builder = ofFactory.buildMatch();
        if (vlanId > 0) {
            matchVlan(ofFactory, builder, vlanId);
        }

        if (multiTable) {
            RoutingMetadata metadata = RoutingMetadata.builder()
                    .inputPort(customerPort)
                    .build(featureDetectorService.detectSwitch(sw));
            builder.setMasked(MatchField.METADATA,
                    OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask()));
        } else {
            int udpSrcPort = config.getServer42UdpPortOffset() + customerPort;

            builder.setExact(MatchField.ETH_SRC, MacAddress.of(server42MacAddress.toString()))
                    .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                    .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                    .setExact(MatchField.UDP_SRC, TransportPort.of(udpSrcPort));
        }
        builder.setExact(MatchField.IN_PORT, OFPort.of(server42Port));
        return builder.build();
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
                pushVlanTransitEncapsulation(ofFactory, transitTunnelId, outputVlanType, actionList);
                break;
            case VXLAN:
                actionList.add(actionPushVxlan(ofFactory, transitTunnelId, convertDpIdToMac(ethSrc),
                        convertDpIdToMac((ethDst)), STUB_VXLAN_UDP_SRC));
                actionList.add(actionVxlanEthSrcCopyField(ofFactory));
                break;
            default:
                throw new UnsupportedOperationException(
                        String.format("Unknown encapsulation type: %s", encapsulationType));
        }

        return actionList;
    }

    /**
     * Create transit encapsulation OFActions for server 42 ingress Flow.
     */
    private List<OFAction> pushTransitEncapsulationForServer42IngressFlow(
            OFFactory of, int transitTunnelId, OutputVlanType outputVlanType, FlowEncapsulationType encapsulationType,
            DatapathId ethSrc, DatapathId ethDst, Set<SwitchFeature> features, boolean multiTable) {
        List<OFAction> actions = new ArrayList<>();
        switch (encapsulationType) {
            case TRANSIT_VLAN:
                actions.add(of.actions().setField(of.oxms().ethSrc(MacAddress.of(ethSrc))));
                actions.add(of.actions().setField(of.oxms().ethDst(MacAddress.of(ethDst))));

                if (!multiTable) {
                    actions.add(of.actions().setField(of.oxms().udpSrc(TransportPort.of(SERVER_42_FORWARD_UDP_PORT))));
                    actions.add(of.actions().setField(of.oxms().udpDst(TransportPort.of(SERVER_42_FORWARD_UDP_PORT))));
                    if (features.contains(NOVIFLOW_COPY_FIELD)) {
                        actions.add(buildServer42CopyFirstTimestamp(of));
                    }
                }
                pushVlanTransitEncapsulation(of, transitTunnelId, outputVlanType, actions);
                break;
            case VXLAN:
                if (OutputVlanType.PUSH.equals(outputVlanType) || OutputVlanType.NONE.equals(outputVlanType)) {
                    // Server 42 requires constant number of Vlans in packet.
                    // TRANSIT_VLAN encapsulation always keeps 1 Vlan in packet.
                    // VXLAN encapsulation for NON default port also keeps 1 Vlan in packet.
                    // VXLAN encapsulation for default port has no Vlans in packet so we will add one fake Vlan
                    actions.add(actionPushVlan(of, ETH_TYPE));
                }
                if (!multiTable && features.contains(NOVIFLOW_COPY_FIELD)) {
                    actions.add(buildServer42CopyFirstTimestamp(of));
                }
                actions.add(actionPushVxlan(of, transitTunnelId, convertDpIdToMac(ethSrc),
                        convertDpIdToMac(ethDst), SERVER_42_FORWARD_UDP_PORT));
                break;
            default:
                throw new UnsupportedOperationException(
                        String.format("Unknown encapsulation type: %s", encapsulationType));
        }
        return actions;
    }

    private void pushVlanTransitEncapsulation(
            OFFactory ofFactory, int transitTunnelId, OutputVlanType outputVlanType, List<OFAction> actionList) {
        if (OutputVlanType.PUSH.equals(outputVlanType) || OutputVlanType.NONE.equals(outputVlanType)) {
            actionList.add(actionPushVlan(ofFactory, ETH_TYPE));
        }
        actionList.add(actionReplaceVlan(ofFactory, transitTunnelId));
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

    private OFAction actionPushVxlan(
            OFFactory ofFactory, long tunnelId, MacAddress ethSrc, MacAddress ethDst, int udpSrc) {
        OFActions actions = ofFactory.actions();
        return actions.buildNoviflowPushVxlanTunnel()
                .setVni(tunnelId)
                .setEthSrc(ethSrc)
                .setEthDst(ethDst)
                .setUdpSrc(udpSrc)
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
                .collect(toList());
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
                .collect(toList());
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
    public SwitchManagerConfig getSwitchManagerConfig() {
        return config;
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


    private OFMeterConfig getMeter(DatapathId dpid, long meter) throws SwitchOperationException {
        return dumpMeters(dpid).stream()
                .filter(meterConfig -> meterConfig.getMeterId() == meter)
                .findFirst()
                .orElse(null);
    }

    private int getFlowPriority(int inputVlanId) {
        return inputVlanId == 0 ? DEFAULT_FLOW_PRIORITY : FLOW_PRIORITY;
    }

    private Long installDefaultFlow(DatapathId dpid, SwitchFlowGenerator flowGeneratorSupplier,
                                    String flowDescription)
            throws SwitchNotFoundException, OfInstallException {
        IOFSwitch sw = lookupSwitch(dpid);

        SwitchFlowTuple switchFlowTuple = flowGeneratorSupplier.generateFlow(sw);
        if (switchFlowTuple.getFlow() == null) {
            logger.debug("Skip installation of {} rule for switch {}", flowDescription, dpid);
            return null;
        } else {
            String flowName = flowDescription + dpid.toString();
            installSwitchFlowTuple(switchFlowTuple, flowName);
            return switchFlowTuple.getFlow().getCookie().getValue();
        }
    }

    private void installSwitchFlowTuple(SwitchFlowTuple switchFlowTuple, String flowName) throws OfInstallException {
        IOFSwitch sw = switchFlowTuple.getSw();
        if (switchFlowTuple.getMeter() != null) {
            processMeter(sw, switchFlowTuple.getMeter());
        }
        if (switchFlowTuple.getGroup() != null) {
            processGroup(sw, switchFlowTuple.getGroup());
        }
        pushFlow(sw, flowName, switchFlowTuple.getFlow());
    }

    @VisibleForTesting
    void processMeter(IOFSwitch sw, OFMeterMod meterMod) {
        long meterId = meterMod.getMeterId();
        OFMeterConfig meterConfig;
        try {
            meterConfig = getMeter(sw.getId(), meterId);
        } catch (SwitchOperationException e) {
            logger.warn("Meter {} won't be installed on the switch {}: {}", meterId, sw.getId(), e.getMessage());
            return;
        }

        OFMeterBandDrop meterBandDrop = Optional.ofNullable(meterConfig)
                .map(OFMeterConfig::getEntries)
                .flatMap(entries -> entries.stream().findFirst())
                .map(OFMeterBandDrop.class::cast)
                .orElse(null);

        try {
            OFMeterBandDrop ofMeterBandDrop = sw.getOFFactory().getVersion().compareTo(OF_13) > 0
                    ? (OFMeterBandDrop) meterMod.getBands().get(0) : (OFMeterBandDrop) meterMod.getMeters().get(0);
            long rate = ofMeterBandDrop.getRate();
            Set<OFMeterFlags> flags = meterMod.getFlags();

            if (meterBandDrop != null && meterBandDrop.getRate() == rate
                    && CollectionUtils.isEqualCollection(meterConfig.getFlags(), flags)) {
                logger.debug("Meter {} won't be reinstalled on switch {}. It already exists", meterId, sw.getId());
                return;
            }

            if (meterBandDrop != null) {
                logger.info("Meter {} with origin rate {} will be reinstalled on {} switch.",
                        meterId, sw.getId(), meterBandDrop.getRate());
                buildAndDeleteMeter(sw, sw.getId(), meterId);
                sendBarrierRequest(sw);
            }

            installMeterMod(sw, meterMod);
        } catch (SwitchOperationException e) {
            logger.warn("Failed to (re)install meter {} on switch {}: {}", meterId, sw.getId(), e.getMessage());
        }
    }

    private void installMeterMod(IOFSwitch sw, OFMeterMod meterMod) throws OfInstallException {
        logger.info("Installing meter {} on switch {}", meterMod.getMeterId(), sw.getId());

        pushFlow(sw, "--InstallMeter--", meterMod);

        // All cases when we're installing meters require that we wait until the command is processed and
        // the meter is installed.
        sendBarrierRequest(sw);
    }

    private void processGroup(IOFSwitch sw, OFGroupAdd groupAdd) {
        try {
            installRoundTripLatencyGroup(sw, groupAdd);
            logger.debug("Round trip latency group was installed on switch {}", sw.getId());
        } catch (OfInstallException | UnsupportedOperationException e) {
            String message = String.format(
                    "Couldn't install round trip latency group on switch %s. "
                            + "Standard discovery actions will be installed instead. Error: %s", sw.getId(),
                    e.getMessage());
            logger.warn(message, e);
        }
    }

    private void installRoundTripLatencyGroup(IOFSwitch sw, OFGroupAdd groupAdd) throws OfInstallException {
        Optional<OFGroupDescStatsEntry> groupDesc = getGroup(sw, ROUND_TRIP_LATENCY_GROUP_ID);

        if (groupDesc.isPresent()) {
            if (validateRoundTripLatencyGroup(sw.getId(), groupDesc.get())) {
                logger.debug("Skip installation of round trip latency group on switch {}. Group exists.", sw.getId());
                return;
            } else {
                logger.debug("Found invalid round trip latency group on switch {}. Need to be deleted.", sw.getId());
                deleteGroup(sw, ROUND_TRIP_LATENCY_GROUP_ID);
            }
        }

        pushFlow(sw, "--InstallGroup--", groupAdd);
        sendBarrierRequest(sw);
    }

    private RoutingMetadata buildMetadata(RoutingMetadata.RoutingMetadataBuilder builder, IOFSwitch sw) {
        Set<SwitchFeature> features = featureDetectorService.detectSwitch(sw);
        return builder.build(features);
    }
}
