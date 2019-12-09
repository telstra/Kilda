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

package org.openkilda.northbound.service.impl;

import static java.lang.String.format;
import static org.openkilda.messaging.Utils.FLOW_ID;
import static org.openkilda.northbound.utils.async.AsyncUtils.collectResponses;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.apps.FlowAddAppRequest;
import org.openkilda.messaging.command.apps.FlowAppsReadRequest;
import org.openkilda.messaging.command.apps.FlowRemoveAppRequest;
import org.openkilda.messaging.command.flow.FlowCreateRequest;
import org.openkilda.messaging.command.flow.FlowDeleteRequest;
import org.openkilda.messaging.command.flow.FlowPathSwapRequest;
import org.openkilda.messaging.command.flow.FlowPingRequest;
import org.openkilda.messaging.command.flow.FlowReadRequest;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.command.flow.FlowRequest.Type;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.flow.FlowUpdateRequest;
import org.openkilda.messaging.command.flow.FlowsDumpRequest;
import org.openkilda.messaging.command.flow.SwapFlowEndpointRequest;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.apps.FlowAppsResponse;
import org.openkilda.messaging.info.flow.FlowHistoryData;
import org.openkilda.messaging.info.flow.FlowInfoData;
import org.openkilda.messaging.info.flow.FlowOperation;
import org.openkilda.messaging.info.flow.FlowPingResponse;
import org.openkilda.messaging.info.flow.FlowReadResponse;
import org.openkilda.messaging.info.flow.FlowRerouteResponse;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.info.flow.FlowStatusResponse;
import org.openkilda.messaging.info.flow.SwapFlowResponse;
import org.openkilda.messaging.info.meter.FlowMeterEntries;
import org.openkilda.messaging.model.BidirectionalFlowDto;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.model.FlowPathDto;
import org.openkilda.messaging.model.FlowPathDto.FlowProtectedPathDto;
import org.openkilda.messaging.nbtopology.request.FlowConnectedDeviceRequest;
import org.openkilda.messaging.nbtopology.request.FlowPatchRequest;
import org.openkilda.messaging.nbtopology.request.FlowValidationRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowHistoryRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowPathRequest;
import org.openkilda.messaging.nbtopology.request.MeterModifyRequest;
import org.openkilda.messaging.nbtopology.response.FlowValidationResponse;
import org.openkilda.messaging.nbtopology.response.GetFlowPathResponse;
import org.openkilda.messaging.payload.flow.DiverseGroupPayload;
import org.openkilda.messaging.payload.flow.FlowCreatePayload;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowPathPayload;
import org.openkilda.messaging.payload.flow.FlowPathPayload.FlowProtectedPath;
import org.openkilda.messaging.payload.flow.FlowPayload;
import org.openkilda.messaging.payload.flow.FlowReroutePayload;
import org.openkilda.messaging.payload.flow.FlowResponsePayload;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.messaging.payload.flow.FlowUpdatePayload;
import org.openkilda.messaging.payload.flow.GroupFlowPathPayload;
import org.openkilda.messaging.payload.history.FlowEventPayload;
import org.openkilda.northbound.converter.ConnectedDeviceMapper;
import org.openkilda.northbound.converter.FlowMapper;
import org.openkilda.northbound.converter.PathMapper;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.flows.FlowAddAppDto;
import org.openkilda.northbound.dto.v1.flows.FlowAppsDto;
import org.openkilda.northbound.dto.v1.flows.FlowConnectedDevicesResponse;
import org.openkilda.northbound.dto.v1.flows.FlowDeleteAppDto;
import org.openkilda.northbound.dto.v1.flows.FlowEndpoint;
import org.openkilda.northbound.dto.v1.flows.FlowPatchDto;
import org.openkilda.northbound.dto.v1.flows.FlowValidationDto;
import org.openkilda.northbound.dto.v1.flows.PingInput;
import org.openkilda.northbound.dto.v1.flows.PingOutput;
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2;
import org.openkilda.northbound.dto.v2.flows.FlowRerouteResponseV2;
import org.openkilda.northbound.dto.v2.flows.FlowResponseV2;
import org.openkilda.northbound.dto.v2.flows.SwapFlowEndpointPayload;
import org.openkilda.northbound.messaging.MessagingChannel;
import org.openkilda.northbound.service.FlowService;
import org.openkilda.northbound.utils.CorrelationIdFactory;
import org.openkilda.northbound.utils.RequestCorrelationId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Manages operations with flows.
 */
@Service
public class FlowServiceImpl implements FlowService {
    /**
     * The logger.
     */
    private static final Logger logger = LoggerFactory.getLogger(FlowServiceImpl.class);

    /**
     * The kafka topic for the flow topology.
     */
    @Value("#{kafkaTopicsConfig.getFlowTopic()}")
    private String topic;

    /**
     * The kafka topic for the new flow topology.
     */
    @Value("#{kafkaTopicsConfig.getFlowHsTopic()}")
    private String flowHsTopic;

    /**
     * The kafka topic for `nbWorker` topology.
     */
    @Value("#{kafkaTopicsConfig.getTopoNbTopic()}")
    private String nbworkerTopic;

    /**
     * The kafka topic for `ping` topology.
     */
    @Value("#{kafkaTopicsConfig.getPingTopic()}")
    private String pingTopic;

    /**
     * The kafka topic for `apps` topology.
     */
    @Value("#{kafkaTopicsConfig.getTopoAppsNbTopic()}")
    private String appsTopic;

    @Value("${neo4j.uri}")
    private String neoUri;

    @Value("${neo4j.user}")
    private String neoUser;

    @Value("${neo4j.password}")
    private String neoPswd;

    @Autowired
    private FlowMapper flowMapper;

    @Autowired
    private PathMapper pathMapper;

    @Autowired
    private ConnectedDeviceMapper connectedDeviceMapper;

    @Autowired
    private MessagingChannel messagingChannel;

    @Autowired
    private CorrelationIdFactory idFactory;

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowResponsePayload> createFlow(final FlowCreatePayload input) {
        final String correlationId = RequestCorrelationId.getId();
        logger.info("Create flow: {}", input);

        FlowCreateRequest payload;
        try {
            payload = new FlowCreateRequest(new FlowDto(input), input.getDiverseFlowId());
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage(), e);
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments of the create flow request");
        }

        CommandMessage request = new CommandMessage(
                payload, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(topic, request)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseOutput);
    }

    @Override
    public CompletableFuture<FlowResponseV2> createFlow(FlowRequestV2 request) {
        logger.info("Processing flow creation: {}", request);

        final String correlationId = RequestCorrelationId.getId();
        FlowRequest flowRequest;

        try {
            flowRequest = flowMapper.toFlowCreateRequest(request);
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage(), e);
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments of the create flow request");
        }

        CommandMessage command = new CommandMessage(flowRequest,
                System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(flowHsTopic, command)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseV2);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowResponsePayload> getFlow(final String id) {
        logger.debug("Get flow request for flow {}", id);

        return getFlowReadResponse(id, RequestCorrelationId.getId())
                .thenApply(flowMapper::toFlowResponseOutput);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowResponsePayload> updateFlow(final FlowUpdatePayload input) {
        final String correlationId = RequestCorrelationId.getId();
        logger.info("Update flow request for flow {}", input.getId());

        FlowUpdateRequest payload;
        try {
            payload = new FlowUpdateRequest(new FlowDto(input), input.getDiverseFlowId());
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage(), e);
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments of the update flow request");
        }
        CommandMessage request = new CommandMessage(
                payload, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(topic, request)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseOutput);
    }

    @Override
    public CompletableFuture<FlowResponseV2> updateFlow(FlowRequestV2 request) {
        logger.info("Processing flow update: {}", request);

        final String correlationId = RequestCorrelationId.getId();
        FlowRequest updateRequest;

        try {
            updateRequest = flowMapper.toFlowRequest(request).toBuilder().type(Type.UPDATE).build();
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage(), e);
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments of the update flow request");
        }

        CommandMessage command = new CommandMessage(updateRequest,
                System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(flowHsTopic, command)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseV2);
    }

    @Override
    public CompletableFuture<FlowResponsePayload> patchFlow(String flowId, FlowPatchDto flowPatchDto) {
        logger.info("Patch flow request for flow {}", flowId);

        FlowDto flowDto = flowMapper.toFlowDto(flowPatchDto);
        flowDto.setFlowId(flowId);
        CommandMessage request = new CommandMessage(new FlowPatchRequest(flowDto), System.currentTimeMillis(),
                RequestCorrelationId.getId());

        return messagingChannel.sendAndGet(nbworkerTopic, request)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseOutput);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<List<FlowResponsePayload>> getAllFlows() {
        final String correlationId = RequestCorrelationId.getId();
        logger.debug("Get flows request processing");
        FlowsDumpRequest data = new FlowsDumpRequest();
        CommandMessage request = new CommandMessage(data, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGetChunked(topic, request)
                .thenApply(result -> result.stream()
                        .map(FlowReadResponse.class::cast)
                        .map(FlowReadResponse::getPayload)
                        .map(flowMapper::toFlowResponseOutput)
                        .collect(Collectors.toList()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<List<FlowResponsePayload>> deleteAllFlows() {
        CompletableFuture<List<FlowResponsePayload>> result = new CompletableFuture<>();
        logger.warn("Delete all flows request");
        // TODO: Need a getFlowIDs .. since that is all we need
        CompletableFuture<List<FlowResponsePayload>> getFlowsStage = this.getAllFlows();

        getFlowsStage.thenApply(flows -> {
            List<CompletableFuture<?>> deletionRequests = new ArrayList<>();
            for (int i = 0; i < flows.size(); i++) {
                String requestId = idFactory.produceChained(String.valueOf(i));
                FlowPayload flow = flows.get(i);
                deletionRequests.add(sendDeleteFlow(flow.getId(), requestId));
            }
            return deletionRequests;
        }).thenApply(requests -> collectResponses(requests, FlowResponsePayload.class)
                .thenApply(result::complete));

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowResponsePayload> deleteFlow(final String id) {
        logger.info("Delete flow request for flow: {}", id);
        final String correlationId = RequestCorrelationId.getId();

        return sendDeleteFlow(id, correlationId);
    }

    private CompletableFuture<FlowResponsePayload> sendDeleteFlow(String flowId, String correlationId) {
        CommandMessage request = buildDeleteFlowCommand(flowId, correlationId);

        return messagingChannel.sendAndGet(topic, request)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseOutput);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowResponseV2> deleteFlowV2(String flowId) {
        logger.info("Delete flow request for flow: {}", flowId);

        CommandMessage command = new CommandMessage(new FlowDeleteRequest(flowId),
                System.currentTimeMillis(), RequestCorrelationId.getId(), Destination.WFM);

        return messagingChannel.sendAndGet(flowHsTopic, command)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseV2);

    }

    /**
     * Non-blocking primitive .. just create and send delete request.
     *
     * @return the request
     */
    private CommandMessage buildDeleteFlowCommand(String flowId, String correlationId) {
        FlowDeleteRequest data = new FlowDeleteRequest(flowId);
        return new CommandMessage(data, System.currentTimeMillis(), correlationId, Destination.WFM);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowIdStatusPayload> statusFlow(final String id) {
        logger.debug("Flow status request for flow: {}", id);
        return getFlowReadResponse(id, RequestCorrelationId.getId())
                .thenApply(FlowReadResponse::getPayload)
                .thenApply(flowMapper::toFlowIdStatusPayload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowPathPayload> pathFlow(final String id) {
        logger.debug("Flow path request for flow {}", id);
        final String correlationId = RequestCorrelationId.getId();

        GetFlowPathRequest data = new GetFlowPathRequest(id);
        CommandMessage request = new CommandMessage(data, System.currentTimeMillis(), correlationId);

        return messagingChannel.sendAndGetChunked(nbworkerTopic, request)
                .thenApply(result -> result.stream()
                        .map(GetFlowPathResponse.class::cast)
                        .map(GetFlowPathResponse::getPayload)
                        .collect(Collectors.toList()))
                .thenApply(respList -> buildFlowPathPayload(respList, id));
    }

    private FlowPathPayload buildFlowPathPayload(List<FlowPathDto> paths, String flowId) {
        FlowPathDto askedPathDto = paths.stream().filter(e -> e.getId().equals(flowId)).findAny()
                .orElseThrow(() -> new IllegalStateException(format("Path for flow %s is not found.", flowId)));
        // fill primary flow path
        FlowPathPayload payload = new FlowPathPayload();
        payload.setId(askedPathDto.getId());
        payload.setForwardPath(askedPathDto.getForwardPath());
        payload.setReversePath(askedPathDto.getReversePath());

        if (askedPathDto.getProtectedPath() != null) {
            FlowProtectedPathDto protectedPath = askedPathDto.getProtectedPath();

            payload.setProtectedPath(FlowProtectedPath.builder()
                    .forwardPath(protectedPath.getForwardPath())
                    .reversePath(protectedPath.getReversePath())
                    .build());
        }

        // fill group paths
        if (paths.size() > 1) {
            payload.setDiverseGroupPayload(DiverseGroupPayload.builder()
                    .overlappingSegments(askedPathDto.getSegmentsStats())
                    .otherFlows(mapGroupPaths(paths, flowId, FlowPathDto::isPrimaryPathCorrespondStat))
                    .build());

            if (askedPathDto.getProtectedPath() != null) {
                payload.setDiverseGroupProtectedPayload(DiverseGroupPayload.builder()
                        .overlappingSegments(askedPathDto.getProtectedPath().getSegmentsStats())
                        .otherFlows(mapGroupPaths(paths, flowId, e -> !e.isPrimaryPathCorrespondStat()))
                        .build());
            }
        }
        return payload;
    }

    private List<GroupFlowPathPayload> mapGroupPaths(
            List<FlowPathDto> paths, String flowId, Predicate<FlowPathDto> predicate) {
        return paths.stream()
                .filter(e -> !e.getId().equals(flowId))
                .filter(predicate)
                .map(pathMapper::mapGroupFlowPathPayload)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<BatchResults> unpushFlows(List<FlowInfoData> externalFlows, Boolean propagate,
                                                       Boolean verify) {
        FlowOperation op = (propagate) ? FlowOperation.UNPUSH_PROPAGATE : FlowOperation.UNPUSH;
        // TODO: ADD the VERIFY implementation
        return flowPushUnpush(externalFlows, op);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<BatchResults> pushFlows(List<FlowInfoData> externalFlows,
                                                     Boolean propagate, Boolean verify) {
        FlowOperation op = (propagate) ? FlowOperation.PUSH_PROPAGATE : FlowOperation.PUSH;
        // TODO: ADD the VERIFY implementation
        return flowPushUnpush(externalFlows, op);
    }

    /**
     * Reads {@link BidirectionalFlowDto} flow representation from the Storm.
     *
     * @return the bidirectional flow.
     */
    private CompletableFuture<FlowReadResponse> getFlowReadResponse(String flowId, String correlationId) {
        FlowReadRequest data = new FlowReadRequest(flowId);
        CommandMessage request = new CommandMessage(data, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(topic, request)
                .thenApply(FlowReadResponse.class::cast);
    }

    /**
     * There are only minor differences between push and unpush .. this utility function helps
     */
    private CompletableFuture<BatchResults> flowPushUnpush(List<FlowInfoData> externalFlows, FlowOperation op) {
        final String correlationId = RequestCorrelationId.getId();
        String collect = externalFlows.stream().map(FlowInfoData::getFlowId).collect(Collectors.joining());
        logger.debug("Flow {}: id: {}", op, collect);
        logger.debug("Size of list: {}", externalFlows.size());
        // First, send them all, then wait for all the responses.
        // Send the command to both Flow Topology and to TE
        List<CompletableFuture<?>> flowRequests = new ArrayList<>();    // used for error reporting, if needed
        for (int i = 0; i < externalFlows.size(); i++) {
            FlowInfoData data = externalFlows.get(i);
            data.setOperation(op);  // <-- this is what determines PUSH / UNPUSH
            String flowCorrelation = correlationId + "-FLOW-" + i;
            InfoMessage flowRequest =
                    new InfoMessage(data, System.currentTimeMillis(), flowCorrelation, Destination.WFM, null);
            flowRequests.add(messagingChannel.sendAndGet(topic, flowRequest));
        }

        FlowState expectedState;
        switch (op) {
            case PUSH:
                expectedState = FlowState.UP;
                break;
            case PUSH_PROPAGATE:
                expectedState = FlowState.IN_PROGRESS;
                break;
            default:
                expectedState = FlowState.DOWN;
        }

        CompletableFuture<List<String>> flowFailures = collectResponses(flowRequests, FlowStatusResponse.class)
                .thenApply(flows ->
                        flows.stream()
                                .map(FlowStatusResponse::getPayload)
                                .filter(payload -> payload.getStatus() != expectedState)
                                .map(payload -> "FAILURE (FlowTopo): Flow " + payload.getId()
                                        + " NOT in " + expectedState
                                        + " state: state = " + payload.getStatus())
                                .collect(Collectors.toList()));

        return flowFailures.thenApply(failures -> {
            BatchResults batchResults = new BatchResults(failures.size(),
                    externalFlows.size() - failures.size(), failures);
            logger.debug("Returned: {}", batchResults);
            return batchResults;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowResponsePayload> swapFlowPaths(String flowId) {
        final String correlationId = RequestCorrelationId.getId();
        logger.info("Swapping paths for flow : {}", flowId);

        FlowPathSwapRequest payload = new FlowPathSwapRequest(flowId, null);
        CommandMessage request = new CommandMessage(
                payload, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(topic, request)
                .thenApply(FlowResponse.class::cast)
                .thenApply(FlowResponse::getPayload)
                .thenApply(flowMapper::toFlowResponseOutput);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowReroutePayload> rerouteFlow(String flowId) {
        logger.info("Reroute flow request for flow {}", flowId);
        return reroute(flowId, false);
    }

    @Override
    public CompletableFuture<FlowReroutePayload> syncFlow(String flowId) {
        logger.info("Forced reroute request for flow {}", flowId);
        return reroute(flowId, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<List<FlowValidationDto>> validateFlow(final String flowId) {
        logger.debug("Validate flow request for flow {}", flowId);
        CommandMessage message = new CommandMessage(new FlowValidationRequest(flowId),
                System.currentTimeMillis(), RequestCorrelationId.getId());

        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(FlowValidationResponse.class::cast)
                        .map(flowMapper::toFlowValidationDto)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<PingOutput> pingFlow(String flowId, PingInput payload) {
        FlowPingRequest request = new FlowPingRequest(flowId, payload.getTimeoutMillis());

        final String correlationId = RequestCorrelationId.getId();
        CommandMessage message = new CommandMessage(
                request, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(pingTopic, message)
                .thenApply(FlowPingResponse.class::cast)
                .thenApply(flowMapper::toPingOutput);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowMeterEntries> modifyMeter(String flowId) {
        MeterModifyRequest request = new MeterModifyRequest(flowId);

        final String correlationId = RequestCorrelationId.getId();
        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(), correlationId);
        return messagingChannel.sendAndGet(nbworkerTopic, message)
                .thenApply(FlowMeterEntries.class::cast);
    }

    @Override
    public CompletableFuture<FlowRerouteResponseV2> rerouteFlowV2(String flowId) {
        logger.info("Processing flow reroute: {}", flowId);

        FlowRerouteRequest payload = new FlowRerouteRequest(flowId, false,
                "initiated via Northbound");
        CommandMessage command = new CommandMessage(
                payload, System.currentTimeMillis(), RequestCorrelationId.getId(), Destination.WFM);

        return messagingChannel.sendAndGet(flowHsTopic, command)
                .thenApply(FlowRerouteResponse.class::cast)
                .thenApply(response ->
                        flowMapper.toRerouteResponseV2(flowId, response.getPayload(), response.isRerouted()));
    }

    private CompletableFuture<FlowReroutePayload> reroute(String flowId, boolean forced) {
        logger.debug("Reroute flow: {}={}, forced={}", FLOW_ID, flowId, forced);
        String correlationId = RequestCorrelationId.getId();
        FlowRerouteRequest payload = new FlowRerouteRequest(flowId, forced,
                "initiated via Northbound");
        CommandMessage command = new CommandMessage(
                payload, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(topic, command)
                .thenApply(FlowRerouteResponse.class::cast)
                .thenApply(response ->
                        flowMapper.toReroutePayload(flowId, response.getPayload(), response.isRerouted()));
    }

    @Override
    public CompletableFuture<List<FlowEventPayload>> listFlowEvents(String flowId,
                                                                    long timestampFrom,
                                                                    long timestampTo) {
        String correlationId = RequestCorrelationId.getId();
        GetFlowHistoryRequest request = GetFlowHistoryRequest.builder()
                .flowId(flowId)
                .timestampFrom(timestampFrom)
                .timestampTo(timestampTo)
                .build();
        CommandMessage command = new CommandMessage(request, System.currentTimeMillis(), correlationId);
        return messagingChannel.sendAndGet(nbworkerTopic, command)
                .thenApply(FlowHistoryData.class::cast)
                .thenApply(FlowHistoryData::getPayload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<SwapFlowEndpointPayload> swapFlowEndpoint(SwapFlowEndpointPayload input) {
        final String correlationId = RequestCorrelationId.getId();
        logger.info("Swap endpoints for flow {} and {}", input.getFirstFlow().getFlowId(),
                input.getSecondFlow().getFlowId());

        SwapFlowEndpointRequest payload = new SwapFlowEndpointRequest(flowMapper.toSwapFlowDto(input.getFirstFlow()),
                flowMapper.toSwapFlowDto(input.getSecondFlow()));

        CommandMessage request = new CommandMessage(
                payload, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGet(topic, request)
                .thenApply(SwapFlowResponse.class::cast)
                .thenApply(response -> new SwapFlowEndpointPayload(
                        flowMapper.toSwapOutput(response.getFirstFlow().getPayload()),
                        flowMapper.toSwapOutput(response.getSecondFlow().getPayload())));
    }

    @Override
    public CompletableFuture<FlowConnectedDevicesResponse> getFlowConnectedDevices(String flowId, Instant since) {
        logger.info("Get connected devices for flow {} since {}", flowId, since);

        FlowConnectedDeviceRequest request = new FlowConnectedDeviceRequest(flowId, since);

        CommandMessage message = new CommandMessage(
                request, System.currentTimeMillis(), RequestCorrelationId.getId(), Destination.WFM);

        return messagingChannel.sendAndGet(nbworkerTopic, message)
                .thenApply(org.openkilda.messaging.nbtopology.response.FlowConnectedDevicesResponse.class::cast)
                .thenApply(connectedDeviceMapper::toResponse);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowAppsDto> getFlowApplications(String flowId) {
        logger.debug("Get flow applications request for the flow {}", flowId);
        return processFlowAppsRequest(FlowAppsReadRequest.builder().flowId(flowId).build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowAppsDto> addFlowApplication(String flowId, String application,
                                                             FlowAddAppDto flowAddAppDto) {
        logger.debug("Add flow application request for the flow {}", flowId);
        FlowAddAppRequest.FlowAddAppRequestBuilder flowAddAppRequestBuilder = FlowAddAppRequest.builder()
                .flowId(flowId)
                .application(application);

        if (flowAddAppDto != null && flowAddAppDto.getEndpoint() != null) {
            flowAddAppRequestBuilder.switchId(flowAddAppDto.getEndpoint().getSwitchId())
                    .portNumber(flowAddAppDto.getEndpoint().getPortNumber())
                    .vlanId(flowAddAppDto.getEndpoint().getVlanId());
        }

        return processFlowAppsRequest(flowAddAppRequestBuilder.build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<FlowAppsDto> removeFlowApplications(String flowId, String application,
                                                                 FlowDeleteAppDto flowDeleteAppDto) {
        logger.debug("Remove flow application request for the flow {}", flowId);
        FlowEndpoint endpoint = flowDeleteAppDto.getEndpoint();
        FlowRemoveAppRequest flowRemoveAppRequest = FlowRemoveAppRequest.builder().flowId(flowId)
                .application(application)
                .switchId(endpoint.getSwitchId())
                .portNumber(endpoint.getPortNumber())
                .vlanId(endpoint.getVlanId())
                .build();
        return processFlowAppsRequest(flowRemoveAppRequest);
    }

    private CompletableFuture<FlowAppsDto> processFlowAppsRequest(CommandData data) {
        CommandMessage request = new CommandMessage(data, System.currentTimeMillis(), RequestCorrelationId.getId());
        return messagingChannel.sendAndGet(appsTopic, request)
                .thenApply(FlowAppsResponse.class::cast)
                .thenApply(flowMapper::toFlowAppsDto);
    }
}
