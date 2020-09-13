/* Copyright 2020 Telstra Open Source
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

package org.openkilda.wfm.topology.flowhs.bolts;

import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_HISTORY_BOLT;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_NB_RESPONSE_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SWAP_ENDPOINTS_HUB_TO_ROUTER_BOLT;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_KEY;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;
import static org.openkilda.wfm.topology.utils.MessageKafkaTranslator.STREAM_FIELDS;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.command.flow.SwapFlowEndpointRequest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.FlowSwapEndpointsHubCarrier;
import org.openkilda.wfm.topology.flowhs.service.FlowSwapEndpointsHubService;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class FlowSwapEndpointsHubBolt extends HubBolt implements FlowSwapEndpointsHubCarrier {

    private final PersistenceManager persistenceManager;
    private transient FlowSwapEndpointsHubService service;
    private String currentKey;

    public FlowSwapEndpointsHubBolt(Config config, PersistenceManager persistenceManager) {
        super(config);
        this.persistenceManager = persistenceManager;
    }

    @Override
    public void init() {
        service = new FlowSwapEndpointsHubService(this, persistenceManager);
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        currentKey = input.getStringByField(FIELD_ID_KEY);
        SwapFlowEndpointRequest data = (SwapFlowEndpointRequest) input.getValueByField(FIELD_ID_PAYLOAD);
        service.handleRequest(currentKey, pullContext(input), data);
    }

    @Override
    protected void onWorkerResponse(Tuple input) throws PipelineException {
        currentKey = input.getStringByField(FIELD_ID_KEY);
        Message message = pullValue(input, FIELD_ID_PAYLOAD, Message.class);
        service.handleAsyncResponse(currentKey, message);
    }

    @Override
    public void onTimeout(String key, Tuple tuple) {
        currentKey = key;
        service.handleTaskTimeout(key);
    }

    @Override
    public void sendFlowUpdateRequest(FlowRequest flowRequest) {
        String childKey = KeyProvider.generateChainedKey(currentKey);
        CommandContext commandContext = getCommandContext();
        CommandMessage commandMessage = new CommandMessage(flowRequest, commandContext.getCreateTime(), childKey);
        emit(Stream.SWAP_ENDPOINTS_HUB_TO_ROUTER_BOLT.name(), getCurrentTuple(),
                new Values(childKey, commandMessage, new CommandContext(commandMessage)));
    }

    @Override
    public void sendNorthboundResponse(Message message) {
        emitWithContext(Stream.HUB_TO_NB_RESPONSE_SENDER.name(), getCurrentTuple(), new Values(currentKey, message));
    }

    @Override
    public void sendSpeakerRequest(FlowSegmentRequest command) {
        log.info("Not implemented for swap flow endpoints operation. Skipping command: {}", command);
    }

    @Override
    public void sendSpeakerDbCommand(DbCommand command) {
        log.info("Not implemented for swap flow endpoints operation. Skipping command: {}", command);
    }

    @Override
    public void sendHistoryUpdate(FlowHistoryHolder historyHolder) {
        emitWithContext(Stream.HUB_TO_HISTORY_BOLT.name(), getCurrentTuple(), new Values(currentKey, historyHolder));
    }

    @Override
    public void sendPeriodicPingNotification(String flowId, boolean enabled) {
        log.info("Not implemented for swap flow endpoints operation. Skipping for the flow {}", flowId);
    }

    @Override
    public void cancelTimeoutCallback(String key) {
        cancelCallback(key);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);

        declarer.declareStream(SWAP_ENDPOINTS_HUB_TO_ROUTER_BOLT.name(), STREAM_FIELDS);
        declarer.declareStream(HUB_TO_NB_RESPONSE_SENDER.name(), STREAM_FIELDS);
        declarer.declareStream(HUB_TO_HISTORY_BOLT.name(), STREAM_FIELDS);
    }
}
