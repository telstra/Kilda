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

package org.openkilda.wfm.topology.switchmanager.bolt;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.switches.SwitchRulesDeleteRequest;
import org.openkilda.messaging.command.switches.SwitchRulesInstallRequest;
import org.openkilda.messaging.command.switches.SwitchValidateRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.flow.FlowInstallResponse;
import org.openkilda.messaging.info.flow.FlowReinstallResponse;
import org.openkilda.messaging.info.flow.FlowRemoveResponse;
import org.openkilda.messaging.info.meter.SwitchMeterData;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.meter.SwitchMeterUnsupported;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultFlowEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.info.rule.SwitchGroupEntries;
import org.openkilda.messaging.info.switches.DeleteMeterResponse;
import org.openkilda.messaging.info.switches.SwitchRulesResponse;
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
import org.openkilda.wfm.topology.switchmanager.StreamType;
import org.openkilda.wfm.topology.switchmanager.SwitchManagerTopologyConfig;
import org.openkilda.wfm.topology.switchmanager.model.ValidationResult;
import org.openkilda.wfm.topology.switchmanager.service.SwitchManagerCarrier;
import org.openkilda.wfm.topology.switchmanager.service.SwitchRuleService;
import org.openkilda.wfm.topology.switchmanager.service.SwitchSyncService;
import org.openkilda.wfm.topology.switchmanager.service.SwitchValidateService;
import org.openkilda.wfm.topology.switchmanager.service.impl.SwitchRuleServiceImpl;
import org.openkilda.wfm.topology.switchmanager.service.impl.SwitchSyncServiceImpl;
import org.openkilda.wfm.topology.switchmanager.service.impl.SwitchValidateServiceImpl;
import org.openkilda.wfm.topology.switchmanager.service.impl.ValidationServiceImpl;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.Map;

public class SwitchManagerHub extends HubBolt implements SwitchManagerCarrier {
    public static final String ID = "switch.manager.hub";

    public static final String INCOME_STREAM = "switch.manage.command";

    public static final String NORTHBOUND_STREAM_ID = StreamType.TO_NORTHBOUND.toString();
    public static final Fields NORTHBOUND_STREAM_FIELDS = new Fields(
            MessageKafkaTranslator.FIELD_ID_KEY, MessageKafkaTranslator.FIELD_ID_PAYLOAD);

    public static final String ZOOKEEPER_STREAM_ID = ZkStreams.ZK.toString();
    public static final Fields ZOOKEEPER_STREAM_FIELDS = new Fields(
            ZooKeeperBolt.FIELD_ID_STATE, ZooKeeperBolt.FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;
    private final FlowResourcesConfig flowResourcesConfig;
    private final SwitchManagerTopologyConfig topologyConfig;
    private transient SwitchValidateService validateService;
    private transient SwitchSyncService syncService;
    private transient SwitchRuleService switchRuleService;
    private transient PushToStreamMeterRegistry meterRegistry;

    private LifecycleEvent differedShutdownEvent;

    public SwitchManagerHub(HubBolt.Config hubConfig, PersistenceManager persistenceManager,
                            SwitchManagerTopologyConfig topologyConfig,
                            FlowResourcesConfig flowResourcesConfig) {
        super(hubConfig);
        this.persistenceManager = persistenceManager;
        this.topologyConfig = topologyConfig;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        super.prepare(stormConf, context, collector);

        meterRegistry = new PushToStreamMeterRegistry("kilda.switch_validate");
        meterRegistry.config().commonTags("bolt_id", this.getComponentId());

        validateService = new SwitchValidateServiceImpl(
                this, persistenceManager, new ValidationServiceImpl(persistenceManager, topologyConfig));
        syncService = new SwitchSyncServiceImpl(this, persistenceManager, flowResourcesConfig);
        switchRuleService = new SwitchRuleServiceImpl(this, persistenceManager.getRepositoryFactory());
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        MeterRegistryHolder.setRegistry(meterRegistry);
        try {
            super.handleInput(input);
        } finally {
            MeterRegistryHolder.removeRegistry();
            meterRegistry.pushMeters(getOutput(), StreamType.HUB_TO_METRICS_BOLT.name());
        }
    }

    @Override
    protected void onRequest(Tuple input) throws PipelineException {
        if (!active) {
            log.info("Switch Manager Topology is inactive");
            return;
        }

        String key = input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY);
        CommandMessage message = pullValue(input, MessageKafkaTranslator.FIELD_ID_PAYLOAD, CommandMessage.class);

        CommandData data = message.getData();
        if (data instanceof SwitchValidateRequest) {
            validateService.handleSwitchValidateRequest(key, (SwitchValidateRequest) data);
        } else if (data instanceof SwitchRulesDeleteRequest) {
            switchRuleService.deleteRules(key, (SwitchRulesDeleteRequest) data);
        } else if (data instanceof SwitchRulesInstallRequest) {
            switchRuleService.installRules(key, (SwitchRulesInstallRequest) data);
        } else {
            log.warn("Receive unexpected CommandMessage for key {}: {}", key, data);
        }
    }

    private void handleMetersResponse(String key, SwitchMeterData data) {
        if (data instanceof SwitchMeterEntries) {
            validateService.handleMeterEntriesResponse(key, (SwitchMeterEntries) data);
        } else if (data instanceof SwitchMeterUnsupported) {
            validateService.handleMetersUnsupportedResponse(key);
        } else {
            log.warn("Receive unexpected SwitchMeterData for key {}: {}", key, data);
        }
    }

    @Override
    protected void onWorkerResponse(Tuple input) throws PipelineException {
        String key = KeyProvider.getParentKey(input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY));
        Message message = pullValue(input, MessageKafkaTranslator.FIELD_ID_PAYLOAD, Message.class);

        if (message instanceof InfoMessage) {
            InfoData data = ((InfoMessage) message).getData();
            if (data instanceof SwitchFlowEntries) {
                validateService.handleFlowEntriesResponse(key, (SwitchFlowEntries) data);
            } else if (data instanceof SwitchExpectedDefaultFlowEntries) {
                validateService.handleExpectedDefaultFlowEntriesResponse(key, (SwitchExpectedDefaultFlowEntries) data);
            } else if (data instanceof SwitchExpectedDefaultMeterEntries) {
                validateService.handleExpectedDefaultMeterEntriesResponse(key,
                        (SwitchExpectedDefaultMeterEntries) data);
            } else if (data instanceof SwitchGroupEntries) {
                validateService.handleGroupEntriesResponse(key, (SwitchGroupEntries) data);
            } else if (data instanceof SwitchMeterData) {
                handleMetersResponse(key, (SwitchMeterData) data);
            } else if (data instanceof FlowInstallResponse) {
                syncService.handleInstallRulesResponse(key);
            } else if (data instanceof FlowRemoveResponse) {
                syncService.handleRemoveRulesResponse(key);
            } else if (data instanceof FlowReinstallResponse) {
                syncService.handleReinstallDefaultRulesResponse(key, (FlowReinstallResponse) data);
            } else if (data instanceof DeleteMeterResponse) {
                syncService.handleRemoveMetersResponse(key);
            } else if (data instanceof SwitchRulesResponse) {
                switchRuleService.rulesResponse(key, (SwitchRulesResponse) data);
            } else {
                log.warn("Receive unexpected InfoData for key {}: {}", key, data);
            }
        } else if (message instanceof ErrorMessage) {
            log.warn("Receive ErrorMessage for key {}", key);
            validateService.handleTaskError(key, (ErrorMessage) message);
            syncService.handleTaskError(key, (ErrorMessage) message);
        }
    }

    @Override
    public void onTimeout(String key, Tuple tuple) {
        log.warn("Receive TaskTimeout for key {}", key);
        validateService.handleTaskTimeout(key);
        syncService.handleTaskTimeout(key);
    }

    @Override
    protected void handleLifeCycleEvent(LifecycleEvent event) {
        if (event.getSignal().equals(Signal.SHUTDOWN)) {
            if (validateService.deactivate() && syncService.deactivate() && switchRuleService.deactivate()) {
                emitWithContext(ZOOKEEPER_STREAM_ID, getCurrentTuple(), new Values(event));
            } else {
                differedShutdownEvent = event;
            }
            active = false;
        } else if (event.getSignal().equals(Signal.START)) {
            validateService.activate();
            syncService.activate();
            switchRuleService.activate();
            active = true;
            emitWithContext(ZOOKEEPER_STREAM_ID, getCurrentTuple(), new Values(event));
        } else {
            log.info("Received signal info {}", event.getSignal());
        }
    }

    @Override
    public void cancelTimeoutCallback(String key) {
        cancelCallback(key);
    }

    @Override
    public void sendCommandToSpeaker(String key, CommandData command) {
        emit(SpeakerWorkerBolt.INCOME_STREAM, getCurrentTuple(), makeWorkerTuple(key, command));
    }

    @Override
    public void response(String key, Message message) {
        emit(NORTHBOUND_STREAM_ID, getCurrentTuple(), makeNorthboundTuple(key, message));
    }

    @Override
    public void errorResponse(String key, ErrorType error, String message) {
        ErrorData payload = new ErrorData(error, message, "Error in switch validation/synchronisation");
        response(key, new ErrorMessage(payload, System.currentTimeMillis(), key));
    }

    @Override
    public void runSwitchSync(String key, SwitchValidateRequest request, ValidationResult validationResult) {
        syncService.handleSwitchSync(key, request, validationResult);
    }

    @Override
    public void sendInactive() {
        if (validateService.isAllOperationsCompleted()
                && syncService.isAllOperationsCompleted()
                && switchRuleService.isAllOperationsCompleted()) {
            getOutput().emit(ZOOKEEPER_STREAM_ID, new Values(differedShutdownEvent, getCommandContext()));
            differedShutdownEvent = null;
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(SpeakerWorkerBolt.INCOME_STREAM, MessageKafkaTranslator.STREAM_FIELDS);
        declarer.declareStream(NORTHBOUND_STREAM_ID, NORTHBOUND_STREAM_FIELDS);
        declarer.declareStream(ZOOKEEPER_STREAM_ID, ZOOKEEPER_STREAM_FIELDS);
        declarer.declareStream(StreamType.HUB_TO_METRICS_BOLT.name(), AbstractTopology.fieldMessage);
    }

    private Values makeWorkerTuple(String key, CommandData payload) {
        return new Values(KeyProvider.generateChainedKey(key), payload, getCommandContext());
    }

    private Values makeNorthboundTuple(String key, Message payload) {
        return new Values(key, payload);
    }
}
