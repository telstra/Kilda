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
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_METRICS_BOLT;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_NB_RESPONSE_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_PING_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_SPEAKER_WORKER;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.command.flow.PeriodicPingCommand;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.server42.control.messaging.flowrtt.ActivateFlowMonitoringInfoData;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.metrics.PushToStreamMeterRegistry;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCreateHubCarrier;
import org.openkilda.wfm.topology.flowhs.service.FlowCreateService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import lombok.Builder;
import lombok.Getter;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class FlowCreateHubBolt extends HubBolt implements FlowCreateHubCarrier {

    private final FlowCreateConfig config;
    private final PersistenceManager persistenceManager;
    private final PathComputerConfig pathComputerConfig;
    private final FlowResourcesConfig flowResourcesConfig;

    private transient PushToStreamMeterRegistry meterRegistry;
    private transient FlowCreateService service;
    private String currentKey;

    private LifecycleEvent deferedShutdownEvent;

    public FlowCreateHubBolt(FlowCreateConfig config, PersistenceManager persistenceManager,
                             PathComputerConfig pathComputerConfig, FlowResourcesConfig flowResourcesConfig) {
        super(config);

        this.config = config;
        this.persistenceManager = persistenceManager;
        this.pathComputerConfig = pathComputerConfig;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    @Override
    protected void init() {
        meterRegistry = new PushToStreamMeterRegistry("kilda.flow_create");
        meterRegistry.config().commonTags("bolt_id", this.getComponentId());

        FlowResourcesManager resourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        AvailableNetworkFactory availableNetworkFactory =
                new AvailableNetworkFactory(pathComputerConfig, persistenceManager.getRepositoryFactory());
        PathComputer pathComputer =
                new PathComputerFactory(pathComputerConfig, availableNetworkFactory).getPathComputer();

        service = new FlowCreateService(this, persistenceManager, pathComputer, resourcesManager,
                config.getFlowCreationRetriesLimit(), config.getPathAllocationRetriesLimit(),
                config.getPathAllocationRetryDelay(), config.getSpeakerCommandRetriesLimit());
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
            emit(ZkStreams.ZK.toString(), getCurrentTuple(), new Values(event, getCommandContext()));
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
            meterRegistry.pushMeters(getOutput(), HUB_TO_METRICS_BOLT.name());
        }
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        currentKey = pullKey(input);
        FlowRequest payload = pullValue(input, FIELD_ID_PAYLOAD, FlowRequest.class);
        service.handleRequest(currentKey, pullContext(input), payload);
    }

    @Override
    protected void onWorkerResponse(Tuple input) throws PipelineException {
        String operationKey = pullKey(input);
        currentKey = KeyProvider.getParentKey(operationKey);
        SpeakerFlowSegmentResponse flowResponse = pullValue(input, FIELD_ID_PAYLOAD, SpeakerFlowSegmentResponse.class);
        service.handleAsyncResponse(currentKey, flowResponse);
    }

    @Override
    public void onTimeout(String key, Tuple tuple) {
        currentKey = key;
        service.handleTimeout(key);
    }

    @Override
    public void sendSpeakerRequest(FlowSegmentRequest command) {
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
    public void sendInactive() {
        getOutput().emit(ZkStreams.ZK.toString(), new Values(deferedShutdownEvent, getCommandContext()));
        deferedShutdownEvent = null;
    }

    @Override
    public void sendPeriodicPingNotification(String flowId, boolean enabled) {
        PeriodicPingCommand payload = new PeriodicPingCommand(flowId, enabled);
        Message message = new CommandMessage(payload, getCommandContext().getCreateTime(),
                getCommandContext().getCorrelationId());
        emitWithContext(Stream.HUB_TO_PING_SENDER.name(), getCurrentTuple(), new Values(currentKey, message));
    }

    @Override
    public void sendActivateFlowMonitoring(RequestedFlow flow) {
        ActivateFlowMonitoringInfoData payload = RequestedFlowMapper.INSTANCE.toActivateFlowMonitoringInfoData(flow);

        Message message = new InfoMessage(payload, getCommandContext().getCreateTime(),
                getCommandContext().getCorrelationId());
        emitWithContext(HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name(), getCurrentTuple(),
                new Values(flow.getFlowId(), message));
    }


    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);

        declarer.declareStream(HUB_TO_SPEAKER_WORKER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_NB_RESPONSE_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_HISTORY_BOLT.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_PING_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(ZkStreams.ZK.toString(),
                new Fields(ZooKeeperBolt.FIELD_ID_STATE, ZooKeeperBolt.FIELD_ID_CONTEXT));
        declarer.declareStream(HUB_TO_METRICS_BOLT.name(), AbstractTopology.fieldMessage);
    }

    @Getter
    public static class FlowCreateConfig extends Config {
        private int flowCreationRetriesLimit;
        private int pathAllocationRetriesLimit;
        private int pathAllocationRetryDelay;
        private int speakerCommandRetriesLimit;

        @Builder(builderMethodName = "flowCreateBuilder", builderClassName = "flowCreateBuild")
        public FlowCreateConfig(String requestSenderComponent, String workerComponent, String lifeCycleEventComponent,
                                int timeoutMs, boolean autoAck,
                                int flowCreationRetriesLimit, int pathAllocationRetriesLimit,
                                int pathAllocationRetryDelay, int speakerCommandRetriesLimit) {
            super(requestSenderComponent, workerComponent, lifeCycleEventComponent, timeoutMs, autoAck);
            this.flowCreationRetriesLimit = flowCreationRetriesLimit;
            this.pathAllocationRetriesLimit = pathAllocationRetriesLimit;
            this.pathAllocationRetryDelay = pathAllocationRetryDelay;
            this.speakerCommandRetriesLimit = speakerCommandRetriesLimit;
        }
    }
}
