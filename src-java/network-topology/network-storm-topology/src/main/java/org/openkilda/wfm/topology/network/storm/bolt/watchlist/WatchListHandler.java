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

package org.openkilda.wfm.topology.network.storm.bolt.watchlist;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.service.IWatchListCarrier;
import org.openkilda.wfm.topology.network.service.NetworkWatchListService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.port.PortHandler;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.UniIslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.watcher.command.WatcherAddCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watcher.command.WatcherCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watcher.command.WatcherRemoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListCommand;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class WatchListHandler extends AbstractBolt implements IWatchListCarrier {
    public static final String BOLT_ID = ComponentId.WATCH_LIST.toString();

    public static final String FIELD_ID_DATAPATH = PortHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = PortHandler.FIELD_ID_PORT_NUMBER;
    public static final String FIELD_ID_COMMAND = PortHandler.FIELD_ID_COMMAND;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER, FIELD_ID_COMMAND,
            FIELD_ID_CONTEXT);

    public static final String STREAM_ZOOKEEPER_ID = ZkStreams.ZK.toString();
    public static final Fields STREAM_ZOOKEEPER_FIELDS = new Fields(ZooKeeperBolt.FIELD_ID_STATE,
            ZooKeeperBolt.FIELD_ID_CONTEXT);

    private final NetworkOptions options;

    private transient NetworkWatchListService service;

    public WatchListHandler(NetworkOptions options) {
        this.options = options;
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (CoordinatorSpout.ID.equals(source)) {
            handleTimer();
        } else if (ComponentId.INPUT_ZOOKEEPER.toString().equals(source)) {
            handleLifeCycleEvent(input);
        } else if (PortHandler.BOLT_ID.equals(source)) {
            handlePortCommand(input);
        } else if (UniIslHandler.BOLT_ID.equals(source)) {
            handleUniIslCommand(input);
        } else if (IslHandler.BOLT_ID.equals(source)) {
            handleIslCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleTimer() {
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

    private void handlePortCommand(Tuple input) throws PipelineException {
        handleCommand(input, PortHandler.FIELD_ID_COMMAND);
    }

    private void handleUniIslCommand(Tuple input) throws PipelineException {
        handleCommand(input, UniIslHandler.FIELD_ID_COMMAND);
    }

    private void handleIslCommand(Tuple input) throws PipelineException {
        handleCommand(input, IslHandler.FIELD_ID_COMMAND);
    }

    private void handleCommand(Tuple input, String fieldId) throws PipelineException {
        WatchListCommand command = pullValue(input, fieldId, WatchListCommand.class);
        command.apply(this);
    }

    @Override
    protected void init() {
        service = new NetworkWatchListService(this, options.getDiscoveryGenericInterval(),
                options.getDiscoveryExhaustedInterval(), options.getDiscoveryAuxiliaryInterval());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        streamManager.declareStream(STREAM_ZOOKEEPER_ID, STREAM_ZOOKEEPER_FIELDS);
    }

    @Override
    public void watchRemoved(Endpoint endpoint) {
        emit(getCurrentTuple(), makeDefaultTuple(new WatcherRemoveCommand(endpoint)));
    }

    @Override
    public void discoveryRequest(Endpoint endpoint, long currentTime) {
        emit(getCurrentTuple(), makeDefaultTuple(new WatcherAddCommand(endpoint)));
    }

    private Values makeDefaultTuple(WatcherCommand command) {
        Endpoint endpoint = command.getEndpoint();
        CommandContext forkedContext = getCommandContext()
                .fork(endpoint.getDatapath().toString())
                .fork(String.format("p%d", endpoint.getPortNumber()));
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, forkedContext);
    }

    // WatchListCommand

    public void processAddWatch(Endpoint endpoint) {
        service.addWatch(endpoint);
    }

    public void processRemoveWatch(Endpoint endpoint) {
        service.removeWatch(endpoint);
    }

    public void processUpdateExhaustedPollMode(Endpoint endpoint, boolean enable) {
        service.updateExhaustedPollMode(endpoint, enable);
    }

    public void processUpdateAuxiliaryPollMode(Endpoint endpoint, boolean enable) {
        service.updateAuxiliaryPollMode(endpoint, enable);
    }
}
