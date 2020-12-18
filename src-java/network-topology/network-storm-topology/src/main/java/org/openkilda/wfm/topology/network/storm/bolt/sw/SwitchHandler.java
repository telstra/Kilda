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

package org.openkilda.wfm.topology.network.storm.bolt.sw;

import org.openkilda.messaging.MessageData;
import org.openkilda.messaging.command.reroute.RerouteAffectedInactiveFlows;
import org.openkilda.messaging.error.rule.SwitchSyncErrorData;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.info.reroute.SwitchStateChanged;
import org.openkilda.messaging.info.switches.SwitchSyncResponse;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.Isl;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.bolt.KafkaEncoder;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.OnlineStatus;
import org.openkilda.wfm.topology.network.model.facts.HistoryFacts;
import org.openkilda.wfm.topology.network.service.ISwitchCarrier;
import org.openkilda.wfm.topology.network.service.NetworkSwitchService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.NetworkPersistedStateImportHandler;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubLinkStatusCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubOnlineStatusUpdateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubPortAddCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubPortDeleteCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubSwitchRemovedNotificationCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortLinkStatusCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortOnlineModeCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortRemoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortSetupCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.sw.command.SwitchCommand;
import org.openkilda.wfm.topology.network.storm.bolt.swmanager.SwitchManagerWorker;
import org.openkilda.wfm.topology.network.storm.bolt.swmanager.command.SwitchManagerSynchronizeSwitchCommand;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class SwitchHandler extends AbstractBolt implements ISwitchCarrier {
    public static final String BOLT_ID = ComponentId.SWITCH_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = SpeakerRouter.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = "port-number";
    public static final String FIELD_ID_COMMAND = "command";

    public static final String FIELD_ID_KEY = MessageKafkaTranslator.FIELD_ID_KEY;

    public static final String STREAM_SWMANAGER_ID = "swmanager";
    public static final Fields STREAM_SWMANAGER_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_PORT_ID = "port";
    public static final Fields STREAM_PORT_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_BFD_HUB_ID = "bfd-port";
    public static final Fields STREAM_BFD_HUB_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_COMMAND,
            FIELD_ID_CONTEXT);

    public static final String STREAM_REROUTE_ID = "reroute";
    public static final Fields STREAM_REROUTE_FIELDS = new Fields(
            KafkaEncoder.FIELD_ID_KEY, KafkaEncoder.FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private final NetworkOptions options;
    private final PersistenceManager persistenceManager;

    private transient NetworkSwitchService service;

    public SwitchHandler(NetworkOptions options, PersistenceManager persistenceManager) {
        this.options = options;
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void handleInput(Tuple input) throws PipelineException {
        String source = input.getSourceComponent();

        if (SpeakerRouter.BOLT_ID.equals(source)) {
            handleSpeakerInput(input);
        } else if (SwitchManagerWorker.BOLT_ID.equals(source)) {
            handleSwitchManagerWorkerInput(input);
        } else if (NetworkPersistedStateImportHandler.BOLT_ID.equals(source)) {
            handleHistoryImport(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleHistoryImport(Tuple input) throws PipelineException {
        handleCommand(input, NetworkPersistedStateImportHandler.FIELD_ID_PAYLOAD);
    }

    private void handleSpeakerInput(Tuple input) throws PipelineException {
        handleCommand(input, SpeakerRouter.FIELD_ID_COMMAND);
    }

    private void handleSwitchManagerWorkerInput(Tuple input) throws PipelineException {
        handleCommand(input, SwitchManagerWorker.FIELD_ID_PAYLOAD);
    }

    private void handleCommand(Tuple input, String field) throws PipelineException {
        SwitchCommand command = pullValue(input, field, SwitchCommand.class);
        command.apply(this);
    }

    @Override
    protected void init() {
        service = new NetworkSwitchService(this, persistenceManager, options);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_PORT_ID, STREAM_PORT_FIELDS);
        streamManager.declareStream(STREAM_BFD_HUB_ID, STREAM_BFD_HUB_FIELDS);
        streamManager.declareStream(STREAM_SWMANAGER_ID, STREAM_SWMANAGER_FIELDS);
        streamManager.declareStream(STREAM_REROUTE_ID, STREAM_REROUTE_FIELDS);
    }

    @Override
    public void setupPortHandler(Endpoint endpoint, Isl history) {
        emit(STREAM_PORT_ID, getCurrentTuple(), makePortTuple(new PortSetupCommand(endpoint, history)));
    }

    @Override
    public void removePortHandler(Endpoint endpoint) {
        emit(STREAM_PORT_ID, getCurrentTuple(), makePortTuple(new PortRemoveCommand(endpoint)));
    }

    @Override
    public void setOnlineMode(Endpoint endpoint, OnlineStatus onlineStatus) {
        emit(STREAM_PORT_ID, getCurrentTuple(), makePortTuple(new PortOnlineModeCommand(endpoint, onlineStatus)));
    }

    @Override
    public void setPortLinkMode(Endpoint endpoint, LinkStatus linkStatus) {
        emit(STREAM_PORT_ID, getCurrentTuple(), makePortTuple(new PortLinkStatusCommand(endpoint, linkStatus)));
    }

    @Override
    public void sendBfdPortAdd(Endpoint endpoint, int physicalPortNumber) {
        emit(STREAM_BFD_HUB_ID, getCurrentTuple(), makeBfdHubTuple(
                new BfdHubPortAddCommand(endpoint, physicalPortNumber)));
    }

    @Override
    public void sendBfdPortDelete(Endpoint logicalEndpoint) {
        emit(STREAM_BFD_HUB_ID, getCurrentTuple(), makeBfdHubTuple(new BfdHubPortDeleteCommand(logicalEndpoint)));
    }

    @Override
    public void sendBfdLinkStatusUpdate(Endpoint logicalEndpoint, LinkStatus linkStatus) {
        emit(STREAM_BFD_HUB_ID, getCurrentTuple(), makeBfdHubTuple(
                new BfdHubLinkStatusCommand(logicalEndpoint, linkStatus)));
    }

    @Override
    public void sendSwitchSynchronizeRequest(String key, SwitchId switchId) {
        emit(STREAM_SWMANAGER_ID, getCurrentTuple(), makeSwitchManagerWorkerTuple(key, switchId));
    }

    @Override
    public void sendAffectedFlowRerouteRequest(SwitchId switchId) {
        emit(STREAM_REROUTE_ID, getCurrentTuple(), makeRerouteTuple(switchId,
                new RerouteAffectedInactiveFlows(switchId)));
    }

    @Override
    public void sendSwitchStateChanged(SwitchId switchId, SwitchStatus status) {
        emit(STREAM_BFD_HUB_ID, getCurrentTuple(), makeBfdHubTuple(
                new BfdHubOnlineStatusUpdateCommand(switchId, status == SwitchStatus.ACTIVE)));
        emit(STREAM_REROUTE_ID, getCurrentTuple(), makeRerouteTuple(switchId,
                new SwitchStateChanged(switchId, status)));
    }

    @Override
    public void switchRemovedNotification(SwitchId switchId) {
        emit(STREAM_BFD_HUB_ID, getCurrentTuple(), makeBfdHubTuple(
                new BfdHubSwitchRemovedNotificationCommand(switchId)));
    }

    private Values makePortTuple(PortCommand command) {
        Endpoint endpoint = command.getEndpoint();
        CommandContext context = forkContext(endpoint.toString());
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, context);
    }

    private Values makeBfdHubTuple(BfdHubCommand command) {
        CommandContext context = forkContext(command.getWorkflowQualifier());
        return new Values(command.getSwitchId(), command, context);
    }

    private Values makeSwitchManagerWorkerTuple(String key, SwitchId switchId) {
        return new Values(
                key, new SwitchManagerSynchronizeSwitchCommand(key, switchId), getCommandContext().fork("sync"));
    }

    private Values makeRerouteTuple(SwitchId switchId, MessageData payload) {
        return new Values(switchId.toString(), payload,
                getCommandContext().fork("reroute"));
    }

    // SwitchCommand processing

    public void processSwitchEvent(SwitchInfoData payload) {
        service.switchEvent(payload);
    }

    public void processSwitchManagerResponse(SwitchSyncResponse payload) {
        service.switchManagerResponse(payload, getKey());
    }

    public void processSwitchManagerErrorResponse(SwitchSyncErrorData payload) {
        service.switchManagerErrorResponse(payload, getKey());
    }

    public void processSwitchManagerTimeout(SwitchId switchId) {
        service.switchManagerTimeout(switchId, getKey());
    }

    private String getKey() {
        return getCurrentTuple().getStringByField(FIELD_ID_KEY);
    }

    public void processSwitchBecomeUnmanaged(SwitchId datapath) {
        service.switchBecomeUnmanaged(datapath);
    }

    public void processSwitchBecomeManaged(SpeakerSwitchView switchView) {
        service.switchBecomeManaged(switchView);
    }

    public void processSwitchAddWithHistory(HistoryFacts history) {
        service.switchAddWithHistory(history);
    }

    public void processSwitchPortEvent(PortInfoData payload) {
        service.switchPortEvent(payload);
    }

    public void processRemoveSwitch(SwitchId datapath) {
        service.remove(datapath);
    }
}
