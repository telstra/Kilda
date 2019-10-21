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

package org.openkilda.wfm.topology.flowhs.bolts;

import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_HISTORY_BOLT;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_NB_RESPONSE_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_SPEAKER_WORKER;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.floodlight.flow.request.SpeakerFlowRequest;
import org.openkilda.floodlight.flow.response.FlowResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.flow.FlowDeleteRequest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream;
import org.openkilda.wfm.topology.flowhs.service.FlowDeleteHubCarrier;
import org.openkilda.wfm.topology.flowhs.service.FlowDeleteService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import lombok.Builder;
import lombok.Getter;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class FlowDeleteHubBolt extends HubBolt implements FlowDeleteHubCarrier {

    private final FlowDeleteConfig config;
    private final PersistenceManager persistenceManager;
    private final FlowResourcesConfig flowResourcesConfig;

    private transient FlowDeleteService service;
    private String currentKey;

    public FlowDeleteHubBolt(FlowDeleteConfig config, PersistenceManager persistenceManager,
                             FlowResourcesConfig flowResourcesConfig) {
        super(config);

        this.config = config;
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    @Override
    protected void init() {
        FlowResourcesManager resourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        service = new FlowDeleteService(this, persistenceManager, resourcesManager,
                config.getTransactionRetriesLimit(), config.getSpeakerCommandRetriesLimit());
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        currentKey = pullKey(input);
        FlowDeleteRequest request = pullValue(input, FIELD_ID_PAYLOAD, FlowDeleteRequest.class);
        service.handleRequest(currentKey, pullContext(input), request.getFlowId());
    }

    @Override
    protected void onWorkerResponse(Tuple input) throws PipelineException {
        String operationKey = pullKey(input);
        currentKey = KeyProvider.getParentKey(operationKey);
        FlowResponse flowResponse = pullValue(input, FIELD_ID_PAYLOAD, FlowResponse.class);
        service.handleAsyncResponse(currentKey, flowResponse);
    }

    @Override
    public void onTimeout(String key, Tuple tuple) {
        currentKey = key;
        service.handleTimeout(key);
    }

    @Override
    public void sendSpeakerRequest(SpeakerFlowRequest command) {
        String commandKey = KeyProvider.joinKeys(command.getCommandId().toString(), currentKey);

        Values values = new Values(commandKey, command);
        emitWithContext(HUB_TO_SPEAKER_WORKER.name(), getCurrentTuple(), values);
    }

    @Override
    public void sendNorthboundResponse(Message message) {
        emitWithContext(Stream.HUB_TO_NB_RESPONSE_SENDER.name(), getCurrentTuple(), new Values(currentKey, message));
    }

    @Override
    public void sendHistoryUpdate(FlowHistoryHolder historyHolder) {
        emitWithContext(Stream.HUB_TO_HISTORY_BOLT.name(), getCurrentTuple(), new Values(currentKey, historyHolder));
    }

    @Override
    public void cancelTimeoutCallback(String key) {
        cancelCallback(key);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);

        declarer.declareStream(HUB_TO_SPEAKER_WORKER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_NB_RESPONSE_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_HISTORY_BOLT.name(), MessageKafkaTranslator.STREAM_FIELDS);
    }

    @Getter
    public static class FlowDeleteConfig extends Config {
        private int transactionRetriesLimit;
        private int speakerCommandRetriesLimit;

        @Builder(builderMethodName = "flowDeleteBuilder", builderClassName = "flowDeleteBuild")
        public FlowDeleteConfig(String requestSenderComponent, String workerComponent, int timeoutMs, boolean autoAck,
                                int transactionRetriesLimit, int speakerCommandRetriesLimit) {
            super(requestSenderComponent, workerComponent, timeoutMs, autoAck);
            this.transactionRetriesLimit = transactionRetriesLimit;
            this.speakerCommandRetriesLimit = speakerCommandRetriesLimit;
        }
    }
}
