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

package org.openkilda.floodlight.kafka;

import static java.lang.String.format;
import static org.openkilda.floodlight.kafka.ErrorMessageBuilder.anError;
import static org.openkilda.floodlight.switchmanager.SwitchManager.INGRESS_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.POST_INGRESS_TABLE_ID;
import static org.openkilda.floodlight.switchmanager.SwitchManager.TRANSIT_TABLE_ID;
import static org.openkilda.messaging.Utils.MAPPER;
import static org.openkilda.model.Cookie.ARP_INGRESS_COOKIE;
import static org.openkilda.model.Cookie.ARP_INPUT_PRE_DROP_COOKIE;
import static org.openkilda.model.Cookie.ARP_POST_INGRESS_COOKIE;
import static org.openkilda.model.Cookie.ARP_POST_INGRESS_ONE_SWITCH_COOKIE;
import static org.openkilda.model.Cookie.ARP_POST_INGRESS_VXLAN_COOKIE;
import static org.openkilda.model.Cookie.ARP_TRANSIT_COOKIE;
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
import static org.openkilda.model.Cookie.SERVER_42_OUTPUT_VLAN_COOKIE;
import static org.openkilda.model.Cookie.SERVER_42_OUTPUT_VXLAN_COOKIE;
import static org.openkilda.model.Cookie.SERVER_42_TURNING_COOKIE;
import static org.openkilda.model.Cookie.VERIFICATION_BROADCAST_RULE_COOKIE;
import static org.openkilda.model.Cookie.VERIFICATION_UNICAST_RULE_COOKIE;
import static org.openkilda.model.Cookie.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE;

import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.command.SpeakerCommand;
import org.openkilda.floodlight.command.SpeakerCommandReport;
import org.openkilda.floodlight.converter.OfFlowStatsMapper;
import org.openkilda.floodlight.converter.OfMeterConverter;
import org.openkilda.floodlight.converter.OfPortDescConverter;
import org.openkilda.floodlight.error.FlowCommandException;
import org.openkilda.floodlight.error.SwitchNotFoundException;
import org.openkilda.floodlight.error.SwitchOperationException;
import org.openkilda.floodlight.error.UnsupportedSwitchOperationException;
import org.openkilda.floodlight.kafka.dispatcher.CommandDispatcher;
import org.openkilda.floodlight.kafka.dispatcher.ListSwitchDispatcher;
import org.openkilda.floodlight.kafka.dispatcher.PingRequestDispatcher;
import org.openkilda.floodlight.kafka.dispatcher.RemoveBfdSessionDispatcher;
import org.openkilda.floodlight.kafka.dispatcher.SetupBfdSessionDispatcher;
import org.openkilda.floodlight.kafka.dispatcher.StatsRequestDispatcher;
import org.openkilda.floodlight.service.CommandProcessorService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.switchmanager.ISwitchManager;
import org.openkilda.floodlight.switchmanager.SwitchTrackingService;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.floodlight.utils.CorrelationContext.CorrelationContextClosable;
import org.openkilda.messaging.AliveRequest;
import org.openkilda.messaging.AliveResponse;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.command.discovery.DiscoverPathCommandData;
import org.openkilda.messaging.command.discovery.NetworkCommandData;
import org.openkilda.messaging.command.discovery.PortsCommandData;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.DeleteMeterRequest;
import org.openkilda.messaging.command.flow.InstallEgressFlow;
import org.openkilda.messaging.command.flow.InstallFlowForSwitchManagerRequest;
import org.openkilda.messaging.command.flow.InstallIngressFlow;
import org.openkilda.messaging.command.flow.InstallOneSwitchFlow;
import org.openkilda.messaging.command.flow.InstallServer42Flow;
import org.openkilda.messaging.command.flow.InstallTransitFlow;
import org.openkilda.messaging.command.flow.MeterModifyCommandRequest;
import org.openkilda.messaging.command.flow.ReinstallDefaultFlowForSwitchManagerRequest;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.flow.RemoveFlowForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.ConnectModeRequest;
import org.openkilda.messaging.command.switches.DeleteRulesAction;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.command.switches.DeleterMeterForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.DumpMetersForNbworkerRequest;
import org.openkilda.messaging.command.switches.DumpMetersForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.DumpMetersRequest;
import org.openkilda.messaging.command.switches.DumpPortDescriptionRequest;
import org.openkilda.messaging.command.switches.DumpRulesForNbworkerRequest;
import org.openkilda.messaging.command.switches.DumpRulesForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.DumpRulesRequest;
import org.openkilda.messaging.command.switches.DumpSwitchPortsDescriptionRequest;
import org.openkilda.messaging.command.switches.GetExpectedDefaultMetersRequest;
import org.openkilda.messaging.command.switches.GetExpectedDefaultRulesRequest;
import org.openkilda.messaging.command.switches.InstallRulesAction;
import org.openkilda.messaging.command.switches.PortConfigurationRequest;
import org.openkilda.messaging.command.switches.SwitchRulesDeleteRequest;
import org.openkilda.messaging.command.switches.SwitchRulesInstallRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.rule.FlowCommandErrorData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.DiscoPacketSendingConfirmation;
import org.openkilda.messaging.info.discovery.InstallIslDefaultRulesResult;
import org.openkilda.messaging.info.discovery.RemoveIslDefaultRulesResult;
import org.openkilda.messaging.info.flow.FlowInstallResponse;
import org.openkilda.messaging.info.flow.FlowReinstallResponse;
import org.openkilda.messaging.info.flow.FlowRemoveResponse;
import org.openkilda.messaging.info.meter.MeterEntry;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.meter.SwitchMeterUnsupported;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultFlowEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.info.stats.PortStatusData;
import org.openkilda.messaging.info.stats.SwitchPortStatusData;
import org.openkilda.messaging.info.switches.ConnectModeResponse;
import org.openkilda.messaging.info.switches.DeleteMeterResponse;
import org.openkilda.messaging.info.switches.PortConfigurationResponse;
import org.openkilda.messaging.info.switches.PortDescription;
import org.openkilda.messaging.info.switches.SwitchPortsDescription;
import org.openkilda.messaging.info.switches.SwitchRulesResponse;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.payload.switches.InstallIslDefaultRulesCommand;
import org.openkilda.messaging.payload.switches.RemoveIslDefaultRulesCommand;
import org.openkilda.model.Cookie;
import org.openkilda.model.MacAddress;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.PortStatus;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.ImmutableList;
import net.floodlightcontroller.core.IOFSwitch;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFMeterConfig;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

class RecordHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RecordHandler.class);

    private final ConsumerContext context;
    private final List<CommandDispatcher<?>> dispatchers;
    private final ConsumerRecord<String, String> record;

    private final CommandProcessorService commandProcessor;

    public RecordHandler(ConsumerContext context, List<CommandDispatcher<?>> dispatchers,
                         ConsumerRecord<String, String> record) {
        this.context = context;
        this.dispatchers = dispatchers;
        this.record = record;

        this.commandProcessor = context.getModuleContext().getServiceImpl(CommandProcessorService.class);
    }

    protected void doControllerMsg(CommandMessage message) {
        // Define the destination topic where the reply will be sent to.
        final String replyToTopic = context.getKafkaFlowTopic();
        final Destination replyDestination = getDestinationForTopic(replyToTopic);

        try {
            handleCommand(message, replyToTopic, replyDestination);
        } catch (FlowCommandException e) {
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            logger.error("Failed to handle message {}: {}", message, errorMessage);
            ErrorData errorData = new FlowCommandErrorData(e.getFlowId(), e.getCookie(), e.getTransactionId(),
                    e.getErrorType(), errorMessage, e.getMessage());
            ErrorMessage error = new ErrorMessage(errorData, System.currentTimeMillis(),
                    message.getCorrelationId(), replyDestination);
            getKafkaProducer().sendMessageAndTrack(replyToTopic, error);
        }
    }

    private void handleCommand(CommandMessage message, String replyToTopic, Destination replyDestination)
            throws FlowCommandException {
        logger.debug("Handling message: '{}'. Reply topic: '{}'. Reply destination: '{}'.",
                message, replyToTopic, replyDestination);
        CommandData data = message.getData();

        if (data instanceof DiscoverIslCommandData) {
            doDiscoverIslCommand(message);
        } else if (data instanceof DiscoverPathCommandData) {
            doDiscoverPathCommand(data);
        } else if (data instanceof InstallIngressFlow) {
            doProcessIngressFlow(message, replyToTopic, replyDestination);
        } else if (data instanceof InstallEgressFlow) {
            doProcessEgressFlow(message, replyToTopic, replyDestination);
        } else if (data instanceof InstallTransitFlow) {
            doProcessTransitFlow(message, replyToTopic, replyDestination);
        } else if (data instanceof InstallOneSwitchFlow) {
            doProcessOneSwitchFlow(message, replyToTopic, replyDestination);
        } else if (data instanceof RemoveFlow) {
            doDeleteFlow(message, replyToTopic, replyDestination);
        } else if (data instanceof RemoveFlowForSwitchManagerRequest) {
            doDeleteFlowForSwitchManager(message);
        } else if (data instanceof ReinstallDefaultFlowForSwitchManagerRequest) {
            doReinstallDefaultFlowForSwitchManager(message);
        } else if (data instanceof NetworkCommandData) {
            doNetworkDump(message);
        } else if (data instanceof SwitchRulesDeleteRequest) {
            doDeleteSwitchRules(message);
        } else if (data instanceof SwitchRulesInstallRequest) {
            doInstallSwitchRules(message);
        } else if (data instanceof ConnectModeRequest) {
            doConnectMode(message);
        } else if (data instanceof DumpRulesForNbworkerRequest) {
            doDumpRulesForNbworkerRequest(message);
        } else if (data instanceof GetExpectedDefaultRulesRequest) {
            doGetExpectedDefaultRulesRequest(message);
        } else if (data instanceof GetExpectedDefaultMetersRequest) {
            doGetExpectedDefaultMetersRequest(message);
        } else if (data instanceof DumpRulesRequest) {
            doDumpRulesRequest(message);
        } else if (data instanceof DumpRulesForSwitchManagerRequest) {
            doDumpRulesForSwitchManagerRequest(message);
        } else if (data instanceof InstallFlowForSwitchManagerRequest) {
            doInstallFlowForSwitchManager(message);
        } else if (data instanceof PortsCommandData) {
            doPortsCommandDataRequest(message);
        } else if (data instanceof DeleterMeterForSwitchManagerRequest) {
            doDeleteMeter(message, context.getKafkaSwitchManagerTopic());
        } else if (data instanceof DeleteMeterRequest) {
            doDeleteMeter(message, context.getKafkaNorthboundTopic());
        } else if (data instanceof PortConfigurationRequest) {
            doConfigurePort(message);
        } else if (data instanceof DumpSwitchPortsDescriptionRequest) {
            doDumpSwitchPortsDescriptionRequest(message);
        } else if (data instanceof DumpPortDescriptionRequest) {
            doDumpPortDescriptionRequest(message);
        } else if (data instanceof DumpMetersRequest) {
            doDumpMetersRequest(message);
        } else if (data instanceof DumpMetersForSwitchManagerRequest) {
            doDumpMetersForSwitchManagerRequest(message);
        } else if (data instanceof DumpMetersForNbworkerRequest) {
            doDumpMetersForNbworkerRequest(message);
        } else if (data instanceof MeterModifyCommandRequest) {
            doModifyMeterRequest(message);
        } else if (data instanceof AliveRequest) {
            doAliveRequest(message);
        } else if (data instanceof InstallIslDefaultRulesCommand) {
            doInstallIslDefaultRule(message);
        } else if (data instanceof RemoveIslDefaultRulesCommand) {
            doRemoveIslDefaultRule(message);
        } else {
            logger.error("Unable to handle '{}' request - handler not found.", data);
        }
    }

    private Destination getDestinationForTopic(String replyToTopic) {
        //TODO: depending on the future system design, either get rid of destination or complete the switch-case.
        if (context.getKafkaNorthboundTopic().equals(replyToTopic)) {
            return Destination.NORTHBOUND;
        } else {
            return Destination.WFM_TRANSACTION;
        }
    }

    private void doAliveRequest(CommandMessage message) {
        // TODO(tdurakov): return logic for failed amount counter
        int totalFailedAmount = getKafkaProducer().getFailedSendMessageCounter();
        getKafkaProducer().sendMessageAndTrack(context.getKafkaTopoDiscoTopic(),
                new InfoMessage(new AliveResponse(context.getRegion(), totalFailedAmount), System.currentTimeMillis(),
                        message.getCorrelationId(), context.getRegion()));
    }

    private void doInstallIslDefaultRule(CommandMessage message) {
        InstallIslDefaultRulesCommand toSetup = (InstallIslDefaultRulesCommand) message.getData();
        InstallIslDefaultRulesResult result = new InstallIslDefaultRulesResult(toSetup.getSrcSwitch(),
                toSetup.getSrcPort(), toSetup.getDstSwitch(), toSetup.getDstPort(), true);
        try {
            context.getSwitchManager().installMultitableEndpointIslRules(DatapathId.of(toSetup.getSrcSwitch().toLong()),
                    toSetup.getSrcPort());
        } catch (SwitchOperationException e) {
            logger.error("Failed to install isl rules for switch: '{}'", toSetup.getSrcSwitch(), e);
            result.setSuccess(false);
        }

        getKafkaProducer().sendMessageAndTrack(context.getKafkaFlowTopic(), record.key(), new InfoMessage(result,
                System.currentTimeMillis(), message.getCorrelationId(), context.getRegion()));
    }

    private void doRemoveIslDefaultRule(CommandMessage message) {
        RemoveIslDefaultRulesCommand toRemove = (RemoveIslDefaultRulesCommand) message.getData();
        RemoveIslDefaultRulesResult result = new RemoveIslDefaultRulesResult(toRemove.getSrcSwitch(),
                toRemove.getSrcPort(), toRemove.getDstSwitch(), toRemove.getDstPort(), true);
        try {
            context.getSwitchManager().removeMultitableEndpointIslRules(DatapathId.of(toRemove.getSrcSwitch().toLong()),
                    toRemove.getSrcPort());
        } catch (SwitchOperationException e) {
            logger.error("Failed to remove isl rules for switch: '{}'", toRemove.getSrcSwitch(), e);
            result.setSuccess(false);
        }

        getKafkaProducer().sendMessageAndTrack(context.getKafkaFlowTopic(), record.key(), new InfoMessage(result,
                System.currentTimeMillis(), message.getCorrelationId(), context.getRegion()));
    }

    private void doDiscoverIslCommand(CommandMessage message) {
        DiscoverIslCommandData command = (DiscoverIslCommandData) message.getData();
        SwitchId switchId = command.getSwitchId();
        context.getPathVerificationService().sendDiscoveryMessage(
                DatapathId.of(switchId.toLong()), OFPort.of(command.getPortNumber()), command.getPacketId());

        DiscoPacketSendingConfirmation confirmation = new DiscoPacketSendingConfirmation(
                new NetworkEndpoint(command.getSwitchId(), command.getPortNumber()), command.getPacketId());
        getKafkaProducer().sendMessageAndTrack(context.getKafkaTopoDiscoTopic(), command.getSwitchId().toString(),
                new InfoMessage(confirmation, System.currentTimeMillis(), message.getCorrelationId(),
                        context.getRegion()));
    }

    private void doDiscoverPathCommand(CommandData data) {
        DiscoverPathCommandData command = (DiscoverPathCommandData) data;
        logger.warn("NOT IMPLEMENTED: sending discover Path to {}", command);
    }

    /**
     * Processes install ingress flow message.
     *
     * @param message command message for flow installation
     */
    private void doProcessIngressFlow(final CommandMessage message, String replyToTopic, Destination replyDestination)
            throws FlowCommandException {
        InstallIngressFlow command = (InstallIngressFlow) message.getData();
        logger.info("Installing ingress flow '{}' on switch '{}'", command.getId(), command.getSwitchId());

        try {
            installIngressFlow(command);
            message.setDestination(replyDestination);
            getKafkaProducer().sendMessageAndTrack(replyToTopic, message);
        } catch (SwitchOperationException e) {
            throw new FlowCommandException(command.getId(), command.getCookie(), command.getTransactionId(),
                    ErrorType.CREATION_FAILURE, e);
        }
    }

    /**
     * Installs ingress flow on the switch.
     *
     * @param command command message for flow installation
     */
    private void installIngressFlow(final InstallIngressFlow command) throws SwitchOperationException {
        logger.debug("Creating an ingress flow: {}", command);

        long meterId = 0;
        if (command.getMeterId() != null && command.getMeterId() > 0) {
            meterId = command.getMeterId();

            installMeter(DatapathId.of(command.getSwitchId().toLong()), meterId, command.getBandwidth(),
                    command.getId());
        } else {
            logger.debug("Installing unmetered ingress flow. Switch: {}, cookie: {}",
                    command.getSwitchId(), command.getCookie());
        }
        DatapathId dpid = DatapathId.of(command.getSwitchId().toLong());
        if (command.isMultiTable()) {
            context.getSwitchManager().installIntermediateIngressRule(dpid, command.getInputPort());
        }
        if (command.isEnableLldp()) {
            context.getSwitchManager().installLldpInputCustomerFlow(dpid, command.getInputPort());
        }
        if (command.isEnableArp()) {
            context.getSwitchManager().installArpInputCustomerFlow(dpid, command.getInputPort());
        }
        context.getSwitchManager().installIngressFlow(
                dpid,
                DatapathId.of(command.getEgressSwitchId().toLong()),
                command.getId(),
                command.getCookie(),
                command.getInputPort(),
                command.getOutputPort(),
                command.getInputVlanId(),
                command.getTransitEncapsulationId(),
                command.getOutputVlanType(),
                meterId,
                command.getTransitEncapsulationType(),
                command.isMultiTable());
    }

    /**
     * Processes egress flow install message.
     *
     * @param message command message for flow installation
     */
    private void doProcessEgressFlow(final CommandMessage message, String replyToTopic, Destination replyDestination)
            throws FlowCommandException {
        InstallEgressFlow command = (InstallEgressFlow) message.getData();
        logger.info("Installing egress flow '{}' on switch '{}'", command.getId(), command.getSwitchId());

        try {
            installEgressFlow(command);
            message.setDestination(replyDestination);
            getKafkaProducer().sendMessageAndTrack(replyToTopic, message);
        } catch (SwitchOperationException e) {
            throw new FlowCommandException(command.getId(), command.getCookie(), command.getTransactionId(),
                    ErrorType.CREATION_FAILURE, e);
        }
    }

    /**
     * Installs egress flow on the switch.
     *
     * @param command command message for flow installation
     */
    private void installEgressFlow(InstallEgressFlow command) throws SwitchOperationException {
        logger.debug("Creating an egress flow: {}", command);

        context.getSwitchManager().installEgressFlow(
                DatapathId.of(command.getSwitchId().toLong()),
                command.getId(),
                command.getCookie(),
                command.getInputPort(),
                command.getOutputPort(),
                command.getTransitEncapsulationId(),
                command.getOutputVlanId(),
                command.getOutputVlanType(),
                command.getTransitEncapsulationType(),
                command.isMultiTable());
    }

    /**
     * Processes transit flow installing message.
     *
     * @param message command message for flow installation
     */
    private void doProcessTransitFlow(final CommandMessage message, String replyToTopic, Destination replyDestination)
            throws FlowCommandException {
        InstallTransitFlow command = (InstallTransitFlow) message.getData();
        logger.info("Installing transit flow '{}' on switch '{}'", command.getId(), command.getSwitchId());

        try {
            installTransitFlow(command);
            message.setDestination(replyDestination);
            getKafkaProducer().sendMessageAndTrack(replyToTopic, message);
        } catch (SwitchOperationException e) {
            throw new FlowCommandException(command.getId(), command.getCookie(), command.getTransactionId(),
                    ErrorType.CREATION_FAILURE, e);
        }
    }

    /**
     * Installs transit flow on the switch.
     *
     * @param command command message for flow installation
     */
    private void installTransitFlow(final InstallTransitFlow command) throws SwitchOperationException {
        logger.debug("Creating a transit flow: {}", command);

        context.getSwitchManager().installTransitFlow(
                DatapathId.of(command.getSwitchId().toLong()),
                command.getId(),
                command.getCookie(),
                command.getInputPort(),
                command.getOutputPort(),
                command.getTransitEncapsulationId(),
                command.getTransitEncapsulationType(),
                command.isMultiTable());
    }

    /**
     * Processes one-switch flow installing message.
     *
     * @param message command message for flow installation
     */
    private void doProcessOneSwitchFlow(final CommandMessage message, String replyToTopic, Destination replyDestination)
            throws FlowCommandException {
        InstallOneSwitchFlow command = (InstallOneSwitchFlow) message.getData();
        logger.info("Installing one switch flow '{}' on switch '{}'", command.getId(), command.getSwitchId());

        try {
            installOneSwitchFlow(command);
            message.setDestination(replyDestination);
            getKafkaProducer().sendMessageAndTrack(replyToTopic, message);
        } catch (SwitchOperationException e) {
            throw new FlowCommandException(command.getId(), command.getCookie(), command.getTransactionId(),
                    ErrorType.CREATION_FAILURE, e);
        }
    }

    /**
     * Installs flow through one switch.
     *
     * @param command command message for flow installation
     */
    private void installOneSwitchFlow(InstallOneSwitchFlow command) throws SwitchOperationException {
        long meterId = 0;
        if (command.getMeterId() != null && command.getMeterId() > 0) {
            meterId = command.getMeterId();
            installMeter(DatapathId.of(command.getSwitchId().toLong()), meterId, command.getBandwidth(),
                    command.getId());
        } else {
            logger.debug("Installing unmetered one switch flow. Switch: {}, cookie: {}",
                    command.getSwitchId(), command.getCookie());

        }
        DatapathId dpid = DatapathId.of(command.getSwitchId().toLong());
        if (command.isMultiTable()) {
            context.getSwitchManager().installIntermediateIngressRule(dpid, command.getInputPort());
        }
        if (command.isEnableLldp()) {
            context.getSwitchManager().installLldpInputCustomerFlow(dpid, command.getInputPort());
        }
        if (command.isEnableArp()) {
            context.getSwitchManager().installArpInputCustomerFlow(dpid, command.getInputPort());
        }

        OutputVlanType directOutputVlanType = command.getOutputVlanType();
        context.getSwitchManager().installOneSwitchFlow(
                dpid,
                command.getId(),
                command.getCookie(),
                command.getInputPort(),
                command.getOutputPort(),
                command.getInputVlanId(),
                command.getOutputVlanId(),
                directOutputVlanType,
                meterId,
                command.isMultiTable());

    }

    /**
     * Removes flow.
     *
     * @param message command message for flow deletion
     */
    private void doDeleteFlow(final CommandMessage message, String replyToTopic, Destination replyDestination)
            throws FlowCommandException {
        RemoveFlow command = (RemoveFlow) message.getData();
        DatapathId dpid = DatapathId.of(command.getSwitchId().toLong());

        try {
            processDeleteFlow(command, dpid);
        } catch (SwitchOperationException e) {
            throw new FlowCommandException(command.getId(), command.getCookie(), command.getTransactionId(),
                    ErrorType.DELETION_FAILURE, e);
        }

        message.setDestination(replyDestination);
        getKafkaProducer().sendMessageAndTrack(replyToTopic, message);
    }

    /**
     * Removes flow.
     *
     * @param message command message for flow deletion
     */
    private void doDeleteFlowForSwitchManager(final CommandMessage message) {
        RemoveFlowForSwitchManagerRequest request = (RemoveFlowForSwitchManagerRequest) message.getData();
        IKafkaProducerService producerService = getKafkaProducer();
        String replyToTopic = context.getKafkaSwitchManagerTopic();
        DatapathId dpid = DatapathId.of(request.getSwitchId().toLong());

        try {
            processDeleteFlow(request.getFlowCommand(), dpid);

            InfoMessage response = new InfoMessage(new FlowRemoveResponse(), System.currentTimeMillis(),
                    message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), response);

        } catch (SwitchOperationException e) {
            logger.error("Failed to process switch rule deletion for switch: '{}'", request.getSwitchId(), e);
            anError(ErrorType.DELETION_FAILURE)
                    .withMessage(e.getMessage())
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    /**
     * Reinstall default flow.
     *
     * @param message command message for flow deletion
     */
    private void doReinstallDefaultFlowForSwitchManager(CommandMessage message) {
        ReinstallDefaultFlowForSwitchManagerRequest request =
                (ReinstallDefaultFlowForSwitchManagerRequest) message.getData();
        IKafkaProducerService producerService = getKafkaProducer();
        String replyToTopic = context.getKafkaSwitchManagerTopic();

        long cookie = request.getCookie();

        if (!Cookie.isDefaultRule(cookie)) {
            logger.warn("Failed to reinstall default switch rule for switch: '{}'. Rule {} is not default.",
                    request.getSwitchId(), Long.toHexString(cookie));
            anError(ErrorType.DATA_INVALID)
                    .withMessage(format("Failed to reinstall default switch rule for switch %s. Rule %s is not default",
                            request.getSwitchId(), Long.toHexString(cookie)))
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }

        SwitchId switchId = request.getSwitchId();
        DatapathId dpid = DatapathId.of(switchId.toLong());
        try {
            RemoveFlow command = RemoveFlow.builder()
                    .flowId("REMOVE_DEFAULT_FLOW")
                    .cookie(cookie)
                    .switchId(switchId)
                    .build();
            Set<Long> removedFlows = new HashSet<>(processDeleteFlow(command, dpid));

            for (Long removedFlow : removedFlows) {
                Long installedFlow = processInstallDefaultFlowByCookie(switchId, removedFlow);

                InfoMessage response = new InfoMessage(new FlowReinstallResponse(removedFlow, installedFlow),
                        System.currentTimeMillis(), message.getCorrelationId());
                producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), response);
            }

        } catch (SwitchOperationException e) {
            logger.error("Failed to reinstall switch rule for switch: '{}'", request.getSwitchId(), e);
            anError(ErrorType.INTERNAL_ERROR)
                    .withMessage(e.getMessage())
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }

    }

    private void processInstallServer42Rule(InstallServer42Flow command) throws SwitchOperationException {
        ISwitchManager switchManager = context.getSwitchManager();
        DatapathId dpid = DatapathId.of(command.getSwitchId().toLong());
        long cookie = command.getCookie();

        if (cookie == SERVER_42_OUTPUT_VLAN_COOKIE) {
            switchManager.installServer42OutputVlanFlow(
                    dpid, command.getOutputPort(), command.getServer42MacAddress());
        } else if (cookie == SERVER_42_OUTPUT_VXLAN_COOKIE) {
            switchManager.installServer42OutputVxlanFlow(
                    dpid, command.getOutputPort(), command.getServer42MacAddress());
        } else if (Cookie.isServer42Input(cookie)) {
            int customerPort = (int) Cookie.getValueFromIntermediateCookie(command.getCookie());
            switchManager.installServer42InputFlow(dpid, command.getInputPort(), customerPort);
        } else {
            logger.warn("Skipping the installation of unexpected server 42 switch rule {} for switch {}",
                    Long.toHexString(cookie), command.getSwitchId());
        }
    }

    private Long processInstallDefaultFlowByCookie(SwitchId switchId, long cookie) throws SwitchOperationException {
        ISwitchManager switchManager = context.getSwitchManager();
        DatapathId dpid = DatapathId.of(switchId.toLong());

        if (cookie == DROP_RULE_COOKIE) {
            return switchManager.installDropFlow(dpid);
        } else if (cookie == VERIFICATION_BROADCAST_RULE_COOKIE) {
            return switchManager.installVerificationRule(dpid, true);
        } else if (cookie == VERIFICATION_UNICAST_RULE_COOKIE) {
            return switchManager.installVerificationRule(dpid, false);
        } else if (cookie == DROP_VERIFICATION_LOOP_RULE_COOKIE) {
            return switchManager.installDropLoopRule(dpid);
        } else if (cookie == CATCH_BFD_RULE_COOKIE) {
            return switchManager.installBfdCatchFlow(dpid);
        } else if (cookie == ROUND_TRIP_LATENCY_RULE_COOKIE) {
            return switchManager.installRoundTripLatencyFlow(dpid);
        } else if (cookie == VERIFICATION_UNICAST_VXLAN_RULE_COOKIE) {
            return switchManager.installUnicastVerificationRuleVxlan(dpid);
        } else if (cookie == MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE) {
            return switchManager.installPreIngressTablePassThroughDefaultRule(dpid);
        } else if (cookie == MULTITABLE_INGRESS_DROP_COOKIE) {
            return switchManager.installDropFlowForTable(dpid, INGRESS_TABLE_ID, MULTITABLE_INGRESS_DROP_COOKIE);
        } else if (cookie == MULTITABLE_POST_INGRESS_DROP_COOKIE) {
            return switchManager.installDropFlowForTable(dpid, POST_INGRESS_TABLE_ID,
                    MULTITABLE_POST_INGRESS_DROP_COOKIE);
        } else if (cookie == MULTITABLE_EGRESS_PASS_THROUGH_COOKIE) {
            return switchManager.installEgressTablePassThroughDefaultRule(dpid);
        } else if (cookie == MULTITABLE_TRANSIT_DROP_COOKIE) {
            return switchManager.installDropFlowForTable(dpid, TRANSIT_TABLE_ID,
                    MULTITABLE_TRANSIT_DROP_COOKIE);
        } else if (cookie == LLDP_INPUT_PRE_DROP_COOKIE) {
            return switchManager.installLldpInputPreDropFlow(dpid);
        } else if (cookie == LLDP_INGRESS_COOKIE) {
            return switchManager.installLldpIngressFlow(dpid);
        } else if (cookie == LLDP_POST_INGRESS_COOKIE) {
            return switchManager.installLldpPostIngressFlow(dpid);
        } else if (cookie == LLDP_POST_INGRESS_VXLAN_COOKIE) {
            return switchManager.installLldpPostIngressVxlanFlow(dpid);
        } else if (cookie == LLDP_POST_INGRESS_ONE_SWITCH_COOKIE) {
            return switchManager.installLldpPostIngressOneSwitchFlow(dpid);
        } else if (cookie == LLDP_TRANSIT_COOKIE) {
            return switchManager.installLldpTransitFlow(dpid);
        } else if (cookie == ARP_INPUT_PRE_DROP_COOKIE) {
            return switchManager.installArpInputPreDropFlow(dpid);
        } else if (cookie == ARP_INGRESS_COOKIE) {
            return switchManager.installArpIngressFlow(dpid);
        } else if (cookie == ARP_POST_INGRESS_COOKIE) {
            return switchManager.installArpPostIngressFlow(dpid);
        } else if (cookie == ARP_POST_INGRESS_VXLAN_COOKIE) {
            return switchManager.installArpPostIngressVxlanFlow(dpid);
        } else if (cookie == ARP_POST_INGRESS_ONE_SWITCH_COOKIE) {
            return switchManager.installArpPostIngressOneSwitchFlow(dpid);
        } else if (cookie == ARP_TRANSIT_COOKIE) {
            return switchManager.installArpTransitFlow(dpid);
        } else if (cookie == SERVER_42_TURNING_COOKIE) {
            return switchManager.installServer42TurningFlow(dpid);
        } else if (Cookie.isIngressRulePassThrough(cookie)) {
            long port = Cookie.getValueFromIntermediateCookie(cookie);
            return switchManager.installIntermediateIngressRule(dpid, (int) port);
        } else if (Cookie.isIslVlanEgress(cookie)) {
            long port = Cookie.getValueFromIntermediateCookie(cookie);
            return switchManager.installEgressIslVlanRule(dpid, (int) port);
        } else if (Cookie.isIslVxlanTransit(cookie)) {
            long port = Cookie.getValueFromIntermediateCookie(cookie);
            return switchManager.installTransitIslVxlanRule(dpid, (int) port);
        } else if (Cookie.isIslVxlanEgress(cookie)) {
            long port = Cookie.getValueFromIntermediateCookie(cookie);
            return switchManager.installEgressIslVxlanRule(dpid, (int) port);
        } else if (Cookie.isLldpInputCustomer(cookie)) {
            long port = Cookie.getValueFromIntermediateCookie(cookie);
            return switchManager.installLldpInputCustomerFlow(dpid, (int) port);
        } else if (Cookie.isArpInputCustomer(cookie)) {
            long port = Cookie.getValueFromIntermediateCookie(cookie);
            return switchManager.installArpInputCustomerFlow(dpid, (int) port);
        } else {
            logger.warn("Skipping the installation of unexpected default switch rule {} for switch {}",
                    Long.toHexString(cookie), switchId);
        }
        return null;
    }

    private List<Long> processDeleteFlow(RemoveFlow command, DatapathId dpid) throws SwitchOperationException {
        logger.info("Deleting flow {} from switch {}", command.getId(), dpid);

        if (command.isCleanUpIngress()) {
            context.getSwitchManager().removeIntermediateIngressRule(dpid, command.getCriteria().getInPort());
        }
        if (command.isCleanUpIngressLldp()) {
            context.getSwitchManager().removeLldpInputCustomerFlow(dpid, command.getCriteria().getInPort());
        }
        if (command.isCleanUpIngressArp()) {
            context.getSwitchManager().removeArpInputCustomerFlow(dpid, command.getCriteria().getInPort());
        }
        DeleteRulesCriteria criteria = Optional.ofNullable(command.getCriteria())
                .orElseGet(() -> DeleteRulesCriteria.builder().cookie(command.getCookie()).build());
        ISwitchManager switchManager = context.getSwitchManager();
        List<Long> cookiesOfRemovedRules = switchManager.deleteRulesByCriteria(dpid, command.isMultiTable(),
                command.getRuleType(), criteria);
        if (cookiesOfRemovedRules.isEmpty()) {
            logger.warn("No rules were removed by criteria {} for flow {} from switch {}",
                    criteria, command.getId(), dpid);
        }

        Long meterId = command.getMeterId();
        if (meterId != null) {
            try {
                switchManager.deleteMeter(dpid, meterId);
            } catch (UnsupportedOperationException e) {
                logger.info("Skip meter {} deletion from switch {}: {}", meterId, dpid, e.getMessage());
            } catch (SwitchOperationException e) {
                logger.error("Failed to delete meter {} from switch {}: {}", meterId, dpid, e.getMessage());
            }
        }
        return cookiesOfRemovedRules;
    }

    /**
     * Create network dump for OFELinkBolt.
     *
     * @param message NetworkCommandData
     */
    private void doNetworkDump(final CommandMessage message) {
        logger.info("Processing request from WFM to dump switches. {}", message.getCorrelationId());

        SwitchTrackingService switchTracking = context.getModuleContext().getServiceImpl(SwitchTrackingService.class);
        switchTracking.dumpAllSwitches();
    }

    private void doInstallSwitchRules(final CommandMessage message) {
        SwitchRulesInstallRequest request = (SwitchRulesInstallRequest) message.getData();
        logger.info("Installing rules on '{}' switch: action={}",
                request.getSwitchId(), request.getInstallRulesAction());

        final IKafkaProducerService producerService = getKafkaProducer();
        final String replyToTopic = context.getKafkaSwitchManagerTopic();
        Integer server42Port = request.getServer42Port();
        MacAddress server42MacAddress = request.getServer42MacAddress();

        DatapathId dpid = DatapathId.of(request.getSwitchId().toLong());
        ISwitchManager switchManager = context.getSwitchManager();
        InstallRulesAction installAction = request.getInstallRulesAction();
        List<Long> installedRules = new ArrayList<>();
        try {
            if (installAction == InstallRulesAction.INSTALL_DROP) {
                installedRules.add(switchManager.installDropFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_BROADCAST) {
                installedRules.add(switchManager.installVerificationRule(dpid, true));
            } else if (installAction == InstallRulesAction.INSTALL_UNICAST) {
                // TODO: this isn't always added (ie if OF1.2). Is there a better response?
                installedRules.add(switchManager.installVerificationRule(dpid, false));
            } else if (installAction == InstallRulesAction.INSTALL_DROP_VERIFICATION_LOOP) {
                installedRules.add(switchManager.installDropLoopRule(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_BFD_CATCH) {
                // TODO: this isn't installed as well. Refactor this section
                installedRules.add(switchManager.installBfdCatchFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ROUND_TRIP_LATENCY) {
                // TODO: this isn't installed as well. Refactor this section
                installedRules.add(switchManager.installRoundTripLatencyFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_UNICAST_VXLAN) {
                installedRules.add(switchManager.installUnicastVerificationRuleVxlan(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_MULTITABLE_PRE_INGRESS_PASS_THROUGH) {
                installedRules.add(switchManager.installPreIngressTablePassThroughDefaultRule(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_MULTITABLE_INGRESS_DROP) {
                installedRules.add(switchManager.installDropFlowForTable(dpid,
                        INGRESS_TABLE_ID, MULTITABLE_INGRESS_DROP_COOKIE));
            } else if (installAction == InstallRulesAction.INSTALL_MULTITABLE_POST_INGRESS_DROP) {
                installedRules.add(switchManager.installDropFlowForTable(dpid,
                        POST_INGRESS_TABLE_ID, MULTITABLE_POST_INGRESS_DROP_COOKIE));
            } else if (installAction == InstallRulesAction.INSTALL_MULTITABLE_EGRESS_PASS_THROUGH) {
                installedRules.add(switchManager.installEgressTablePassThroughDefaultRule(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_MULTITABLE_TRANSIT_DROP) {
                installedRules.add(switchManager.installDropFlowForTable(dpid,
                        TRANSIT_TABLE_ID, MULTITABLE_TRANSIT_DROP_COOKIE));
            } else if (installAction == InstallRulesAction.INSTALL_LLDP_INPUT_PRE_DROP) {
                installedRules.add(switchManager.installLldpInputPreDropFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_LLDP_INGRESS) {
                installedRules.add(switchManager.installLldpIngressFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_LLDP_POST_INGRESS) {
                installedRules.add(switchManager.installLldpPostIngressFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_LLDP_POST_INGRESS_VXLAN) {
                installedRules.add(switchManager.installLldpPostIngressVxlanFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_LLDP_POST_INGRESS_ONE_SWITCH) {
                installedRules.add(switchManager.installLldpPostIngressOneSwitchFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_LLDP_TRANSIT) {
                installedRules.add(switchManager.installLldpTransitFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ARP_INPUT_PRE_DROP) {
                installedRules.add(switchManager.installArpInputPreDropFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ARP_INGRESS) {
                installedRules.add(switchManager.installArpIngressFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ARP_POST_INGRESS) {
                installedRules.add(switchManager.installArpPostIngressFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ARP_POST_INGRESS_VXLAN) {
                installedRules.add(switchManager.installArpPostIngressVxlanFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ARP_POST_INGRESS_ONE_SWITCH) {
                installedRules.add(switchManager.installArpPostIngressOneSwitchFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_ARP_TRANSIT) {
                installedRules.add(switchManager.installArpTransitFlow(dpid));
            } else if (installAction == InstallRulesAction.INSTALL_SERVER_42_OUTPUT_VLAN) {
                installedRules.add(switchManager.installServer42OutputVlanFlow(dpid, server42Port, server42MacAddress));
            } else if (installAction == InstallRulesAction.INSTALL_SERVER_42_OUTPUT_VXLAN) {
                installedRules.add(switchManager.installServer42OutputVxlanFlow(
                        dpid, server42Port, server42MacAddress));
            } else if (installAction == InstallRulesAction.INSTALL_SERVER_42_TURNING) {
                installedRules.add(switchManager.installServer42TurningFlow(dpid));
            } else {
                installedRules.addAll(switchManager.installDefaultRules(dpid));
                if (request.isMultiTable()) {
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_INGRESS_DROP_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_POST_INGRESS_DROP_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_EGRESS_PASS_THROUGH_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_TRANSIT_DROP_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            LLDP_POST_INGRESS_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            LLDP_POST_INGRESS_VXLAN_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            LLDP_POST_INGRESS_ONE_SWITCH_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            ARP_POST_INGRESS_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            ARP_POST_INGRESS_VXLAN_COOKIE));
                    installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                            ARP_POST_INGRESS_ONE_SWITCH_COOKIE));
                    for (int port : request.getIslPorts()) {
                        installedRules.addAll(switchManager.installMultitableEndpointIslRules(dpid, port));
                    }
                    for (int port : request.getFlowPorts()) {
                        installedRules.add(switchManager.installIntermediateIngressRule(dpid, port));
                    }
                    for (Integer port : request.getFlowLldpPorts()) {
                        installedRules.add(switchManager.installLldpInputCustomerFlow(dpid, port));
                    }
                    for (Integer port : request.getFlowArpPorts()) {
                        installedRules.add(switchManager.installArpInputCustomerFlow(dpid, port));
                    }

                    if (request.isSwitchLldp()) {
                        installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                                LLDP_INPUT_PRE_DROP_COOKIE));
                        installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                                LLDP_TRANSIT_COOKIE));
                        installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                                LLDP_INGRESS_COOKIE));
                    }
                    if (request.isSwitchArp()) {
                        installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                                ARP_INPUT_PRE_DROP_COOKIE));
                        installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                                ARP_TRANSIT_COOKIE));
                        installedRules.add(processInstallDefaultFlowByCookie(request.getSwitchId(),
                                ARP_INGRESS_COOKIE));
                    }

                    if (request.isServer42FlowRtt()) {
                        installedRules.add(
                                processInstallDefaultFlowByCookie(request.getSwitchId(), SERVER_42_TURNING_COOKIE));
                        installedRules.add(
                                switchManager.installServer42OutputVlanFlow(dpid, server42Port, server42MacAddress));
                        installedRules.add(
                                switchManager.installServer42OutputVxlanFlow(dpid, server42Port, server42MacAddress));

                        for (Integer port : request.getServer42FlowRttPorts()) {
                            installedRules.add(switchManager.installServer42InputFlow(dpid, server42Port, port));
                        }
                    }
                }
            }

            SwitchRulesResponse response = new SwitchRulesResponse(
                    installedRules.stream().filter(Objects::nonNull).collect(Collectors.toList()));
            InfoMessage infoMessage = new InfoMessage(response,
                    System.currentTimeMillis(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, record.key(), infoMessage);

        } catch (SwitchOperationException e) {
            logger.error("Failed to install rules on switch '{}'", request.getSwitchId(), e);
            anError(ErrorType.CREATION_FAILURE)
                    .withMessage(e.getMessage())
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .withKey(record.key())
                    .sendVia(producerService);
        }
    }

    private void doDeleteSwitchRules(final CommandMessage message) {
        SwitchRulesDeleteRequest request = (SwitchRulesDeleteRequest) message.getData();
        logger.info("Deleting rules from '{}' switch: action={}, criteria={}", request.getSwitchId(),
                request.getDeleteRulesAction(), request.getCriteria());

        final IKafkaProducerService producerService = getKafkaProducer();
        final String replyToTopic = context.getKafkaSwitchManagerTopic();

        DatapathId dpid = DatapathId.of(request.getSwitchId().toLong());
        DeleteRulesAction deleteAction = request.getDeleteRulesAction();
        DeleteRulesCriteria criteria = request.getCriteria();

        ISwitchManager switchManager = context.getSwitchManager();

        try {
            List<Long> removedRules = new ArrayList<>();

            if (deleteAction != null) {
                switch (deleteAction) {
                    case REMOVE_DROP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(DROP_RULE_COOKIE).build();
                        break;
                    case REMOVE_BROADCAST:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(VERIFICATION_BROADCAST_RULE_COOKIE).build();
                        break;
                    case REMOVE_UNICAST:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(VERIFICATION_UNICAST_RULE_COOKIE).build();
                        break;
                    case REMOVE_VERIFICATION_LOOP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(DROP_VERIFICATION_LOOP_RULE_COOKIE).build();
                        break;
                    case REMOVE_BFD_CATCH:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(CATCH_BFD_RULE_COOKIE).build();
                        break;
                    case REMOVE_ROUND_TRIP_LATENCY:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ROUND_TRIP_LATENCY_RULE_COOKIE).build();
                        break;
                    case REMOVE_UNICAST_VXLAN:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).build();
                        break;
                    case REMOVE_MULTITABLE_PRE_INGRESS_PASS_THROUGH:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE).build();
                        break;
                    case REMOVE_MULTITABLE_INGRESS_DROP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(MULTITABLE_INGRESS_DROP_COOKIE).build();
                        break;
                    case REMOVE_MULTITABLE_POST_INGRESS_DROP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(MULTITABLE_POST_INGRESS_DROP_COOKIE).build();
                        break;
                    case REMOVE_MULTITABLE_EGRESS_PASS_THROUGH:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(MULTITABLE_EGRESS_PASS_THROUGH_COOKIE).build();
                        break;
                    case REMOVE_MULTITABLE_TRANSIT_DROP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(MULTITABLE_TRANSIT_DROP_COOKIE).build();
                        break;
                    case REMOVE_LLDP_INPUT_PRE_DROP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(LLDP_INPUT_PRE_DROP_COOKIE).build();
                        break;
                    case REMOVE_LLDP_INGRESS:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(LLDP_INGRESS_COOKIE).build();
                        break;
                    case REMOVE_LLDP_POST_INGRESS:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(LLDP_POST_INGRESS_COOKIE).build();
                        break;
                    case REMOVE_LLDP_POST_INGRESS_VXLAN:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(LLDP_POST_INGRESS_VXLAN_COOKIE).build();
                        break;
                    case REMOVE_LLDP_POST_INGRESS_ONE_SWITCH:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).build();
                        break;
                    case REMOVE_LLDP_TRANSIT:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(LLDP_TRANSIT_COOKIE).build();
                        break;
                    case REMOVE_ARP_INPUT_PRE_DROP:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ARP_INPUT_PRE_DROP_COOKIE).build();
                        break;
                    case REMOVE_ARP_INGRESS:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ARP_INGRESS_COOKIE).build();
                        break;
                    case REMOVE_ARP_POST_INGRESS:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ARP_POST_INGRESS_COOKIE).build();
                        break;
                    case REMOVE_ARP_POST_INGRESS_VXLAN:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ARP_POST_INGRESS_VXLAN_COOKIE).build();
                        break;
                    case REMOVE_ARP_POST_INGRESS_ONE_SWITCH:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ARP_POST_INGRESS_ONE_SWITCH_COOKIE).build();
                        break;
                    case REMOVE_ARP_TRANSIT:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(ARP_TRANSIT_COOKIE).build();
                        break;
                    case REMOVE_SERVER_42_TURNING:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(SERVER_42_TURNING_COOKIE).build();
                        break;
                    case REMOVE_SERVER_42_OUTPUT_VLAN:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(SERVER_42_OUTPUT_VLAN_COOKIE).build();
                        break;
                    case REMOVE_SERVER_42_OUTPUT_VXLAN:
                        criteria = DeleteRulesCriteria.builder()
                                .cookie(SERVER_42_OUTPUT_VXLAN_COOKIE).build();
                        break;
                    default:
                        logger.warn("Received unexpected delete switch rule action: {}", deleteAction);
                }

                // The cases when we delete all non-default rules.
                if (deleteAction.nonDefaultRulesToBeRemoved()) {
                    removedRules.addAll(switchManager.deleteAllNonDefaultRules(dpid));
                }

                // The cases when we delete the default rules.
                if (deleteAction.defaultRulesToBeRemoved()) {
                    removedRules.addAll(switchManager.deleteDefaultRules(dpid, request.getIslPorts(),
                            request.getFlowPorts(), request.getFlowLldpPorts(), request.getFlowArpPorts(),
                            request.getServer42FlowRttPorts(), request.isMultiTable(), request.isSwitchLldp(),
                            request.isSwitchArp(), request.isServer42FlowRtt()));
                }
            }

            // The case when we either delete by criteria or a specific default rule.
            if (criteria != null) {
                removedRules.addAll(switchManager.deleteRulesByCriteria(dpid, false, null, criteria));
            }

            // The cases when we (re)install the default rules.
            if (deleteAction != null && deleteAction.defaultRulesToBeInstalled()) {
                switchManager.installDefaultRules(dpid);
                if (request.isMultiTable()) {
                    processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_INGRESS_DROP_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_POST_INGRESS_DROP_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_EGRESS_PASS_THROUGH_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(),
                            MULTITABLE_TRANSIT_DROP_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(), LLDP_POST_INGRESS_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(), LLDP_POST_INGRESS_VXLAN_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(), LLDP_POST_INGRESS_ONE_SWITCH_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(), ARP_POST_INGRESS_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(), ARP_POST_INGRESS_VXLAN_COOKIE);
                    processInstallDefaultFlowByCookie(request.getSwitchId(), ARP_POST_INGRESS_ONE_SWITCH_COOKIE);
                    for (int port : request.getIslPorts()) {
                        switchManager.installMultitableEndpointIslRules(dpid, port);
                    }

                    for (int port : request.getFlowPorts()) {
                        switchManager.installIntermediateIngressRule(dpid, port);
                    }
                    for (Integer port : request.getFlowLldpPorts()) {
                        switchManager.installLldpInputCustomerFlow(dpid, port);
                    }
                    for (Integer port : request.getFlowArpPorts()) {
                        switchManager.installArpInputCustomerFlow(dpid, port);
                    }

                    if (request.isSwitchLldp()) {
                        processInstallDefaultFlowByCookie(request.getSwitchId(), LLDP_INPUT_PRE_DROP_COOKIE);
                        processInstallDefaultFlowByCookie(request.getSwitchId(), LLDP_TRANSIT_COOKIE);
                        processInstallDefaultFlowByCookie(request.getSwitchId(), LLDP_INGRESS_COOKIE);
                    }
                    if (request.isSwitchArp()) {
                        processInstallDefaultFlowByCookie(request.getSwitchId(), ARP_INPUT_PRE_DROP_COOKIE);
                        processInstallDefaultFlowByCookie(request.getSwitchId(), ARP_TRANSIT_COOKIE);
                        processInstallDefaultFlowByCookie(request.getSwitchId(), ARP_INGRESS_COOKIE);
                    }
                    if (request.isServer42FlowRtt()) {
                        Integer server42Port = request.getServer42Port();
                        MacAddress server42MacAddress = request.getServer42MacAddress();

                        switchManager.installServer42TurningFlow(dpid);
                        switchManager.installServer42OutputVlanFlow(dpid, server42Port, server42MacAddress);
                        switchManager.installServer42OutputVxlanFlow(dpid, server42Port, server42MacAddress);

                        for (Integer port : request.getServer42FlowRttPorts()) {
                            switchManager.installServer42InputFlow(dpid, server42Port, port);
                        }
                    }
                }
            }

            SwitchRulesResponse response = new SwitchRulesResponse(removedRules);
            InfoMessage infoMessage = new InfoMessage(response,
                    System.currentTimeMillis(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, record.key(), infoMessage);

        } catch (SwitchNotFoundException e) {
            logger.error("Deleting switch rules was unsuccessful. Switch '{}' not found", request.getSwitchId());
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .withKey(record.key())
                    .sendVia(producerService);
        } catch (SwitchOperationException e) {
            logger.error("Failed to delete switch '{}' rules.", request.getSwitchId(), e);
            anError(ErrorType.DELETION_FAILURE)
                    .withMessage(e.getMessage())
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .withKey(record.key())
                    .sendVia(producerService);
        }
    }

    private void doConnectMode(final CommandMessage message) {
        ConnectModeRequest request = (ConnectModeRequest) message.getData();
        if (request.getMode() != null) {
            logger.debug("Setting CONNECT MODE to '{}'", request.getMode());
        } else {
            logger.debug("Getting CONNECT MODE");
        }

        ISwitchManager switchManager = context.getSwitchManager();
        ConnectModeRequest.Mode result = switchManager.connectMode(request.getMode());

        logger.info("CONNECT MODE is now '{}'", result);
        ConnectModeResponse response = new ConnectModeResponse(result);
        InfoMessage infoMessage = new InfoMessage(response,
                System.currentTimeMillis(), message.getCorrelationId());
        getKafkaProducer().sendMessageAndTrack(context.getKafkaNorthboundTopic(), infoMessage);

    }

    private void doGetExpectedDefaultRulesRequest(CommandMessage message) {
        IKafkaProducerService producerService = getKafkaProducer();
        String replyToTopic = context.getKafkaSwitchManagerTopic();

        GetExpectedDefaultRulesRequest request = (GetExpectedDefaultRulesRequest) message.getData();
        SwitchId switchId = request.getSwitchId();
        boolean multiTable = request.isMultiTable();
        boolean switchLldp = request.isSwitchLldp();
        boolean switchArp = request.isSwitchArp();
        boolean server42FlowRtt = request.isServer42FlowRtt();
        Integer server42Port = request.getServer42Port();
        MacAddress server42MacAddress = request.getServer42MacAddress();
        List<Integer> islPorts = request.getIslPorts();
        List<Integer> flowPorts = request.getFlowPorts();
        Set<Integer> flowLldpPorts = request.getFlowLldpPorts();
        Set<Integer> flowArpPorts = request.getFlowArpPorts();
        Set<Integer> server42FlowRttPorts = request.getFlowArpPorts();

        try {
            logger.debug("Loading expected default rules for switch {}", switchId);
            DatapathId dpid = DatapathId.of(switchId.toLong());
            List<OFFlowMod> defaultRules =
                    context.getSwitchManager().getExpectedDefaultFlows(dpid, multiTable, switchLldp, switchArp);
            if (multiTable) {
                for (int port : islPorts) {
                    List<OFFlowMod> islFlows = context.getSwitchManager().getExpectedIslFlowsForPort(dpid, port);
                    defaultRules.addAll(islFlows);
                }
                for (int port : flowPorts) {
                    defaultRules.add(context.getSwitchManager().buildIntermediateIngressRule(dpid, port));
                }
                for (Integer port : flowLldpPorts) {
                    defaultRules.add(context.getSwitchManager().buildLldpInputCustomerFlow(dpid, port));
                }
                for (Integer port : flowArpPorts) {
                    defaultRules.add(context.getSwitchManager().buildArpInputCustomerFlow(dpid, port));
                }
                if (server42FlowRtt) {
                    defaultRules.addAll(context.getSwitchManager()
                            .buildExpectedServer42Flows(dpid, server42Port, server42MacAddress, server42FlowRttPorts));
                }
            }
            List<FlowEntry> flows = defaultRules.stream()
                    .map(OfFlowStatsMapper.INSTANCE::toFlowEntry)
                    .collect(Collectors.toList());

            SwitchExpectedDefaultFlowEntries response = SwitchExpectedDefaultFlowEntries.builder()
                    .switchId(switchId)
                    .flowEntries(flows)
                    .build();
            InfoMessage infoMessage = new InfoMessage(response, message.getTimestamp(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Getting of expected default rules for switch '{}' was unsuccessful: {}",
                    switchId, e.getMessage());
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription(format("Switch '%s' was not found when requesting expected default rules.",
                            switchId))
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }

    }

    private void doGetExpectedDefaultMetersRequest(CommandMessage message) {
        IKafkaProducerService producerService = getKafkaProducer();
        String replyToTopic = context.getKafkaSwitchManagerTopic();

        GetExpectedDefaultMetersRequest request = (GetExpectedDefaultMetersRequest) message.getData();
        SwitchId switchId = request.getSwitchId();
        boolean multiTable = request.isMultiTable();
        boolean switchLldp = request.isSwitchLldp();
        boolean switchArp = request.isSwitchArp();

        try {
            logger.debug("Loading expected default meters for switch {}", switchId);
            DatapathId dpid = DatapathId.of(switchId.toLong());
            List<MeterEntry> defaultMeters =
                    context.getSwitchManager().getExpectedDefaultMeters(dpid, multiTable, switchLldp, switchArp);

            SwitchExpectedDefaultMeterEntries response = SwitchExpectedDefaultMeterEntries.builder()
                    .switchId(switchId)
                    .meterEntries(defaultMeters)
                    .build();
            InfoMessage infoMessage = new InfoMessage(response, message.getTimestamp(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), infoMessage);
        } catch (UnsupportedSwitchOperationException e) {
            logger.info("Meters not supported: {}", switchId);
            InfoMessage infoMessage = new InfoMessage(new SwitchMeterUnsupported(switchId), message.getTimestamp(),
                    message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Getting of expected default meters for switch '{}' was unsuccessful: {}",
                    switchId, e.getMessage());
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription(format("Switch '%s' was not found when requesting get expected default meters.",
                            switchId))
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private void doDumpRulesRequest(final CommandMessage message) {
        processDumpRulesRequest(((DumpRulesRequest) message.getData()).getSwitchId(),
                context.getKafkaNorthboundTopic(), message.getCorrelationId(), message.getTimestamp());
    }

    private void doDumpRulesForSwitchManagerRequest(final CommandMessage message) {
        processDumpRulesRequest(((DumpRulesForSwitchManagerRequest) message.getData()).getSwitchId(),
                context.getKafkaSwitchManagerTopic(), message.getCorrelationId(), message.getTimestamp());
    }

    private void doDumpRulesForNbworkerRequest(final CommandMessage message) {
        processDumpRulesRequest(((DumpRulesForNbworkerRequest) message.getData()).getSwitchId(),
                context.getKafkaNbWorkerTopic(), message.getCorrelationId(), message.getTimestamp());
    }

    private void processDumpRulesRequest(final SwitchId switchId, final String replyToTopic,
                                         String correlationId, long timestamp) {
        final IKafkaProducerService producerService = getKafkaProducer();

        try {
            logger.debug("Loading installed rules for switch {}", switchId);

            List<OFFlowStatsEntry> flowEntries =
                    context.getSwitchManager().dumpFlowTable(DatapathId.of(switchId.toLong()));
            List<FlowEntry> flows = flowEntries.stream()
                    .map(OfFlowStatsMapper.INSTANCE::toFlowEntry)
                    .collect(Collectors.toList());

            SwitchFlowEntries response = SwitchFlowEntries.builder()
                    .switchId(switchId)
                    .flowEntries(flows)
                    .build();
            InfoMessage infoMessage = new InfoMessage(response, timestamp, correlationId);
            producerService.sendMessageAndTrack(replyToTopic, correlationId, infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Dumping of rules on switch '{}' was unsuccessful: {}", switchId, e.getMessage());
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription("The switch was not found when requesting a rules dump.")
                    .withCorrelationId(correlationId)
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    /**
     * Install of flow on the switch from SwitchManager topology.
     *
     * @param message with list of flows.
     */
    private void doInstallFlowForSwitchManager(final CommandMessage message) {
        InstallFlowForSwitchManagerRequest request = (InstallFlowForSwitchManagerRequest) message.getData();

        String replyToTopic = context.getKafkaSwitchManagerTopic();

        try {
            installFlow(request.getFlowCommand());

        } catch (SwitchOperationException e) {
            logger.error("Error during flow installation", e);
            ErrorData errorData = new ErrorData(ErrorType.INTERNAL_ERROR, "Error during flow installation",
                    "Switch operation error");
            ErrorMessage error = new ErrorMessage(errorData, System.currentTimeMillis(),
                    message.getCorrelationId());
            getKafkaProducer().sendMessageAndTrack(replyToTopic, message.getCorrelationId(), error);

        } catch (FlowCommandException e) {
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            logger.error("Failed to handle message {}: {}", message, errorMessage);
            ErrorData errorData = new FlowCommandErrorData(e.getFlowId(), e.getCookie(), e.getTransactionId(),
                    e.getErrorType(), errorMessage, e.getMessage());
            ErrorMessage error = new ErrorMessage(errorData, System.currentTimeMillis(),
                    message.getCorrelationId());
            getKafkaProducer().sendMessageAndTrack(replyToTopic, message.getCorrelationId(), error);
        }

        InfoMessage response = new InfoMessage(new FlowInstallResponse(), System.currentTimeMillis(),
                message.getCorrelationId());
        getKafkaProducer().sendMessageAndTrack(replyToTopic, message.getCorrelationId(), response);
    }

    private void installFlow(BaseInstallFlow command) throws FlowCommandException,
            SwitchOperationException {
        logger.debug("Processing flow install command {}", command);
        if (command instanceof InstallServer42Flow) {
            processInstallServer42Rule((InstallServer42Flow) command);
        } else if (Cookie.isDefaultRule(command.getCookie())) {
            processInstallDefaultFlowByCookie(command.getSwitchId(), command.getCookie());
        } else if (command instanceof InstallIngressFlow) {
            installIngressFlow((InstallIngressFlow) command);
        } else if (command instanceof InstallEgressFlow) {
            installEgressFlow((InstallEgressFlow) command);
        } else if (command instanceof InstallTransitFlow) {
            installTransitFlow((InstallTransitFlow) command);
        } else if (command instanceof InstallOneSwitchFlow) {
            installOneSwitchFlow((InstallOneSwitchFlow) command);
        } else {
            throw new FlowCommandException(command.getId(), command.getCookie(), command.getTransactionId(),
                    ErrorType.REQUEST_INVALID, "Unsupported command for install.");
        }
    }

    private void doPortsCommandDataRequest(CommandMessage message) {
        ISwitchManager switchManager = context.getModuleContext().getServiceImpl(ISwitchManager.class);

        try {
            PortsCommandData request = (PortsCommandData) message.getData();
            logger.info("Getting ports data. Requester: {}", request.getRequester());
            Map<DatapathId, IOFSwitch> allSwitchMap = context.getSwitchManager().getAllSwitchMap(true);
            for (Map.Entry<DatapathId, IOFSwitch> entry : allSwitchMap.entrySet()) {
                SwitchId switchId = new SwitchId(entry.getKey().toString());
                try {
                    IOFSwitch sw = entry.getValue();

                    Set<PortStatusData> statuses = new HashSet<>();
                    for (OFPortDesc portDesc : switchManager.getPhysicalPorts(sw.getId())) {
                        statuses.add(new PortStatusData(portDesc.getPortNo().getPortNumber(),
                                portDesc.isEnabled() ? PortStatus.UP : PortStatus.DOWN));
                    }

                    SwitchPortStatusData response = SwitchPortStatusData.builder()
                            .switchId(switchId)
                            .ports(statuses)
                            .requester(request.getRequester())
                            .build();

                    InfoMessage infoMessage = new InfoMessage(response, message.getTimestamp(),
                            message.getCorrelationId());
                    getKafkaProducer().sendMessageAndTrack(context.getKafkaStatsTopic(), infoMessage);
                } catch (Exception e) {
                    logger.error("Could not get port stats data for switch '{}' with error '{}'",
                            switchId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Could not get port data for stats '{}'", e.getMessage(), e);
        }
    }

    private void doDeleteMeter(CommandMessage message, String replyToTopic) {
        DeleteMeterRequest request = (DeleteMeterRequest) message.getData();
        logger.info("Deleting meter '{}'. Switch: '{}'", request.getMeterId(), request.getSwitchId());

        final IKafkaProducerService producerService = getKafkaProducer();

        try {
            DatapathId dpid = DatapathId.of(request.getSwitchId().toLong());
            context.getSwitchManager().deleteMeter(dpid, request.getMeterId());

            boolean deleted = context.getSwitchManager().dumpMeters(dpid)
                    .stream()
                    .noneMatch(config -> config.getMeterId() == request.getMeterId());
            DeleteMeterResponse response = new DeleteMeterResponse(deleted);
            InfoMessage infoMessage = new InfoMessage(response, System.currentTimeMillis(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Deleting meter '{}' from switch '{}' was unsuccessful: {}",
                    request.getMeterId(), request.getSwitchId(), e.getMessage());
            anError(ErrorType.DATA_INVALID)
                    .withMessage(e.getMessage())
                    .withDescription(request.getSwitchId().toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private void doConfigurePort(final CommandMessage message) {
        PortConfigurationRequest request = (PortConfigurationRequest) message.getData();

        logger.info("Port configuration request. Switch '{}', Port '{}'", request.getSwitchId(),
                request.getPortNumber());

        final IKafkaProducerService producerService = getKafkaProducer();
        final String replyToTopic = context.getKafkaNorthboundTopic();

        try {
            ISwitchManager switchManager = context.getSwitchManager();

            DatapathId dpId = DatapathId.of(request.getSwitchId().toLong());
            switchManager.configurePort(dpId, request.getPortNumber(), request.getAdminDown());

            InfoMessage infoMessage = new InfoMessage(
                    new PortConfigurationResponse(request.getSwitchId(), request.getPortNumber()),
                    message.getTimestamp(),
                    message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Port configuration request failed. " + e.getMessage(), e);
            anError(ErrorType.DATA_INVALID)
                    .withMessage(e.getMessage())
                    .withDescription("Port configuration request failed")
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private void doDumpSwitchPortsDescriptionRequest(CommandMessage message) {
        DumpSwitchPortsDescriptionRequest request = (DumpSwitchPortsDescriptionRequest) message.getData();

        final IKafkaProducerService producerService = getKafkaProducer();
        final String replyToTopic = context.getKafkaNorthboundTopic();

        try {
            SwitchId switchId = request.getSwitchId();
            logger.info("Dump ALL ports description for switch {}", switchId);

            SwitchPortsDescription response = getSwitchPortsDescription(switchId);

            InfoMessage infoMessage = new InfoMessage(response, message.getTimestamp(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Unable to dump switch port descriptions request", e);
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription("Unable to dump switch port descriptions request")
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private SwitchPortsDescription getSwitchPortsDescription(SwitchId switchId) throws SwitchOperationException {

        List<OFPortDesc> ofPortsDescriptions =
                context.getSwitchManager().dumpPortsDescription(DatapathId.of(switchId.toLong()));
        List<PortDescription> portsDescriptions = ofPortsDescriptions.stream()
                .map(OfPortDescConverter.INSTANCE::toPortDescription)
                .collect(Collectors.toList());

        return SwitchPortsDescription.builder()
                .version(ofPortsDescriptions.get(0).getVersion().toString())
                .portsDescription(portsDescriptions)
                .build();
    }

    private void doDumpPortDescriptionRequest(CommandMessage message) {
        DumpPortDescriptionRequest request = (DumpPortDescriptionRequest) message.getData();

        final IKafkaProducerService producerService = getKafkaProducer();
        final String replyToTopic = context.getKafkaNorthboundTopic();

        try {
            SwitchId switchId = request.getSwitchId();
            logger.info("Get port {}_{} description", switchId, request.getPortNumber());
            SwitchPortsDescription switchPortsDescription = getSwitchPortsDescription(switchId);

            int port = request.getPortNumber();
            PortDescription response = switchPortsDescription.getPortsDescription()
                    .stream()
                    .filter(x -> x.getPortNumber() == port)
                    .findFirst()
                    .orElseThrow(() -> new SwitchOperationException(
                            DatapathId.of(switchId.toLong()),
                            format("Port %s_%d does not exists.", switchId, port)));

            InfoMessage infoMessage = new InfoMessage(response, message.getTimestamp(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, infoMessage);
        } catch (SwitchOperationException e) {
            logger.error("Unable to dump port description request", e);
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription("Unable to dump port description request")
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private void doDumpMetersRequest(CommandMessage message) {
        DumpMetersRequest request = (DumpMetersRequest) message.getData();
        String replyToTopic = context.getKafkaNorthboundTopic();
        dumpMeters(request.getSwitchId(), message.getCorrelationId(), replyToTopic, message.getTimestamp());
    }

    private void doDumpMetersForSwitchManagerRequest(CommandMessage message) {
        DumpMetersForSwitchManagerRequest request = (DumpMetersForSwitchManagerRequest) message.getData();
        String replyToTopic = context.getKafkaSwitchManagerTopic();
        dumpMeters(request.getSwitchId(), message.getCorrelationId(), replyToTopic, message.getTimestamp());
    }

    private void doDumpMetersForNbworkerRequest(CommandMessage message) {
        DumpMetersForNbworkerRequest request = (DumpMetersForNbworkerRequest) message.getData();
        String replyToTopic = context.getKafkaNbWorkerTopic();
        dumpMeters(request.getSwitchId(), message.getCorrelationId(), replyToTopic, message.getTimestamp());
    }

    private void dumpMeters(SwitchId switchId, String correlationId, String replyToTopic, long timestamp) {
        final IKafkaProducerService producerService = getKafkaProducer();

        try {
            logger.debug("Get all meters for switch {}", switchId);
            ISwitchManager switchManager = context.getSwitchManager();
            List<OFMeterConfig> meterEntries = switchManager.dumpMeters(DatapathId.of(switchId.toLong()));
            List<MeterEntry> meters = meterEntries.stream()
                    .map(OfMeterConverter::toMeterEntry)
                    .collect(Collectors.toList());

            SwitchMeterEntries response = SwitchMeterEntries.builder()
                    .switchId(switchId)
                    .meterEntries(meters)
                    .build();
            InfoMessage infoMessage = new InfoMessage(response, timestamp, correlationId);
            producerService.sendMessageAndTrack(replyToTopic, correlationId, infoMessage);
        } catch (UnsupportedSwitchOperationException e) {
            logger.info("Meters not supported: {}", switchId);
            InfoMessage infoMessage = new InfoMessage(new SwitchMeterUnsupported(switchId), timestamp, correlationId);
            producerService.sendMessageAndTrack(replyToTopic, correlationId, infoMessage);
        } catch (SwitchNotFoundException e) {
            logger.info("Dumping switch meters is unsuccessful. Switch {} not found", switchId);
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription(switchId.toString())
                    .withCorrelationId(correlationId)
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        } catch (SwitchOperationException e) {
            logger.error("Unable to dump meters", e);
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription("Unable to dump meters")
                    .withCorrelationId(correlationId)
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private void doModifyMeterRequest(CommandMessage message) {
        MeterModifyCommandRequest request = (MeterModifyCommandRequest) message.getData();

        final IKafkaProducerService producerService = getKafkaProducer();
        String replyToTopic = context.getKafkaNbWorkerTopic();

        SwitchId switchId = request.getSwitchId();

        DatapathId datapathId = DatapathId.of(switchId.toLong());
        long meterId = request.getMeterId();

        ISwitchManager switchManager = context.getSwitchManager();

        try {
            switchManager.modifyMeterForFlow(datapathId, meterId, request.getBandwidth());

            MeterEntry meterEntry = OfMeterConverter.toMeterEntry(switchManager.dumpMeterById(datapathId, meterId));

            SwitchMeterEntries response = SwitchMeterEntries.builder()
                    .switchId(switchId)
                    .meterEntries(ImmutableList.of(meterEntry))
                    .build();

            InfoMessage infoMessage = new InfoMessage(response, message.getTimestamp(), message.getCorrelationId());
            producerService.sendMessageAndTrack(replyToTopic, message.getCorrelationId(), infoMessage);
        } catch (UnsupportedSwitchOperationException e) {
            String messageString = String.format("Not supported: %s", new SwitchId(e.getDpId().getLong()));
            logger.error(messageString, e);
            anError(ErrorType.PARAMETERS_INVALID)
                    .withMessage(e.getMessage())
                    .withDescription(messageString)
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        } catch (SwitchNotFoundException e) {
            logger.error("Update switch meters is unsuccessful. Switch {} not found",
                    new SwitchId(e.getDpId().getLong()));
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription(new SwitchId(e.getDpId().getLong()).toString())
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        } catch (SwitchOperationException e) {
            String messageString = "Unable to update meter";
            logger.error(messageString, e);
            anError(ErrorType.NOT_FOUND)
                    .withMessage(e.getMessage())
                    .withDescription(messageString)
                    .withCorrelationId(message.getCorrelationId())
                    .withTopic(replyToTopic)
                    .sendVia(producerService);
        }
    }

    private void installMeter(DatapathId dpid, long meterId, long bandwidth, String flowId) {
        try {
            context.getSwitchManager().installMeterForFlow(dpid, bandwidth, meterId);
        } catch (UnsupportedOperationException e) {
            logger.info("Skip meter {} installation for flow {} on switch {}: {}",
                    meterId, flowId, dpid, e.getMessage());
        } catch (SwitchOperationException e) {
            logger.error("Failed to install meter {} for flow {} on switch {}: {}", meterId, flowId, dpid,
                    e.getMessage());
        }

    }

    private void parseRecord(ConsumerRecord<String, String> record) {
        if (handleSpeakerCommand()) {
            return;
        }

        CommandMessage message;
        try {
            String value = record.value();
            // TODO: Prior to Message changes, this MAPPER would read Message ..
            //          but, changed to BaseMessage and got an error wrt "timestamp" ..
            //          so, need to experiment with why CommandMessage can't be read as
            //          a BaseMessage
            message = MAPPER.readValue(value, CommandMessage.class);
        } catch (Exception exception) {
            logger.error("error parsing record '{}'", record.value(), exception);
            return;
        }

        // Process the message within the message correlation context.
        try (CorrelationContextClosable closable = CorrelationContext.create(message.getCorrelationId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Receive command: key={}, payload={}", record.key(), message.getData());
            }

            CommandContext commandContext = new CommandContext(context.getModuleContext(), message.getCorrelationId(),
                    record.key());
            if (!dispatch(commandContext, message)) {
                doControllerMsg(message);
            }
        } catch (Exception exception) {
            logger.error("error processing message '{}'", message, exception);
        }
    }

    private boolean handleSpeakerCommand() {
        SpeakerCommand<SpeakerCommandReport> speakerCommand = null;
        try {
            TypeReference<SpeakerCommand<SpeakerCommandReport>> commandType
                    = new TypeReference<SpeakerCommand<SpeakerCommandReport>>() {};
            speakerCommand = MAPPER.readValue(record.value(), commandType);
        } catch (JsonMappingException e) {
            logger.trace("Received deprecated command message");
            return false;
        } catch (IOException e) {
            logger.error("Error while parsing record {}", record.value(), e);
            return false;
        }

        final MessageContext messageContext = speakerCommand.getMessageContext();
        try (CorrelationContextClosable closable =
                     CorrelationContext.create(messageContext.getCorrelationId())) {
            context.getCommandProcessor().process(speakerCommand, record.key());
        }
        return true;
    }

    @Override
    public void run() {
        parseRecord(record);
    }

    private boolean dispatch(CommandContext commandContext, CommandMessage message) {
        CommandData payload = message.getData();

        for (CommandDispatcher<?> entry : dispatchers) {
            Optional<Command> command = entry.dispatch(commandContext, payload);
            if (!command.isPresent()) {
                continue;
            }

            commandProcessor.process(command.get());
            return true;
        }

        return false;
    }

    private IKafkaProducerService getKafkaProducer() {
        return context.getModuleContext().getServiceImpl(IKafkaProducerService.class);
    }

    public static class Factory {
        private final ConsumerContext context;
        private final List<CommandDispatcher<?>> dispatchers = ImmutableList.of(
                new PingRequestDispatcher(),
                new SetupBfdSessionDispatcher(),
                new RemoveBfdSessionDispatcher(),
                new StatsRequestDispatcher(),
                new ListSwitchDispatcher());

        public Factory(ConsumerContext context) {
            this.context = context;
        }

        public RecordHandler produce(ConsumerRecord<String, String> record) {
            return new RecordHandler(context, dispatchers, record);
        }
    }
}
