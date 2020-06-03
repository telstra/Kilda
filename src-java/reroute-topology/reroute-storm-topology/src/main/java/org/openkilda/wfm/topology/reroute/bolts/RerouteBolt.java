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

package org.openkilda.wfm.topology.reroute.bolts;

import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.FlowPathSwapRequest;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.command.reroute.RerouteAffectedInactiveFlows;
import org.openkilda.messaging.command.reroute.RerouteInactiveFlows;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.reroute.PathSwapResult;
import org.openkilda.messaging.info.reroute.RerouteResultInfoData;
import org.openkilda.messaging.info.reroute.SwitchStateChanged;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.metrics.PushToStreamMeterRegistry;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData;
import org.openkilda.wfm.topology.reroute.service.RerouteService;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.Map;
import java.util.UUID;

@Slf4j
public class RerouteBolt extends AbstractBolt implements MessageSender {

    public static final String FLOW_ID_FIELD = "flow-id";
    public static final String THROTTLING_DATA_FIELD = "throttling-data";
    public static final String BOLT_ID = "reroute-bolt";
    public static final String STREAM_REROUTE_REQUEST_ID = "reroute-request-stream";
    public static final String STREAM_MANUAL_REROUTE_REQUEST_ID = "manual-reroute-request-stream";
    public static final String STREAM_TO_METRICS_BOLT = "to-metrics-bolt-stream";

    public static final String STREAM_OPERATION_QUEUE_ID = "operation-queue";
    public static final Fields FIELDS_OPERATION_QUEUE = new Fields(FLOW_ID_FIELD, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private PersistenceManager persistenceManager;
    private transient RerouteService rerouteService;
    private transient PushToStreamMeterRegistry meterRegistry;

    public RerouteBolt(PersistenceManager persistenceManager, String lifeCycleEventSourceComponent) {
        super(lifeCycleEventSourceComponent);
        this.persistenceManager = persistenceManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.rerouteService = new RerouteService(persistenceManager);
        super.prepare(stormConf, context, collector);

        meterRegistry = new PushToStreamMeterRegistry("kilda.reroute");
        meterRegistry.config().commonTags("bolt_id", this.getComponentId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void handleInput(Tuple tuple) throws PipelineException {
        MeterRegistryHolder.setRegistry(meterRegistry);
        try {
            Message message = pullValue(tuple, FIELD_ID_PAYLOAD, Message.class);
            if (message instanceof CommandMessage) {
                if (active) {
                    handleCommandMessage((CommandMessage) message);
                }
            } else if (message instanceof InfoMessage) {
                handleInfoMessage(message);
            } else {
                unhandledInput(tuple);
            }
        } finally {
            MeterRegistryHolder.removeRegistry();
            meterRegistry.pushMeters(getOutput(), STREAM_TO_METRICS_BOLT);
        }
    }

    private void handleCommandMessage(CommandMessage commandMessage) {
        CommandData commandData = commandMessage.getData();
        String correlationId = getCommandContext().getCorrelationId();
        if (commandData instanceof RerouteAffectedFlows) {
            rerouteService.rerouteAffectedFlows(this, correlationId, (RerouteAffectedFlows) commandData);
        } else if (commandData instanceof RerouteAffectedInactiveFlows) {
            rerouteService.rerouteInactiveAffectedFlows(this, correlationId,
                    ((RerouteAffectedInactiveFlows) commandData).getSwitchId());
        } else if (commandData instanceof RerouteInactiveFlows) {
            rerouteService.rerouteInactiveFlows(this, correlationId, (RerouteInactiveFlows) commandData);
        } else if (commandData instanceof FlowRerouteRequest) {
            rerouteService.processManualRerouteRequest(this, correlationId, (FlowRerouteRequest) commandData);
        } else {
            unhandledInput(getCurrentTuple());
        }
    }

    private void handleInfoMessage(Message message) {
        if (message instanceof InfoMessage) {
            InfoData infoData = ((InfoMessage) message).getData();
            if (infoData instanceof RerouteResultInfoData) {
                RerouteResultInfoData rerouteResultInfoData = (RerouteResultInfoData) infoData;
                emitWithContext(STREAM_OPERATION_QUEUE_ID, getCurrentTuple(),
                        new Values(rerouteResultInfoData.getFlowId(), rerouteResultInfoData));
            } else if (infoData instanceof PathSwapResult) {
                PathSwapResult pathSwapResult = (PathSwapResult) infoData;
                emitWithContext(STREAM_OPERATION_QUEUE_ID, getCurrentTuple(),
                        new Values(pathSwapResult.getFlowId(), pathSwapResult));
            } else if (active && infoData instanceof SwitchStateChanged) {
                rerouteService.processSingleSwitchFlowStatusUpdate((SwitchStateChanged) infoData);
            } else {
                unhandledInput(getCurrentTuple());
            }
        } else {
            unhandledInput(getCurrentTuple());
        }
    }

    /**
     * Emit reroute command for consumer.
     *
     * @param flowId flow id
     * @param flowThrottlingData flow throttling data
     */
    @Override
    public void emitRerouteCommand(String flowId, FlowThrottlingData flowThrottlingData) {
        String newCorrelationId = new CommandContext(flowThrottlingData.getCorrelationId())
                .fork(flowId).getCorrelationId();
        flowThrottlingData.setCorrelationId(newCorrelationId);
        emitWithContext(STREAM_REROUTE_REQUEST_ID, getCurrentTuple(), new Values(flowId, flowThrottlingData));

        log.warn("Flow {} reroute command message sent with correlationId {}, reason \"{}\"",
                flowId, flowThrottlingData.getCorrelationId(), flowThrottlingData.getReason());
    }

    /**
     * Emit manual reroute command for consumer.
     *
     * @param flowId flow id
     * @param flowThrottlingData flow throttling data
     */
    @Override
    public void emitManualRerouteCommand(String flowId, FlowThrottlingData flowThrottlingData) {
        emitWithContext(STREAM_MANUAL_REROUTE_REQUEST_ID, getCurrentTuple(), new Values(flowId, flowThrottlingData));

        log.info("Manual reroute command message sent for flow {}", flowId);
    }

    /**
     * Emit swap command for consumer.
     *
     * @param correlationId correlation id to pass through
     * @param flowId flow
     * @param reason initial reason of path swap
     */
    @Override
    public void emitPathSwapCommand(String correlationId, String flowId, String reason) {
        CommandContext context = new CommandContext(correlationId).fork(UUID.randomUUID().toString());
        emit(STREAM_OPERATION_QUEUE_ID, getCurrentTuple(),
                new Values(flowId, new FlowPathSwapRequest(flowId), context));

        log.warn("Flow {} swap path command message sent with correlationId {}, reason \"{}\"",
                flowId, context.getCorrelationId(), reason);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer output) {
        output.declareStream(STREAM_REROUTE_REQUEST_ID,
                new Fields(FLOW_ID_FIELD, THROTTLING_DATA_FIELD, FIELD_ID_CONTEXT));
        output.declareStream(STREAM_MANUAL_REROUTE_REQUEST_ID,
                new Fields(FLOW_ID_FIELD, THROTTLING_DATA_FIELD, FIELD_ID_CONTEXT));
        output.declareStream(STREAM_OPERATION_QUEUE_ID, FIELDS_OPERATION_QUEUE);
        output.declareStream(ZkStreams.ZK.toString(), new Fields(ZooKeeperBolt.FIELD_ID_STATE,
                ZooKeeperBolt.FIELD_ID_CONTEXT));
        output.declareStream(STREAM_TO_METRICS_BOLT, AbstractTopology.fieldMessage);
    }
}
