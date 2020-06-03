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

package org.openkilda.wfm.topology.nbworker.bolts;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.nbtopology.request.FlowValidationRequest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.topology.nbworker.StreamType;
import org.openkilda.wfm.topology.nbworker.services.FlowValidationHubService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.List;
import java.util.Map;

public class FlowValidationHubBolt extends HubBolt {
    public static final String ID = "flow.validation.hub";
    public static final String INCOME_STREAM = "flow.validation.stream";

    private final PersistenceManager persistenceManager;
    private final FlowResourcesConfig flowResourcesConfig;
    private transient FlowValidationHubService service;
    private long flowMeterMinBurstSizeInKbits;
    private double flowMeterBurstCoefficient;
    private LifecycleEvent deferredShutdownEvent;


    public FlowValidationHubBolt(Config config, PersistenceManager persistenceManager,
                                 FlowResourcesConfig flowResourcesConfig,
                                 long flowMeterMinBurstSizeInKbits, double flowMeterBurstCoefficient) {
        super(config);
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;
        this.flowMeterMinBurstSizeInKbits = flowMeterMinBurstSizeInKbits;
        this.flowMeterBurstCoefficient = flowMeterBurstCoefficient;

        enableMeterRegistry("kilda.flow_validation", StreamType.TO_METRICS_BOLT.name());
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        super.prepare(stormConf, context, collector);
        service = new FlowValidationHubService(persistenceManager, flowResourcesConfig,
                new FlowValidationHubCarrierImpl(null));
    }

    @Override
    protected boolean deactivate(LifecycleEvent event) {
        if (service.deactivate()) {
            return true;
        }
        deferredShutdownEvent = event;
        return false;
    }

    @Override
    protected void activate() {
        service.activate();
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        String key = input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY);
        CommandData data = pullValue(input, MessageKafkaTranslator.FIELD_ID_PAYLOAD, CommandData.class);

        if (data instanceof FlowValidationRequest) {
            service.handleFlowValidationRequest(key, (FlowValidationRequest) data,
                    new FlowValidationHubCarrierImpl(input));
        } else {
            unhandledInput(input);
        }
    }

    @Override
    protected void onWorkerResponse(Tuple input) throws PipelineException {
        String key = KeyProvider.getParentKey(input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY));
        Message message = pullValue(input, MessageKafkaTranslator.FIELD_ID_PAYLOAD, Message.class);
        service.handleAsyncResponse(key, message);
    }

    @Override
    public void onTimeout(String key, Tuple tuple) {
        service.handleTaskTimeout(key);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(StreamType.FLOW_VALIDATION_WORKER.toString(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(StreamType.ERROR.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
        declarer.declareStream(ZkStreams.ZK.toString(),
                new Fields(ZooKeeperBolt.FIELD_ID_STATE, ZooKeeperBolt.FIELD_ID_CONTEXT));
        declarer.declare(new Fields(ResponseSplitterBolt.FIELD_ID_RESPONSE,
                ResponseSplitterBolt.FIELD_ID_CONTEXT));
    }

    private class FlowValidationHubCarrierImpl implements FlowValidationHubCarrier {
        private final Tuple tuple;

        FlowValidationHubCarrierImpl(Tuple tuple) {
            this.tuple = tuple;
        }

        @Override
        public void sendCommandToSpeakerWorker(String key, CommandData commandData) {
            emitWithContext(StreamType.FLOW_VALIDATION_WORKER.toString(), tuple,
                    new Values(KeyProvider.generateChainedKey(key), commandData));
        }

        @Override
        public void sendToResponseSplitterBolt(String key, List<? extends InfoData> message) {
            emit(tuple, new Values(message, key));
        }

        @Override
        public void sendToMessageEncoder(String key, ErrorData errorData) {
            emit(StreamType.ERROR.toString(), tuple, new Values(errorData, key));
        }

        @Override
        public void endProcessing(String key) {
            cancelCallback(key);
        }

        @Override
        public void sendInactive() {
            getOutput().emit(ZkStreams.ZK.toString(), new Values(deferredShutdownEvent, getCommandContext()));
            deferredShutdownEvent = null;

        }

        @Override
        public long getFlowMeterMinBurstSizeInKbits() {
            return flowMeterMinBurstSizeInKbits;
        }

        @Override
        public double getFlowMeterBurstCoefficient() {
            return flowMeterBurstCoefficient;
        }
    }
}
