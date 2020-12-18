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

package org.openkilda.wfm.topology.network.storm.bolt.port;

import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.payload.switches.PortPropertiesPayload;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Isl;
import org.openkilda.model.PortProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.history.model.PortHistoryEvent;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.mappers.PortMapper;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.controller.AntiFlapFsm.Config;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.OnlineStatus;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;
import org.openkilda.wfm.topology.network.service.IAntiFlapCarrier;
import org.openkilda.wfm.topology.network.service.IPortCarrier;
import org.openkilda.wfm.topology.network.service.NetworkAntiFlapService;
import org.openkilda.wfm.topology.network.service.NetworkPortService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.decisionmaker.DecisionMakerHandler;
import org.openkilda.wfm.topology.network.storm.bolt.history.command.AntiFlapPortHistoryWithStatsCommand;
import org.openkilda.wfm.topology.network.storm.bolt.history.command.HistoryCommand;
import org.openkilda.wfm.topology.network.storm.bolt.history.command.PortHistoryCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.bcast.ISpeakerBcastConsumer;
import org.openkilda.wfm.topology.network.storm.bolt.sw.SwitchHandler;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslDiscoveryCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslFailCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslPhysicalDownCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslRemoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslRoundTripStatusCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslSetupCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watcher.WatcherHandler;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListPollAddCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListPollRemoveCommand;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.time.Instant;

public class PortHandler extends AbstractBolt implements IPortCarrier, IAntiFlapCarrier, ISpeakerBcastConsumer {
    public static final String BOLT_ID = ComponentId.PORT_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = SwitchHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = "port-number";
    public static final String FIELD_ID_COMMAND = "command";

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_POLL_ID = "poll";
    public static final Fields STREAM_POLL_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_HISTORY_ID = "history";
    private static final Fields STREAM_HISTORY_FIELDS = new Fields(FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public static final String STREAM_NORTHBOUND_ID = "northbound";
    private static final Fields STREAM_NORTHBOUND_FIELDS = new Fields(FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private transient NetworkPortService portService;
    private transient NetworkAntiFlapService antiFlapService;

    private Config antiFlapConfig;
    private PersistenceManager persistenceManager;

    public PortHandler(NetworkOptions options, PersistenceManager persistenceManager,
                       String lifeCycleEventSourceComponent) {
        super(lifeCycleEventSourceComponent);
        this.antiFlapConfig = Config.builder()
                .delayCoolingDown(options.getDelayCoolingDown())
                .delayWarmUp(options.getDelayWarmUp())
                .delayMin(options.getDelayMin())
                .antiFlapStatsDumpingInterval(options.getAntiFlapStatsDumpingInterval())
                .build();
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (CoordinatorSpout.ID.equals(source)) {
            handleTimer();
        } else if (WatcherHandler.BOLT_ID.equals(source)) {
            handleWatcherCommand(input);
        } else if (DecisionMakerHandler.BOLT_ID.equals(source)) {
            handleDecisionMakerCommand(input);
        } else if (SwitchHandler.BOLT_ID.equals(source)) {
            handleSwitchCommand(input);
        } else if (SpeakerRouter.BOLT_ID.equals(source)) {
            handlePortCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleWatcherCommand(Tuple input) throws PipelineException {
        handleCommand(input, WatcherHandler.FIELD_ID_COMMAND);
    }

    private void handleDecisionMakerCommand(Tuple input) throws PipelineException {
        handleCommand(input, DecisionMakerHandler.FIELD_ID_COMMAND);
    }

    private void handleSwitchCommand(Tuple input) throws PipelineException {
        handleCommand(input, SwitchHandler.FIELD_ID_COMMAND);
    }

    private void handlePortCommand(Tuple input) throws PipelineException {
        try {
            handleCommand(input, SpeakerRouter.FIELD_ID_COMMAND);
        } catch (MessageException e) {
            log.error("Handle port command exception", e);
            ErrorData data = new ErrorData(e.getErrorType(), e.getMessage(), e.getErrorDescription());
            emit(STREAM_NORTHBOUND_ID, getCurrentTuple(), new Values(data, getCommandContext()));
        }
    }

    private void handleCommand(Tuple input, String field) throws PipelineException {
        PortCommand command = pullValue(input, field, PortCommand.class);
        command.apply(this);
    }

    private void handleTimer() {
        antiFlapService.tick();
    }

    @Override
    protected void init() {
        portService = new NetworkPortService(this, persistenceManager);
        antiFlapService = new NetworkAntiFlapService(this, antiFlapConfig);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        streamManager.declareStream(STREAM_POLL_ID, STREAM_POLL_FIELDS);
        streamManager.declareStream(STREAM_HISTORY_ID, STREAM_HISTORY_FIELDS);
        streamManager.declareStream(STREAM_NORTHBOUND_ID, STREAM_NORTHBOUND_FIELDS);
    }

    // IPortCarrier

    @Override
    public void setupUniIslHandler(Endpoint endpoint, Isl history) {
        emit(getCurrentTuple(), makeDefaultTuple(new UniIslSetupCommand(endpoint, history)));
    }

    @Override
    public void enableDiscoveryPoll(Endpoint endpoint) {
        emit(STREAM_POLL_ID, getCurrentTuple(), makePollTuple(new WatchListPollAddCommand(endpoint)));
    }

    @Override
    public void disableDiscoveryPoll(Endpoint endpoint) {
        emit(STREAM_POLL_ID, getCurrentTuple(), makePollTuple(new WatchListPollRemoveCommand(endpoint)));
    }

    @Override
    public void notifyPortDiscovered(Endpoint endpoint, IslInfoData speakerDiscoveryEvent) {
        emit(getCurrentTuple(), makeDefaultTuple(new UniIslDiscoveryCommand(endpoint, speakerDiscoveryEvent)));
    }

    @Override
    public void notifyPortDiscoveryFailed(Endpoint endpoint) {
        emit(getCurrentTuple(), makeDefaultTuple(new UniIslFailCommand(endpoint)));
    }

    @Override
    public void notifyPortPhysicalDown(Endpoint endpoint) {
        emit(getCurrentTuple(), makeDefaultTuple(new UniIslPhysicalDownCommand(endpoint)));
    }

    @Override
    public void sendPortStateChangedHistory(Endpoint endpoint, PortHistoryEvent event, Instant time) {
        HistoryCommand command =  new PortHistoryCommand(endpoint, event, time);
        emit(STREAM_HISTORY_ID, getCurrentTuple(), makeHistoryTuple(command));
    }

    @Override
    public void removeUniIslHandler(Endpoint endpoint) {
        emit(getCurrentTuple(), makeDefaultTuple(new UniIslRemoveCommand(endpoint)));
    }

    @Override
    public void notifyPortPropertiesChanged(PortProperties portProperties) {
        PortPropertiesPayload payload = PortMapper.INSTANCE.map(portProperties);
        emit(STREAM_NORTHBOUND_ID, getCurrentTuple(), makePortPropertiesTuple(payload));
    }

    @Override
    public void notifyPortRoundTripStatus(RoundTripStatus roundTripStatus) {
        emit(getCurrentTuple(), makeDefaultTuple(new UniIslRoundTripStatusCommand(roundTripStatus)));
    }

    // IAntiFlapCarrier

    @Override
    public void filteredLinkStatus(Endpoint endpoint, LinkStatus status) {
        portService.updateLinkStatus(endpoint, status);
    }

    @Override
    public void sendAntiFlapPortHistoryEvent(Endpoint endpoint, PortHistoryEvent event, Instant time) {
        HistoryCommand command =  new PortHistoryCommand(endpoint, event, time);
        emit(STREAM_HISTORY_ID, getCurrentTuple(), makeHistoryTuple(command));
    }

    @Override
    public void sendAntiFlapStatsPortHistoryEvent(Endpoint endpoint, PortHistoryEvent event, Instant time,
                                                  int upEvents, int downEvents) {
        HistoryCommand command =  new AntiFlapPortHistoryWithStatsCommand(endpoint, event, time, upEvents, downEvents);
        emit(STREAM_HISTORY_ID, getCurrentTuple(), makeHistoryTuple(command));
    }

    // PortCommand processing

    public void processSetup(Endpoint endpoint, Isl history) {
        portService.setup(endpoint, history);
    }

    public void processRemove(Endpoint endpoint) {
        portService.remove(endpoint);
    }

    public void processUpdateOnlineMode(Endpoint endpoint, OnlineStatus onlineStatus) {
        portService.updateOnlineMode(endpoint, onlineStatus);
    }

    public void processUpdateLinkStatus(Endpoint endpoint, LinkStatus linkStatus) {
        antiFlapService.filterLinkStatus(endpoint, linkStatus);
    }

    public void processDiscovery(Endpoint endpoint, IslInfoData speakerDiscoveryEvent) {
        portService.discovery(endpoint, speakerDiscoveryEvent);
    }

    public void processFail(Endpoint endpoint) {
        portService.fail(endpoint);
    }

    public void updatePortProperties(Endpoint endpoint, boolean discoveryEnabled) {
        portService.updatePortProperties(endpoint, discoveryEnabled);
    }

    public void processRoundTripStatus(RoundTripStatus status) {
        portService.roundTripStatusNotification(status);
    }

    // ISpeakerBcastConsumer

    @Override
    public void activationStatusUpdate(boolean isActive) {
        if (! isActive) {
            antiFlapService.reset();
        }
    }

    @Override
    public void processFeatureTogglesUpdate(FeatureToggles toggles) {
        // no actions required
    }

    // Private

    private Values makeDefaultTuple(UniIslCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }

    private Values makePollTuple(WatchListCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }

    private Values makeHistoryTuple(HistoryCommand command) {
        return new Values(command, getCommandContext());
    }

    private Values makePortPropertiesTuple(PortPropertiesPayload payload) {
        return new Values(payload, getCommandContext());
    }
}
