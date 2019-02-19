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

package org.openkilda.wfm.topology.discovery.storm.bolt.watcher;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.AbstractOutputAdapter;
import org.openkilda.wfm.error.AbstractException;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.bolt.KafkaEncoder;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.service.DiscoveryWatcherService;
import org.openkilda.wfm.topology.discovery.service.IWatcherCarrier;
import org.openkilda.wfm.topology.discovery.storm.ComponentId;
import org.openkilda.wfm.topology.discovery.storm.bolt.SpeakerMonitor;
import org.openkilda.wfm.topology.discovery.storm.bolt.decisionmaker.command.DecisionMakerCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.decisionmaker.command.DecisionMakerDiscoveryCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.decisionmaker.command.DecisionMakerFailCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.watcher.command.WatcherCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.watchlist.WatchListHandler;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class WatcherHandler extends AbstractBolt {
    public static final String BOLT_ID = ComponentId.WATCHER.toString();

    public static final String FIELD_ID_DATAPATH = WatchListHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = WatchListHandler.FIELD_ID_PORT_NUMBER;
    public static final String FIELD_ID_COMMAND = WatchListHandler.FIELD_ID_COMMAND;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER, FIELD_ID_COMMAND,
                                                          FIELD_ID_CONTEXT);

    public static final String STREAM_SPEAKER_ID = "speaker";
    public static final Fields STREAM_SPEAKER_FIELDS = new Fields(
            KafkaEncoder.FIELD_ID_KEY, KafkaEncoder.FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private final DiscoveryOptions options;

    private transient DiscoveryWatcherService service;

    public WatcherHandler(DiscoveryOptions options) {
        this.options = options;
    }

    @Override
    protected void handleInput(Tuple input) throws AbstractException {
        String source = input.getSourceComponent();
        if (CoordinatorSpout.ID.equals(source)) {
            handleTimerTick(input);
        } else if (SpeakerMonitor.BOLT_ID.equals(source)) {
            handleSpeakerCommand(input);
        } else if (WatchListHandler.BOLT_ID.equals(source)) {
            handleWatchListCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleTimerTick(Tuple input) {
        long timeMs = input.getLongByField(CoordinatorSpout.FIELD_ID_TIME_MS);
        service.tick(new OutputAdapter(this, input), timeMs);
    }

    private void handleSpeakerCommand(Tuple input) throws PipelineException {
        handleCommand(input, SpeakerMonitor.FIELD_ID_COMMAND);
    }

    private void handleWatchListCommand(Tuple input) throws PipelineException {
        handleCommand(input, WatchListHandler.FIELD_ID_COMMAND);
    }

    private void handleCommand(Tuple input, String fieldName) throws PipelineException {
        WatcherCommand command = pullValue(input, fieldName, WatcherCommand.class);
        command.apply(service, new OutputAdapter(this, input));
    }

    @Override
    protected void init() {
        service = new DiscoveryWatcherService(options.getDiscoveryPacketTtl());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        streamManager.declareStream(STREAM_SPEAKER_ID, STREAM_SPEAKER_FIELDS);
    }

    private static class OutputAdapter extends AbstractOutputAdapter implements IWatcherCarrier {
        public OutputAdapter(AbstractBolt owner, Tuple tuple) {
            super(owner, tuple);
        }

        @Override
        public void discoveryReceived(Endpoint endpoint, IslInfoData discoveryEvent, long currentTime) {
            emit(makeDefaultTuple(new DecisionMakerDiscoveryCommand(endpoint, discoveryEvent, currentTime)));
        }

        @Override
        public void discoveryFailed(Endpoint endpoint, long currentTime) {
            emit(makeDefaultTuple(new DecisionMakerFailCommand(endpoint, currentTime)));
        }

        @Override
        public void sendDiscovery(DiscoverIslCommandData discoveryRequest) {
            SwitchId switchId = discoveryRequest.getSwitchId();
            emit(STREAM_SPEAKER_ID, makeSpeakerTuple(switchId.toString(), discoveryRequest));
        }

        private Values makeDefaultTuple(DecisionMakerCommand command) {
            Endpoint endpoint = command.getEndpoint();
            return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getContext());
        }

        private Values makeSpeakerTuple(String key, CommandData payload) {
            return new Values(key, payload, getContext());
        }
    }
}