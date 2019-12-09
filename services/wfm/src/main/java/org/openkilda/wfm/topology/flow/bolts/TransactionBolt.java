/* Copyright 2017 Telstra Open Source
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

package org.openkilda.wfm.topology.flow.bolts;

import static java.util.Objects.requireNonNull;
import static org.openkilda.messaging.Utils.MAPPER;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.BatchCommandsRequest;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandGroup;
import org.openkilda.messaging.command.CommandGroup.FailureReaction;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.BaseFlow;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.DeleteMeterRequest;
import org.openkilda.messaging.command.switches.RemoveExclusionRequest;
import org.openkilda.messaging.command.switches.RemoveTelescopeRuleRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.rule.FlowCommandErrorData;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.flow.ComponentType;
import org.openkilda.wfm.topology.flow.FlowTopology;
import org.openkilda.wfm.topology.flow.StreamType;
import org.openkilda.wfm.topology.flow.transactions.FlowCommandRegistry;
import org.openkilda.wfm.topology.flow.transactions.UnknownBatchException;
import org.openkilda.wfm.topology.flow.transactions.UnknownTransactionException;
import org.openkilda.wfm.topology.utils.AbstractTickRichBolt;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Transaction Bolt. Tracks OpenFlow Speaker commands transactions.
 */
public class TransactionBolt extends AbstractTickRichBolt {
    private static final Logger logger = LoggerFactory.getLogger(TransactionBolt.class);

    private static final String STREAM_ID_CTRL = "ctrl";

    private final Duration transactionExpirationTime;
    private transient FlowCommandRegistry flowCommandRegistry;

    public TransactionBolt(Duration transactionExpirationTime) {
        this.transactionExpirationTime = requireNonNull(transactionExpirationTime);
    }

    @Override
    public void doWork(Tuple tuple) {
        logger.debug("Request tuple: {}", tuple);

        ComponentType componentId = ComponentType.valueOf(tuple.getSourceComponent());
        Message message = (Message) tuple.getValueByField(FlowTopology.MESSAGE_FIELD);
        String flowId = tuple.getStringByField(Utils.FLOW_ID);

        try {
            switch (componentId) {
                case CRUD_BOLT:
                case FLOW_OPERATION_BOLT:
                    if (message instanceof CommandMessage) {
                        CommandData data = ((CommandMessage) message).getData();
                        if (data instanceof BatchCommandsRequest) {
                            registerBatch(flowId, (BatchCommandsRequest) data);

                            processCurrentBatch(flowId, tuple, message.getCorrelationId());
                        } else {
                            logger.error("Skip undefined command message: {}", message);
                        }
                    } else {
                        logger.error("Skip undefined message: {}", message);
                    }
                    break;

                case SPEAKER_BOLT:
                    if (message instanceof CommandMessage) {
                        CommandData data = ((CommandMessage) message).getData();
                        if (data instanceof BaseFlow) {
                            onSuccessfulCommand(flowId, (BaseFlow) data, tuple, message.getCorrelationId());
                        } else {
                            logger.error("Skip undefined command message: {}", message);
                        }

                    } else if (message instanceof ErrorMessage) {
                        ErrorData data = ((ErrorMessage) message).getData();
                        if (data instanceof FlowCommandErrorData) {
                            onFailedCommand(flowId, (FlowCommandErrorData) data, tuple, message.getCorrelationId());
                        } else {
                            logger.error("Skip undefined error message: {}", message);
                        }

                    } else {
                        logger.error("Skip undefined message: {}", message);
                    }
                    break;

                default:
                    logger.error("Unexpected component: {} in {}", componentId, tuple);
                    break;
            }

        } catch (UnknownTransactionException | UnknownBatchException e) {
            logger.error("Skip command message as the batch not found: {}", message, e);
        } catch (JsonProcessingException e) {
            logger.error("Could not serialize message", e);
        } catch (Exception e) {
            logger.error("Unhandled exception", e);
        } finally {
            outputCollector.ack(tuple);

            logger.debug("Transaction message ack: {}", tuple);
        }
    }

    private void registerBatch(String flowId, BatchCommandsRequest data) {
        List<CommandGroup> commandGroups = data.getGroups();
        UUID batchId = flowCommandRegistry.registerBatch(flowId, commandGroups,
                data.getOnSuccessCommands(), data.getOnFailureCommands());

        logger.info("Flow commands were registered as the batch {}: {}={}, {}", batchId,
                Utils.FLOW_ID, flowId, commandGroups);
    }

    private void processCurrentBatch(String flowId, Tuple tuple, String correlationId)
            throws JsonProcessingException, UnknownBatchException {
        Optional<UUID> batchId;
        while ((batchId = flowCommandRegistry.getCurrentBatch(flowId)).isPresent()) {
            if (flowCommandRegistry.isBatchEmpty(batchId.get())) {
                onCompletedBatch(batchId.get(), tuple, correlationId);
            }

            List<CommandData> groupCommands = flowCommandRegistry.pollNextGroup(flowId);
            if (groupCommands.isEmpty()) {
                // There's a non-completed transaction.
                return;
            }

            for (CommandData command : groupCommands) {
                if (command instanceof BaseFlow || command instanceof DeleteMeterRequest
                        || command instanceof RemoveTelescopeRuleRequest || command instanceof RemoveExclusionRequest) {
                    CommandMessage message = new CommandMessage(command, System.currentTimeMillis(), correlationId,
                            Destination.CONTROLLER);
                    StreamType streamId = command instanceof BaseInstallFlow ? StreamType.CREATE : StreamType.DELETE;
                    outputCollector.emit(streamId.toString(), tuple, new Values(MAPPER.writeValueAsString(message)));
                } else {
                    CommandMessage message = new CommandMessage(command, System.currentTimeMillis(), correlationId);
                    // Send to the default stream.
                    outputCollector.emit(tuple, new Values(MAPPER.writeValueAsString(message)));
                }
            }
        }
    }

    private void onSuccessfulCommand(String flowId, BaseFlow data, Tuple tuple, String correlationId)
            throws UnknownTransactionException, JsonProcessingException, UnknownBatchException {
        UUID transactionId = data.getTransactionId();
        UUID batchId = flowCommandRegistry.removeCommand(flowId, transactionId);

        logger.info("Successful transaction in the batch {}: {}={}, {}={}", batchId,
                Utils.FLOW_ID, flowId, Utils.TRANSACTION_ID, transactionId);

        if (flowCommandRegistry.isBatchEmpty(batchId)) {
            onCompletedBatch(batchId, tuple, correlationId);
        }

        processCurrentBatch(flowId, tuple, correlationId);

    }

    private void onFailedCommand(String flowId, FlowCommandErrorData errorData, Tuple tuple, String correlationId)
            throws UnknownTransactionException, JsonProcessingException, UnknownBatchException {
        UUID transactionId = errorData.getTransactionId();
        FailureReaction reaction = flowCommandRegistry.getFailureReaction(flowId, transactionId)
                .orElse(FailureReaction.IGNORE);

        if (reaction == FailureReaction.ABORT_BATCH) {
            UUID batchId = flowCommandRegistry.removeCommand(flowId, transactionId);
            logger.error("The batch {} has a failed transaction: {}={}, {}={} ", batchId,
                    Utils.FLOW_ID, flowId, Utils.TRANSACTION_ID, transactionId);
            onFailedBatch(batchId, tuple, correlationId);
        } else {
            logger.info("Ignoring the failed transaction: {}={}, {}={}",
                    Utils.FLOW_ID, flowId, Utils.TRANSACTION_ID, transactionId);

            UUID batchId = flowCommandRegistry.removeCommand(flowId, transactionId);
            if (flowCommandRegistry.isBatchEmpty(batchId)) {
                onCompletedBatch(batchId, tuple, correlationId);
            }
        }

        processCurrentBatch(flowId, tuple, correlationId);
    }

    private void onCompletedBatch(UUID batchId, Tuple tuple, String correlationId)
            throws JsonProcessingException, UnknownBatchException {
        logger.debug("Sending on-success commands for the batch {}", batchId);

        List<CommandData> commands = flowCommandRegistry.getOnSuccessCommands(batchId);
        for (CommandData command : commands) {
            CommandMessage message = new CommandMessage(command,
                    System.currentTimeMillis(), correlationId);
            // Send to the default stream.
            outputCollector.emit(tuple, new Values(MAPPER.writeValueAsString(message)));
        }

        logger.debug("Removing completed batch {}", batchId);

        flowCommandRegistry.removeBatch(batchId);
    }

    private void onFailedBatch(UUID batchId, Tuple tuple, String correlationId)
            throws JsonProcessingException, UnknownBatchException {
        logger.debug("Sending on-failure commands for the batch {}", batchId);

        List<CommandData> commands = flowCommandRegistry.getOnFailureCommands(batchId);
        for (CommandData command : commands) {
            CommandMessage message = new CommandMessage(command,
                    System.currentTimeMillis(), correlationId);
            // Send to the default stream.
            outputCollector.emit(tuple, new Values(MAPPER.writeValueAsString(message)));
        }

        logger.debug("Removing failed batch {}", batchId);

        flowCommandRegistry.removeBatch(batchId);
    }

    @Override
    protected void doTick(Tuple tuple) {
        try {
            Set<UUID> expiredBatches = flowCommandRegistry.getExpiredBatches(transactionExpirationTime);

            CommandContext commandContext = new CommandContext();
            for (UUID batchId : expiredBatches) {
                logger.error("The batch {} has been expired", batchId);
                onFailedBatch(batchId, tuple, commandContext.getCorrelationId());
            }
        } catch (Exception e) {
            logger.error("Unhandled exception", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(StreamType.CREATE.toString(), FlowTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.DELETE.toString(), FlowTopology.fieldMessage);
        outputFieldsDeclarer.declare(FlowTopology.fieldMessage);
        // FIXME(dbogun): use proper tuple format
        outputFieldsDeclarer.declareStream(STREAM_ID_CTRL, AbstractTopology.fieldMessage);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        super.prepare(map, topologyContext, outputCollector);

        this.flowCommandRegistry = new FlowCommandRegistry();
    }
}
