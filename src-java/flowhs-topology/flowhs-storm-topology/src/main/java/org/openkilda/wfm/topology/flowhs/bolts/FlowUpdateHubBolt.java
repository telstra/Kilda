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
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_PING_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.HUB_TO_SPEAKER_WORKER;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.UPDATE_HUB_TO_SWAP_ENDPOINTS_HUB;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.CreateFlowLoopRequest;
import org.openkilda.messaging.command.flow.DeleteFlowLoopRequest;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.command.flow.PeriodicPingCommand;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.server42.control.messaging.flowrtt.ActivateFlowMonitoringInfoData;
import org.openkilda.server42.control.messaging.flowrtt.DeactivateFlowMonitoringInfoData;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowUpdateHubCarrier;
import org.openkilda.wfm.topology.flowhs.service.FlowUpdateService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import lombok.Builder;
import lombok.Getter;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class FlowUpdateHubBolt extends HubBolt implements FlowUpdateHubCarrier {

    private final FlowUpdateConfig config;
    private final PersistenceManager persistenceManager;
    private final PathComputerConfig pathComputerConfig;
    private final FlowResourcesConfig flowResourcesConfig;

    private transient FlowUpdateService service;
    private String currentKey;

    public FlowUpdateHubBolt(FlowUpdateConfig config, PersistenceManager persistenceManager,
                             PathComputerConfig pathComputerConfig, FlowResourcesConfig flowResourcesConfig) {
        super(config);

        this.config = config;
        this.persistenceManager = persistenceManager;
        this.pathComputerConfig = pathComputerConfig;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    @Override
    protected void init() {
        AvailableNetworkFactory availableNetworkFactory =
                new AvailableNetworkFactory(pathComputerConfig, persistenceManager.getRepositoryFactory());
        PathComputer pathComputer =
                new PathComputerFactory(pathComputerConfig, availableNetworkFactory).getPathComputer();

        FlowResourcesManager resourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        service = new FlowUpdateService(this, persistenceManager, pathComputer, resourcesManager,
                config.getPathAllocationRetriesLimit(),
                config.getPathAllocationRetryDelay(), config.getSpeakerCommandRetriesLimit());
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        currentKey = input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY);
        Object payload = input.getValueByField(FIELD_ID_PAYLOAD);
        if (payload instanceof FlowRequest) {
            FlowRequest flowRequest = (FlowRequest) payload;
            service.handleUpdateRequest(currentKey, pullContext(input), flowRequest);
        } else if (payload instanceof CreateFlowLoopRequest) {
            CreateFlowLoopRequest flowLoopRequest = (CreateFlowLoopRequest) payload;
            service.handleCreateFlowLoopRequest(currentKey, pullContext(input), flowLoopRequest);
        } else if (payload instanceof DeleteFlowLoopRequest) {
            DeleteFlowLoopRequest flowLoopRequest = (DeleteFlowLoopRequest) payload;
            service.handleDeleteFlowLoopRequest(currentKey, pullContext(input), flowLoopRequest);
        } else {
            unhandledInput(input);
        }
    }

    @Override
    protected void onWorkerResponse(Tuple input) {
        String operationKey = input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY);
        currentKey = KeyProvider.getParentKey(operationKey);
        SpeakerFlowSegmentResponse flowResponse = (SpeakerFlowSegmentResponse) input.getValueByField(FIELD_ID_PAYLOAD);
        service.handleAsyncResponse(currentKey, flowResponse);
    }

    @Override
    public void onTimeout(String key, Tuple tuple) {
        currentKey = key;
        service.handleTimeout(key);
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
    public void sendDeactivateFlowMonitoring(String flow, SwitchId srcSwitchId, SwitchId dstSwitchId) {
        //TODO(nmarchenko) move that to some util method to avoid duplicate with delete
        DeactivateFlowMonitoringInfoData payload = DeactivateFlowMonitoringInfoData.builder()
                .flowId(flow).switchId(dstSwitchId).switchId(srcSwitchId).build();

        Message message = new InfoMessage(payload, getCommandContext().getCreateTime(),
                getCommandContext().getCorrelationId());
        emitWithContext(HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name(), getCurrentTuple(),
                new Values(flow, message));
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
    public void sendHubSwapEndpointsResponse(Message message) {
        emitWithContext(Stream.UPDATE_HUB_TO_SWAP_ENDPOINTS_HUB.name(), getCurrentTuple(),
                new Values(KeyProvider.getParentKey(currentKey), message));
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
        declarer.declareStream(UPDATE_HUB_TO_SWAP_ENDPOINTS_HUB.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_HISTORY_BOLT.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_PING_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
    }

    @Getter
    public static class FlowUpdateConfig extends Config {
        private int pathAllocationRetriesLimit;
        private int pathAllocationRetryDelay;
        private int speakerCommandRetriesLimit;

        @Builder(builderMethodName = "flowUpdateBuilder", builderClassName = "flowUpdateBuild")
        public FlowUpdateConfig(String requestSenderComponent, String workerComponent, int timeoutMs, boolean autoAck,
                                int pathAllocationRetriesLimit,
                                int pathAllocationRetryDelay, int speakerCommandRetriesLimit) {
            super(requestSenderComponent, workerComponent, null, timeoutMs, autoAck);
            this.pathAllocationRetriesLimit = pathAllocationRetriesLimit;
            this.pathAllocationRetryDelay = pathAllocationRetryDelay;
            this.speakerCommandRetriesLimit = speakerCommandRetriesLimit;
        }
    }
}
