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
import org.openkilda.bluegreen.Signal;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.nbtopology.request.FlowValidationRequest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.metrics.PushToStreamMeterRegistry;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.topology.AbstractTopology;
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

    private transient PushToStreamMeterRegistry meterRegistry;
    private transient FlowValidationHubService service;
    private long flowMeterMinBurstSizeInKbits;
    private double flowMeterBurstCoefficient;
    private LifecycleEvent deferedShutdownEvent;


    public FlowValidationHubBolt(Config config, PersistenceManager persistenceManager,
                                 FlowResourcesConfig flowResourcesConfig,
                                 long flowMeterMinBurstSizeInKbits, double flowMeterBurstCoefficient) {
        super(config);
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;
        this.flowMeterMinBurstSizeInKbits = flowMeterMinBurstSizeInKbits;
        this.flowMeterBurstCoefficient = flowMeterBurstCoefficient;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        super.prepare(stormConf, context, collector);

        meterRegistry = new PushToStreamMeterRegistry("kilda.flow_validation");
        meterRegistry.config().commonTags("bolt_id", this.getComponentId());

        service = new FlowValidationHubService(persistenceManager, flowResourcesConfig,
                new FlowValidationHubCarrierImpl(null));
    }

    @Override
    protected void handleLifeCycleEvent(LifecycleEvent event) {
        if (event.getSignal().equals(Signal.SHUTDOWN)) {
            if (service.deactivate()) {
                emit(ZkStreams.ZK.toString(), getCurrentTuple(), new Values(event, getCommandContext()));
            } else {
                deferedShutdownEvent = event;
            }
        } else if (event.getSignal().equals(Signal.START)) {
            service.activate();
            emit(ZkStreams.ZK.toString(), new Values(event, getCommandContext()));
        } else {
            log.info("Received signal info {}", event.getSignal());
        }
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        MeterRegistryHolder.setRegistry(meterRegistry);
        try {
            super.handleInput(input);
        } finally {
            MeterRegistryHolder.removeRegistry();
            meterRegistry.pushMeters(getOutput(), StreamType.TO_METRICS_BOLT.name());
        }
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
        declarer.declareStream(StreamType.TO_METRICS_BOLT.name(), AbstractTopology.fieldMessage);
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
            getOutput().emit(ZkStreams.ZK.toString(), new Values(deferedShutdownEvent, getCommandContext()));
            deferedShutdownEvent = null;

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
