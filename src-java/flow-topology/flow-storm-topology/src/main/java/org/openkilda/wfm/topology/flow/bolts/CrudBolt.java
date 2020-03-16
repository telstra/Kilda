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

package org.openkilda.wfm.topology.flow.bolts;

import static java.lang.String.format;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.BatchCommandsRequest;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandGroup;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.DeallocateFlowResourcesRequest;
import org.openkilda.messaging.command.flow.FlowCreateRequest;
import org.openkilda.messaging.command.flow.FlowPathSwapRequest;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.flow.FlowUpdateRequest;
import org.openkilda.messaging.command.flow.PeriodicPingCommand;
import org.openkilda.messaging.command.flow.UpdateFlowPathStatusRequest;
import org.openkilda.messaging.ctrl.AbstractDumpState;
import org.openkilda.messaging.ctrl.state.CrudBoltState;
import org.openkilda.messaging.error.CacheException;
import org.openkilda.messaging.error.ClientErrorMessage;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.ChunkedInfoMessage;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.PathInfoData;
import org.openkilda.messaging.info.flow.FlowInfoData;
import org.openkilda.messaging.info.flow.FlowOperation;
import org.openkilda.messaging.info.flow.FlowReadResponse;
import org.openkilda.messaging.info.flow.FlowRerouteResponse;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.info.flow.FlowStatusResponse;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.ctrl.CtrlAction;
import org.openkilda.wfm.ctrl.ICtrlBolt;
import org.openkilda.wfm.error.ClientException;
import org.openkilda.wfm.error.FeatureTogglesNotEnabledException;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.service.FlowCommandFactory;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.history.model.FlowEventData;
import org.openkilda.wfm.share.history.model.FlowEventData.Event;
import org.openkilda.wfm.share.history.model.FlowEventData.Initiator;
import org.openkilda.wfm.share.history.model.FlowHistoryData;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.mappers.FlowMapper;
import org.openkilda.wfm.share.mappers.FlowPathMapper;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.flow.ComponentType;
import org.openkilda.wfm.topology.flow.FlowTopology;
import org.openkilda.wfm.topology.flow.StreamType;
import org.openkilda.wfm.topology.flow.model.FlowData;
import org.openkilda.wfm.topology.flow.model.ReroutedFlowPaths;
import org.openkilda.wfm.topology.flow.service.FeatureToggle;
import org.openkilda.wfm.topology.flow.service.FeatureTogglesService;
import org.openkilda.wfm.topology.flow.service.FlowCommandSender;
import org.openkilda.wfm.topology.flow.service.FlowService;
import org.openkilda.wfm.topology.flow.validation.FlowValidationException;
import org.openkilda.wfm.topology.flow.validation.FlowValidator;
import org.openkilda.wfm.topology.flow.validation.SwitchValidationException;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.NonNull;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CrudBolt extends BaseRichBolt implements ICtrlBolt {

    private static final String STREAM_ID_CTRL = "ctrl";

    private static final Logger logger = LoggerFactory.getLogger(CrudBolt.class);

    private final PersistenceManager persistenceManager;

    private final PathComputerConfig pathComputerConfig;

    private final FlowResourcesConfig flowResourcesConfig;

    private transient RepositoryFactory repositoryFactory;

    private transient KildaConfigurationRepository kildaConfigurationRepository;

    private transient FlowService flowService;

    private transient FeatureTogglesService featureTogglesService;

    private transient FlowCommandFactory commandFactory;

    private transient PathComputerFactory pathComputerFactory;

    private transient FlowResourcesManager flowResourcesManager;

    private transient FlowValidator flowValidator;

    private transient TopologyContext context;
    private transient OutputCollector outputCollector;

    public CrudBolt(PersistenceManager persistenceManager, PathComputerConfig pathComputerConfig,
                    FlowResourcesConfig flowResourcesConfig) {
        this.persistenceManager = persistenceManager;
        this.pathComputerConfig = pathComputerConfig;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(StreamType.CREATE.toString(), FlowTopology.fieldsMessageFlowId);
        outputFieldsDeclarer.declareStream(StreamType.UPDATE.toString(), FlowTopology.fieldsMessageFlowId);
        outputFieldsDeclarer.declareStream(StreamType.DELETE.toString(), FlowTopology.fieldsMessageFlowId);
        outputFieldsDeclarer.declareStream(StreamType.RESPONSE.toString(), AbstractTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.ERROR.toString(), FlowTopology.fieldsMessageErrorType);
        outputFieldsDeclarer.declareStream(StreamType.HISTORY.toString(), MessageKafkaTranslator.STREAM_FIELDS);
        outputFieldsDeclarer.declareStream(StreamType.PING.toString(), FlowTopology.fieldsKeyMessage);
        // FIXME(dbogun): use proper tuple format
        outputFieldsDeclarer.declareStream(STREAM_ID_CTRL, AbstractTopology.fieldMessage);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.context = topologyContext;
        this.outputCollector = outputCollector;

        repositoryFactory = persistenceManager.getRepositoryFactory();
        kildaConfigurationRepository = repositoryFactory.createKildaConfigurationRepository();
        flowValidator = new FlowValidator(repositoryFactory);
        AvailableNetworkFactory availableNetworkFactory =
                new AvailableNetworkFactory(pathComputerConfig, repositoryFactory);
        pathComputerFactory = new PathComputerFactory(pathComputerConfig, availableNetworkFactory);
        commandFactory = new FlowCommandFactory();

        flowResourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        flowService = new FlowService(persistenceManager, pathComputerFactory, flowResourcesManager,
                flowValidator, commandFactory);
        featureTogglesService = new FeatureTogglesService(persistenceManager.getRepositoryFactory());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Tuple tuple) {
        if (CtrlAction.boltHandlerEntrance(this, tuple)) {
            return;
        }

        logger.debug("Request tuple={}", tuple);

        ComponentType componentId = ComponentType.valueOf(tuple.getSourceComponent());
        // FIXME(surabujin): do not use any "default" correlation id
        String correlationId = Utils.DEFAULT_CORRELATION_ID;
        StreamType streamId = StreamType.valueOf(tuple.getSourceStreamId());
        String flowId = tuple.getStringByField(Utils.FLOW_ID);

        boolean isRecoverable = false;
        try {
            switch (componentId) {
                case SPLITTER_BOLT:
                    Message msg = (Message) tuple.getValueByField(AbstractTopology.MESSAGE_FIELD);
                    correlationId = msg.getCorrelationId();

                    CommandMessage cmsg = (msg instanceof CommandMessage) ? (CommandMessage) msg : null;
                    InfoMessage imsg = (msg instanceof InfoMessage) ? (InfoMessage) msg : null;

                    logger.info("Flow request: {}={}, {}={}, component={}, stream={}",
                            Utils.CORRELATION_ID, correlationId, Utils.FLOW_ID, flowId, componentId, streamId);

                    switch (streamId) {
                        case CREATE:
                            handleCreateRequest(cmsg, tuple);
                            break;
                        case UPDATE:
                            handleUpdateRequest(cmsg, tuple);
                            break;
                        case DELETE:
                            handleDeleteRequest(flowId, cmsg, tuple);
                            break;
                        case PUSH:
                            handlePushRequest(flowId, imsg, tuple);
                            break;
                        case UNPUSH:
                            handleUnpushRequest(flowId, imsg, tuple);
                            break;
                        case REROUTE:
                            handleRerouteRequest(cmsg, tuple);
                            break;
                        case PATH_SWAP:
                            handlePathSwapRequest(cmsg, tuple);
                            break;
                        case READ:
                            handleReadRequest(flowId, cmsg, tuple);
                            break;
                        case DUMP:
                            handleDumpRequest(cmsg, tuple);
                            break;
                        case DEALLOCATE_RESOURCES:
                            handleDeallocateResourcesRequest(cmsg, tuple);
                            break;
                        case STATUS:
                            handleUpdateFlowPathStatusRequest(cmsg, tuple);
                            break;
                        default:
                            logger.error("Unexpected stream: {} in {}", streamId, tuple);
                            break;
                    }
                    break;

                default:
                    logger.error("Unexpected component: {} in {}", componentId, tuple);
                    break;
            }
            //} catch (RecoverableException e) {
            // FIXME(surabujin): implement retry limit
            // logger.error(
            // "Recoverable error (do not try to recoverable it until retry limit will be implemented): {}", e);
            // isRecoverable = true;
        } catch (ClientException exception) {
            emitError(tuple, correlationId, exception, true);
        } catch (CacheException exception) {
            emitError(tuple, correlationId, exception, false);

        } catch (Exception e) {
            logger.error("Unhandled exception", e);
        } finally {
            logger.debug("Command message ack: {}", tuple);

            if (isRecoverable) {
                outputCollector.fail(tuple);
            } else {
                outputCollector.ack(tuple);
            }
        }
    }

    private void emitError(Tuple tuple, String correlationId, CacheException exception, boolean isWarning) {
        String logMessage = format("%s: %s", exception.getErrorMessage(), exception.getErrorDescription());
        ErrorData errorData = new ErrorData(exception.getErrorType(), logMessage, exception.getErrorDescription());
        ErrorMessage errorMessage;

        if (isWarning) {
            logger.warn(logMessage, exception);
            errorMessage = buildClientErrorMessage(correlationId, errorData);
        } else {
            logger.error(logMessage, exception);
            errorMessage = buildErrorMessage(correlationId, errorData);
        }

        Values error = new Values(errorMessage, exception.getErrorType());
        outputCollector.emit(StreamType.ERROR.toString(), tuple, error);
    }

    private void handlePushRequest(String flowId, InfoMessage message, Tuple tuple) {
        final String errorType = "Can not push flow";

        try {
            featureTogglesService.checkFeatureToggleEnabled(FeatureToggle.PUSH_FLOW);

            logger.info("PUSH flow: {} :: {}", flowId, message);
            FlowInfoData fid = (FlowInfoData) message.getData();
            Flow flow = FlowMapper.INSTANCE.map(fid.getPayload(), () -> kildaConfigurationRepository.get());

            FlowStatus flowStatus = (fid.getOperation() == FlowOperation.PUSH_PROPAGATE)
                    ? FlowStatus.IN_PROGRESS : FlowStatus.UP;
            flow.setStatus(flowStatus);

            flowService.saveFlow(flow,
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.CREATE) {
                        @Override
                        public void sendFlowCommands(String flowId, List<CommandGroup> commandGroups,
                                                     List<? extends CommandData> onSuccessCommands,
                                                     List<? extends CommandData> onFailureCommands) {
                            if (fid.getOperation() == FlowOperation.PUSH_PROPAGATE) {
                                super.sendFlowCommands(flowId, commandGroups, onSuccessCommands, onFailureCommands);
                            }
                        }
                    });

            logger.info("PUSHed the flow: {}", flow);

            Values values = new Values(new InfoMessage(
                    new FlowStatusResponse(new FlowIdStatusPayload(flowId, FlowMapper.INSTANCE.map(flowStatus))),
                    message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
        } catch (FlowAlreadyExistException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.ALREADY_EXISTS, errorType, e.getMessage());
        } catch (FeatureTogglesNotEnabledException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_PERMITTED, errorType, "Feature push flow is disabled");
        } catch (Exception e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.CREATION_FAILURE, errorType, e.getMessage());
        }
    }

    private void handleUnpushRequest(String flowId, InfoMessage message, Tuple tuple) {
        final String errorType = "Can not unpush flow";

        try {
            featureTogglesService.checkFeatureToggleEnabled(FeatureToggle.UNPUSH_FLOW);

            logger.info("UNPUSH flow: {} :: {}", flowId, message);

            FlowInfoData fid = (FlowInfoData) message.getData();

            FlowDto flowDto = flowService.deleteFlow(flowId,
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.DELETE) {
                        public void sendFlowCommands(String flowId, List<CommandGroup> commandGroups,
                                                     List<? extends CommandData> onSuccessCommands,
                                                     List<? extends CommandData> onFailureCommands) {
                            if (fid.getOperation() == FlowOperation.UNPUSH_PROPAGATE) {
                                super.sendFlowCommands(flowId, commandGroups, onSuccessCommands, onFailureCommands);
                            }
                        }
                    });

            logger.info("UNPUSHed the flow: {}", flowDto);

            Values values = new Values(new InfoMessage(
                    new FlowStatusResponse(new FlowIdStatusPayload(flowId, FlowState.DOWN)),
                    message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
        } catch (FlowNotFoundException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, e.getMessage());
        } catch (FeatureTogglesNotEnabledException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_PERMITTED, errorType, "Feature unpush flow is disabled");
        } catch (Exception e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.DELETION_FAILURE, errorType, e.getMessage());
        }
    }

    private void handleDeleteRequest(String flowId, CommandMessage message, Tuple tuple) {
        final String errorType = "Can not delete flow";

        try {
            featureTogglesService.checkFeatureToggleEnabled(FeatureToggle.DELETE_FLOW);

            FlowDto deletedFlow = flowService.deleteFlow(flowId,
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.DELETE));

            logger.info("Deleted the flow: {}", deletedFlow);

            Values values = new Values(new InfoMessage(new FlowResponse(deletedFlow),
                    message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
            Values pingValues = new Values(message.getCorrelationId(),
                    new CommandMessage(new PeriodicPingCommand(flowId, false),
                    System.currentTimeMillis(), message.getCorrelationId()));
            outputCollector.emit(StreamType.PING.toString(), tuple, pingValues);
        } catch (FlowNotFoundException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, e.getMessage());
        } catch (FlowValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    e.getType(), errorType, e.getMessage());
        } catch (FeatureTogglesNotEnabledException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_PERMITTED, errorType, "Feature toggles not enabled for DELETE_FLOW operation.");
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.DELETION_FAILURE, errorType, e.getMessage());
        }
    }

    private void handleCreateRequest(CommandMessage message, Tuple tuple) {
        final String errorType = "Could not create flow";

        try {
            featureTogglesService.checkFeatureToggleEnabled(FeatureToggle.CREATE_FLOW);

            FlowCreateRequest request = (FlowCreateRequest) message.getData();
            if (!request.getPayload().isValid()) {
                throw  new FlowValidationException("Flow flags are not valid, unable to create pinned protected flow",
                        ErrorType.DATA_INVALID);
            }
            Flow flow = FlowMapper.INSTANCE.map(request.getPayload(), () -> kildaConfigurationRepository.get());
            saveEvent(Event.CREATE, flow.getFlowId(), "", message.getCorrelationId(), tuple);

            Flow createdFlow = flowService.createFlow(flow, request.getDiverseFlowId(),
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.CREATE));

            logger.info("Created the flow: {}", createdFlow);
            saveHistory("Created the flow", "", message.getCorrelationId(), tuple);
            saveDump(createdFlow, DumpType.STATE_AFTER, message.getCorrelationId(), tuple);

            Values values = new Values(new InfoMessage(buildFlowResponse(createdFlow),
                    message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
        } catch (FlowValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    e.getType(), errorType, e.getMessage());
        } catch (SwitchValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.DATA_INVALID, errorType, e.getMessage());
        } catch (FlowAlreadyExistException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.ALREADY_EXISTS, errorType, e.getMessage());
        } catch (UnroutableFlowException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, "Not enough bandwidth found or path not found. " + e.getMessage());
        } catch (FlowNotFoundException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, "The flow not found :  " + e.getMessage());
        } catch (FeatureTogglesNotEnabledException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_PERMITTED, errorType, "Feature toggles not enabled for CREATE_FLOW operation.");
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.CREATION_FAILURE, errorType, e.getMessage());
        }
    }

    private void saveEvent(Event event, String flowId, String details, String correlationId, Tuple tuple) {
        FlowEventData flowEvent = FlowEventData.builder()
                .event(event)
                .initiator(Initiator.NB)
                .flowId(flowId)
                .details(details)
                .time(Instant.now())
                .build();

        FlowHistoryHolder historyHolder = FlowHistoryHolder.builder()
                .flowEventData(flowEvent)
                .taskId(correlationId)
                .build();
        outputCollector.emit(StreamType.HISTORY.toString(), tuple, new Values(null, historyHolder,
                new CommandContext(correlationId)));
    }

    private void saveHistory(String action, String details, String correlationId, Tuple tuple) {
        FlowHistoryData flowHistory = FlowHistoryData.builder()
                .action(action)
                .description(details)
                .time(Instant.now())
                .build();

        FlowHistoryHolder historyHolder = FlowHistoryHolder.builder()
                .flowHistoryData(flowHistory)
                .taskId(correlationId)
                .build();
        outputCollector.emit(StreamType.HISTORY.toString(), tuple, new Values(null, historyHolder,
                new CommandContext(correlationId)));
    }

    private void saveDump(Flow flow, DumpType type, String correlationId, Tuple tuple)
            throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        FlowPath forwardPath = flow.getForwardPath();
        FlowPath reversePath = flow.getReversePath();

        FlowDumpData flowDump = FlowDumpData.builder()
                .flowId(flow.getFlowId())
                .dumpType(type)
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth())
                .forwardCookie(forwardPath.getCookie())
                .reverseCookie(reversePath.getCookie())
                .sourceSwitch(flow.getSrcSwitch().getSwitchId())
                .destinationSwitch(flow.getDestSwitch().getSwitchId())
                .sourcePort(flow.getSrcPort())
                .destinationPort(flow.getDestPort())
                .sourceVlan(flow.getSrcVlan())
                .destinationVlan(flow.getDestVlan())
                .forwardMeterId(Optional.ofNullable(forwardPath.getMeterId()).orElse(null))
                .reverseMeterId(Optional.ofNullable(reversePath.getMeterId()).orElse(null))
                .forwardPath(objectMapper.writeValueAsString(FlowPathMapper.INSTANCE.mapToPathNodes(forwardPath)))
                .reversePath(objectMapper.writeValueAsString(FlowPathMapper.INSTANCE.mapToPathNodes(reversePath)))
                .forwardStatus(forwardPath.getStatus())
                .reverseStatus(reversePath.getStatus())
                .build();

        FlowHistoryHolder historyHolder = FlowHistoryHolder.builder()
                .flowDumpData(flowDump)
                .taskId(correlationId)
                .build();
        outputCollector.emit(StreamType.HISTORY.toString(), tuple, new Values(null, historyHolder,
                new CommandContext(correlationId)));
    }

    private void handleRerouteRequest(CommandMessage message, Tuple tuple) {
        FlowRerouteRequest request = (FlowRerouteRequest) message.getData();
        final String flowId = request.getFlowId();
        final String errorType = "Could not reroute flow";

        try {
            ReroutedFlowPaths reroutedFlowPaths = flowService.rerouteFlow(
                    flowId, request.isForce(), request.getAffectedIsl(),
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.UPDATE));

            logger.warn("Rerouted flow with new path: {}", reroutedFlowPaths.getNewFlowPaths());
            handleReroute(message, tuple, reroutedFlowPaths);
        } catch (FlowNotFoundException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, e.getMessage());
        } catch (FlowValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    e.getType(), errorType, e.getMessage());
        } catch (UnroutableFlowException e) {
            flowService.updateFlowStatus(flowId, FlowStatus.DOWN, Collections.emptySet());
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, "Path was not found. " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.UPDATE_FAILURE, errorType, e.getMessage());
        }
    }

    private void handleReroute(CommandMessage message, Tuple tuple, ReroutedFlowPaths reroutedFlowPaths) {
        PathInfoData updatedPath = FlowPathMapper.INSTANCE.map(reroutedFlowPaths.getNewFlowPaths()
                .getForwardPath());

        FlowRerouteResponse response = new FlowRerouteResponse(updatedPath, reroutedFlowPaths.isRerouted());
        Values values = new Values(new InfoMessage(response, message.getTimestamp(),
                message.getCorrelationId(), Destination.NORTHBOUND, null));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
    }

    private void handlePathSwapRequest(CommandMessage message, Tuple tuple) {
        final String errorType = "Could not swap paths";

        FlowPathSwapRequest request = (FlowPathSwapRequest) message.getData();
        final String flowId = request.getFlowId();

        try {
            Flow flow = flowService.pathSwap(flowId, request.getPathId(),
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.UPDATE));

            Values values = new Values(new InfoMessage(buildFlowResponse(flow),
                    message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
        } catch (FlowNotFoundException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, e.getMessage());
        } catch (FlowValidationException e) {
            logger.warn("Flow path swap failure with reason: {}", e.getMessage());
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    e.getType(), errorType, e.getMessage());
        } catch (Exception e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.UPDATE_FAILURE, errorType, e.getMessage());
        }
    }

    private void handleUpdateRequest(CommandMessage message, Tuple tuple) {
        final String errorType = "Could not update flow";

        try {
            featureTogglesService.checkFeatureToggleEnabled(FeatureToggle.UPDATE_FLOW);

            FlowUpdateRequest request = (FlowUpdateRequest) message.getData();
            if (!request.getPayload().isValid()) {
                throw  new FlowValidationException("Flow flags are not valid, unable to update pinned protected flow",
                        ErrorType.DATA_INVALID);
            }
            Flow flow = FlowMapper.INSTANCE.map(request.getPayload(),
                    () -> kildaConfigurationRepository.get());
            saveEvent(Event.UPDATE, flow.getFlowId(), "Flow updating", message.getCorrelationId(), tuple);

            //TODO: this is extra fetch of the flow entity, must be moved into the service method.
            Optional<Flow> foundFlow = repositoryFactory.createFlowRepository().findById(flow.getFlowId());
            if (foundFlow.isPresent()) {
                saveDump(foundFlow.get(), DumpType.STATE_BEFORE, message.getCorrelationId(), tuple);
            }

            Flow updatedFlow = flowService.updateFlow(flow, request.getDiverseFlowId(),
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.UPDATE));

            logger.info("Updated the flow: {}", updatedFlow);
            saveHistory("Updated the flow", "", message.getCorrelationId(), tuple);
            saveDump(updatedFlow, DumpType.STATE_AFTER, message.getCorrelationId(), tuple);

            Values values = new Values(new InfoMessage(buildFlowResponse(updatedFlow),
                    message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
        } catch (FlowValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    e.getType(), errorType, e.getMessage());
        } catch (SwitchValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.DATA_INVALID, errorType, e.getMessage());
        } catch (FlowNotFoundException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, e.getMessage());
        } catch (UnroutableFlowException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_FOUND, errorType, "Not enough bandwidth found or path not found. " + e.getMessage());
        } catch (FeatureTogglesNotEnabledException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.NOT_PERMITTED, errorType, "Feature toggles not enabled for UPDATE_FLOW operation.");
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.UPDATE_FAILURE, errorType, e.getMessage());
        }
    }

    private void handleDumpRequest(CommandMessage message, Tuple tuple) {
        List<FlowData> flows = flowService.getAllFlows();
        logger.debug("Dump flows: found {} items", flows.size());

        String requestId = message.getCorrelationId();
        if (flows.isEmpty()) {
            Message response = new ChunkedInfoMessage(null, System.currentTimeMillis(), requestId, requestId,
                    flows.size());
            outputCollector.emit(StreamType.RESPONSE.toString(), tuple, new Values(response));
        } else {
            int i = 0;
            for (FlowData flowData : flows) {
                Message response = new ChunkedInfoMessage(new FlowReadResponse(flowData.getFlowDto(), null),
                        System.currentTimeMillis(), requestId, i++, flows.size());

                outputCollector.emit(StreamType.RESPONSE.toString(), tuple, new Values(response));
            }
        }
    }

    private void handleReadRequest(String flowId, CommandMessage message, Tuple tuple) {
        FlowData flowData = flowService.getFlowById(flowId)
                .orElseThrow(() -> new ClientException(message.getCorrelationId(), System.currentTimeMillis(),
                        ErrorType.NOT_FOUND, "Can not get flow", String.format("Flow %s not found", flowId)));

        logger.debug("Got flow: {}, correlationId {}", flowData, message.getCorrelationId());

        List<String> diverseFlowsId = flowService.getDiverseFlowsId(flowId, flowData.getFlowGroup());

        Values values = new Values(new InfoMessage(new FlowReadResponse(flowData.getFlowDto(), diverseFlowsId),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND, null));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, values);
    }

    private void handleDeallocateResourcesRequest(CommandMessage message, Tuple tuple) {
        try {
            DeallocateFlowResourcesRequest request = (DeallocateFlowResourcesRequest) message.getData();
            flowService.deallocateResources(request.getPathId(),
                    request.getUnmaskedCookie(), request.getEncapsulationType());

            logger.info("Flow resources deallocated: {}", request);

        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.DELETION_FAILURE, "Could not deallocate flow resources", e.getMessage());
        }
    }

    private void handleUpdateFlowPathStatusRequest(CommandMessage message, Tuple tuple) {
        try {
            UpdateFlowPathStatusRequest request = (UpdateFlowPathStatusRequest) message.getData();
            flowService.updateFlowPathStatus(request.getFlowId(), request.getPathId(), request.getFlowPathStatus());

            logger.info("Flow status updated: {}", request);
            flowService.notifyOnPeriodicPingChanges(request.getFlowId(),
                    new FlowCommandSenderImpl(message.getCorrelationId(), tuple, StreamType.PING));
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.UPDATE_FAILURE, "Could not update flow status", e.getMessage());
        }
    }

    /**
     * Builds flow response entity.
     *
     * @param flow a flow for payload
     * @return flow response entity
     */
    private FlowResponse buildFlowResponse(Flow flow) {
        FlowDto flowDto = FlowMapper.INSTANCE.map(flow);
        flowDto.setCookie(FlowSegmentCookieSchema.INSTANCE.getFlowEffectiveId(flow.getForwardPath().getCookie()));
        return new FlowResponse(flowDto);
    }

    private ErrorMessage buildErrorMessage(String correlationId, ErrorData errorData) {
        return new ErrorMessage(errorData, System.currentTimeMillis(), correlationId, Destination.NORTHBOUND);
    }

    private ErrorMessage buildClientErrorMessage(String correlationId, ErrorData errorData) {
        return new ClientErrorMessage(errorData, System.currentTimeMillis(), correlationId, Destination.NORTHBOUND);
    }

    @Override
    public AbstractDumpState dumpState() {
        // Not implemented
        return new CrudBoltState();
    }

    @VisibleForTesting
    @Override
    public void clearState() {
        logger.info("State clear request from test");
    }

    @Override
    public AbstractDumpState dumpStateBySwitchId(SwitchId switchId) {
        // Not implemented
        return new CrudBoltState();
    }

    @Override
    public String getCtrlStreamId() {
        return STREAM_ID_CTRL;
    }

    @Override
    public TopologyContext getContext() {
        return context;
    }

    @Override
    public OutputCollector getOutput() {
        return outputCollector;
    }

    @Override
    public Optional<AbstractDumpState> dumpResorceCacheState() {
        return Optional.empty();
    }

    class FlowCommandSenderImpl implements FlowCommandSender {
        private final String correlationId;
        private final Tuple tuple;
        private final StreamType stream;

        FlowCommandSenderImpl(String correlationId, Tuple tuple, StreamType stream) {
            this.correlationId = correlationId;
            this.tuple = tuple;
            this.stream = stream;
        }

        @Override
        public void sendFlowCommands(@NonNull String flowId, @NonNull List<CommandGroup> commandGroups,
                                     @NonNull List<? extends CommandData> onSuccessCommands,
                                     @NonNull List<? extends CommandData> onFailureCommands) {
            CommandMessage message = new CommandMessage(
                    new BatchCommandsRequest(commandGroups, onSuccessCommands, onFailureCommands),
                    System.currentTimeMillis(), correlationId);
            outputCollector.emit(stream.toString(), tuple, new Values(message, flowId));
        }

        @Override
        public void sendPeriodicPingNotification(@NonNull String flowId, boolean enabled) {
            CommandMessage message = new CommandMessage(
                    new PeriodicPingCommand(flowId, enabled),
                    System.currentTimeMillis(), correlationId);

            outputCollector.emit(stream.toString(), tuple, new Values(correlationId, message));
        }
    }
}
