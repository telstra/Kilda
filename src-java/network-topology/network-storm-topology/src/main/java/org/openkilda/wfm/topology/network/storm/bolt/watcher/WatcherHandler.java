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

package org.openkilda.wfm.topology.network.storm.bolt.watcher;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.bolt.KafkaEncoder;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;
import org.openkilda.wfm.topology.network.service.IWatcherCarrier;
import org.openkilda.wfm.topology.network.service.NetworkWatcherService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.decisionmaker.command.DecisionMakerClearCommand;
import org.openkilda.wfm.topology.network.storm.bolt.decisionmaker.command.DecisionMakerCommand;
import org.openkilda.wfm.topology.network.storm.bolt.decisionmaker.command.DecisionMakerDiscoveryCommand;
import org.openkilda.wfm.topology.network.storm.bolt.decisionmaker.command.DecisionMakerFailCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.command.PortRoundTripStatusCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.watcher.command.WatcherCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.WatchListHandler;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.time.Duration;

public class WatcherHandler extends AbstractBolt implements IWatcherCarrier {
    public static final String BOLT_ID = ComponentId.WATCHER.toString();

    public static final String FIELD_ID_DATAPATH = WatchListHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = WatchListHandler.FIELD_ID_PORT_NUMBER;
    public static final String FIELD_ID_COMMAND = WatchListHandler.FIELD_ID_COMMAND;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER, FIELD_ID_COMMAND,
            FIELD_ID_CONTEXT);

    public static final String STREAM_SPEAKER_ID = "speaker";
    public static final String STREAM_SPEAKER_FLOW_ID = "speaker.flow";
    public static final Fields STREAM_SPEAKER_FIELDS = new Fields(
            KafkaEncoder.FIELD_ID_KEY, KafkaEncoder.FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public static final String STREAM_PORT_ID = "port";
    public static final Fields STREAM_PORT_FIELDS = new Fields(
            FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER, FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_ZOOKEEPER_ID = ZkStreams.ZK.toString();
    public static final Fields STREAM_ZOOKEEPER_FIELDS = new Fields(ZooKeeperBolt.FIELD_ID_STATE,
            ZooKeeperBolt.FIELD_ID_CONTEXT);

    private final NetworkOptions options;

    private transient NetworkWatcherService service;

    public WatcherHandler(NetworkOptions options) {
        this.options = options;
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (CoordinatorSpout.ID.equals(source)) {
            handleTimerTick();
        } else if (ComponentId.INPUT_ZOOKEEPER.toString().equals(source)) {
            handleLifeCycleEvent(input);
        } else if (SpeakerRouter.BOLT_ID.equals(source)) {
            handleSpeakerCommand(input);
        } else if (WatchListHandler.BOLT_ID.equals(source)) {
            handleWatchListCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleTimerTick() {
        service.tick();
    }

    private void handleLifeCycleEvent(Tuple input) {
        LifecycleEvent event = (LifecycleEvent) input.getValueByField(ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT);
        if (event.getSignal().equals(Signal.SHUTDOWN)) {
            service.deactivate();
            emit(STREAM_ZOOKEEPER_ID, input, new Values(event, getCommandContext()));
        } else if (event.getSignal().equals(Signal.START)) {
            service.activate();
            emit(STREAM_ZOOKEEPER_ID, input, new Values(event, getCommandContext()));
        } else {
            log.info("Received signal info %s", event.getSignal());
        }
    }

    private void handleSpeakerCommand(Tuple input) throws PipelineException {
        handleCommand(input, SpeakerRouter.FIELD_ID_COMMAND);
    }

    private void handleWatchListCommand(Tuple input) throws PipelineException {
        handleCommand(input, WatchListHandler.FIELD_ID_COMMAND);
    }

    private void handleCommand(Tuple input, String fieldName) throws PipelineException {
        WatcherCommand command = pullValue(input, fieldName, WatcherCommand.class);
        command.apply(this);
    }

    @Override
    protected void init() {
        Duration roundTripInterval = Duration.ofNanos(options.getDiscoveryRoundTripStatusInterval());
        service = new NetworkWatcherService(this, roundTripInterval, options.getDiscoveryPacketTtl(), getTaskId());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        streamManager.declareStream(STREAM_SPEAKER_ID, STREAM_SPEAKER_FIELDS);
        streamManager.declareStream(STREAM_SPEAKER_FLOW_ID, STREAM_SPEAKER_FIELDS);
        streamManager.declareStream(STREAM_PORT_ID, STREAM_PORT_FIELDS);
        streamManager.declareStream(STREAM_ZOOKEEPER_ID, STREAM_ZOOKEEPER_FIELDS);
    }

    @Override
    public void discoveryReceived(Endpoint endpoint, long packetId, IslInfoData discoveryEvent, long currentTime) {
        emit(getCurrentTuple(), makeDefaultTuple(
                new DecisionMakerDiscoveryCommand(endpoint, packetId, discoveryEvent)));
    }

    @Override
    public void discoveryFailed(Endpoint endpoint, long packetId, long currentTime) {
        emit(getCurrentTuple(), makeDefaultTuple(new DecisionMakerFailCommand(endpoint, packetId)));
    }

    @Override
    public void sendRoundTripStatus(RoundTripStatus status) {
        emit(STREAM_PORT_ID, getCurrentTuple(), makePortTuple(new PortRoundTripStatusCommand(status)));
    }

    @Override
    public void sendDiscovery(DiscoverIslCommandData discoveryRequest) {
        SwitchId switchId = discoveryRequest.getSwitchId();
        emit(STREAM_SPEAKER_ID, getCurrentTuple(), makeSpeakerTuple(switchId.toString(), discoveryRequest));
    }

    @Override
    public void clearDiscovery(Endpoint endpoint) {
        emit(getCurrentTuple(), makeDefaultTuple(new DecisionMakerClearCommand(endpoint)));
    }

    private Values makeDefaultTuple(DecisionMakerCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, forkContextByEndpoint(endpoint));
    }

    private Values makeSpeakerTuple(String key, CommandData payload) {
        return new Values(key, payload, getCommandContext());
    }

    private Values makePortTuple(PortCommand command) {
        Endpoint endpoint = command.getEndpoint();
        CommandContext context = forkContext("round-trip-status", endpoint.toString());
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, context);
    }

    // WatcherCommand

    public void processConfirmation(Endpoint endpoint, long packetId) {
        service.confirmation(endpoint, packetId);
    }

    public void processAddWatch(Endpoint endpoint) {
        service.addWatch(endpoint);
    }

    public void processRemoveWatch(Endpoint endpoint) {
        service.removeWatch(endpoint);
    }

    public void processDiscovery(IslInfoData payload) {
        service.discovery(payload);
    }

    public void processRoundTripDiscovery(Endpoint endpoint, long packetId) {
        service.roundTripDiscovery(endpoint, packetId);
    }

    // -- private/service methods --

    private CommandContext forkContextByEndpoint(Endpoint endpoint) {
        return forkContext("W", endpoint.toString());
    }
}
