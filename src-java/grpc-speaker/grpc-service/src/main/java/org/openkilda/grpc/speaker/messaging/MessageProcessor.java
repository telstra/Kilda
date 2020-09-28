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

package org.openkilda.grpc.speaker.messaging;

import org.openkilda.grpc.speaker.exception.GrpcRequestFailureException;
import org.openkilda.grpc.speaker.mapper.NoviflowResponseMapper;
import org.openkilda.grpc.speaker.mapper.RequestMapper;
import org.openkilda.grpc.speaker.service.GrpcSenderService;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.MessageData;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.grpc.CreateLogicalPortRequest;
import org.openkilda.messaging.command.grpc.DeleteLogicalPortRequest;
import org.openkilda.messaging.command.grpc.DumpLogicalPortsRequest;
import org.openkilda.messaging.command.grpc.GetPacketInOutStatsRequest;
import org.openkilda.messaging.command.grpc.GetSwitchInfoRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.grpc.CreateLogicalPortResponse;
import org.openkilda.messaging.info.grpc.DeleteLogicalPortResponse;
import org.openkilda.messaging.info.grpc.DumpLogicalPortsResponse;
import org.openkilda.messaging.info.grpc.GetPacketInOutStatsResponse;
import org.openkilda.messaging.info.grpc.GetSwitchInfoResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
@Component
public class MessageProcessor {

    @Autowired
    KafkaMessageProducer messageProducer;

    @Autowired
    private GrpcSenderService service;

    @Autowired
    RequestMapper requestMapper;

    @Autowired
    NoviflowResponseMapper responseMapper;

    @Value("#{kafkaTopicsConfig.getGrpcResponseTopic()}")
    private String grpcResponseTopic;

    @Value("#{kafkaTopicsConfig.getStatsTopic()}")
    private String statsTopic;

    // TODO error handling

    /**
     * Process request.
     *
     * @param message a message to be processed
     */
    public void processRequest(Message message, String key) {
        if (message instanceof CommandMessage) {
            handleCommandMessage((CommandMessage) message, key);
        } else {
            unhandledMessage(message);
        }
    }

    private void handleCommandMessage(CommandMessage command, String key) {
        CommandData data = command.getData();
        String correlationId = command.getCorrelationId();
        String topic = grpcResponseTopic;

        CompletableFuture<MessageData> result;

        if (data instanceof CreateLogicalPortRequest) {
            result = handleCreateLogicalPortRequest((CreateLogicalPortRequest) data);
        } else if (data instanceof DumpLogicalPortsRequest) {
            result = handleDumpLogicalPortsRequest((DumpLogicalPortsRequest) data);
        } else if (data instanceof GetSwitchInfoRequest) {
            result = handleGetSwitchInfoRequest((GetSwitchInfoRequest) data);
        } else if (data instanceof GetPacketInOutStatsRequest) {
            result = handleGetPacketInOutStatsRequest((GetPacketInOutStatsRequest) data);
            topic = statsTopic;
        } else if (data instanceof DeleteLogicalPortRequest) {
            result = handleDeleteLogicalPortRequest((DeleteLogicalPortRequest) data);
        } else {
            result = unhandledMessage(command);
        }

        final String responseTopic = topic;
        result.thenAccept(payload -> sendResponse(payload, correlationId, key, responseTopic));
    }

    private CompletableFuture<MessageData> handleCreateLogicalPortRequest(CreateLogicalPortRequest request) {
        log.info("Creating logical port {} on switch {}", request.getLogicalPortNumber(), request.getAddress());
        return service.createLogicalPort(request.getAddress(), requestMapper.toLogicalPort(request))
                .thenApply(result -> new CreateLogicalPortResponse(request.getAddress(), result, true))
                .handle(this::handleResult);
    }

    private CompletableFuture<MessageData> handleDumpLogicalPortsRequest(DumpLogicalPortsRequest request) {
        log.debug("Dumping logical ports on switch {}", request.getAddress());
        return service.dumpLogicalPorts(request.getAddress())
                .thenApply(result -> new DumpLogicalPortsResponse(request.getAddress(), result))
                .handle(this::handleResult);
    }

    private CompletableFuture<MessageData> handleGetSwitchInfoRequest(GetSwitchInfoRequest request) {
        log.debug("Getting switch info for switch {}", request.getAddress());
        return service.getSwitchStatus(request.getAddress())
                .thenApply(result -> new GetSwitchInfoResponse(request.getAddress(), result))
                .handle(this::handleResult);
    }

    private CompletableFuture<MessageData> handleGetPacketInOutStatsRequest(GetPacketInOutStatsRequest request) {
        log.debug("Getting switch packet in out stats for switch {}", request.getAddress());
        return service.getPacketInOutStats(request.getAddress())
                .thenApply(stats -> new GetPacketInOutStatsResponse(request.getSwitchId(), responseMapper.map(stats)))
                .handle(this::handleResult);
    }

    private CompletableFuture<MessageData> handleDeleteLogicalPortRequest(DeleteLogicalPortRequest command) {
        return service.deleteConfigLogicalPort(command.getAddress(), command.getLogicalPortNumber())
                .thenApply(result -> new DeleteLogicalPortResponse(
                        command.getAddress(), command.getLogicalPortNumber(), result.getDeleted()))
                .handle(this::handleResult);
    }

    private MessageData handleResult(InfoData result, Throwable error) {
        if (error != null) {
            return handleError(error);
        }
        return result;
    }

    private MessageData handleError(Throwable error) {
        try {
            throw error;
        } catch (GrpcRequestFailureException e) {
            return new ErrorData(e.getErrorType(), e.getMessage(), "");
        } catch (CompletionException e) {
            return handleError(e.getCause());
        } catch (Throwable e) {
            return new ErrorData(ErrorType.INTERNAL_ERROR, e.getMessage(), "");
        }
    }

    private void sendResponse(MessageData payload, String correlationId, String key, String topic) {
        Message message = makeMessage(payload, correlationId);
        messageProducer.send(topic, key, message);
    }

    private Message makeMessage(MessageData payload, String correlationId) {
        if (payload instanceof InfoData) {
            return new InfoMessage((InfoData) payload, System.currentTimeMillis(), correlationId);
        } else if (payload instanceof ErrorData) {
            return new ErrorMessage((ErrorData) payload, System.currentTimeMillis(), correlationId);
        } else {
            throw new IllegalArgumentException(String.format(
                    "Unexpected/unsupported message payload type: %s", payload.getClass().getName()));
        }
    }

    private CompletableFuture<MessageData> unhandledMessage(Message message) {
        String errorMessage = String.format("GRPC speaker is unable to handle message %s", message);
        log.error(errorMessage);

        ErrorData response =  new ErrorData(
                ErrorType.INTERNAL_ERROR, errorMessage, "");
        return CompletableFuture.completedFuture(response);
    }
}
