/* Copyright 2018 Telstra Open Source
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

import static org.openkilda.northbound.utils.async.AsyncUtils.collectChunkedResponses;
import static org.openkilda.northbound.utils.async.AsyncUtils.collectResponses;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.info.flow.FlowsResponse;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.model.NetworkEndpointMask;
import org.openkilda.messaging.nbtopology.request.BfdPropertiesReadRequest;
import org.openkilda.messaging.nbtopology.request.BfdPropertiesWriteRequest;
import org.openkilda.messaging.nbtopology.request.DeleteLinkRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowsForIslRequest;
import org.openkilda.messaging.nbtopology.request.GetLinksRequest;
import org.openkilda.messaging.nbtopology.request.LinkPropsDrop;
import org.openkilda.messaging.nbtopology.request.LinkPropsGet;
import org.openkilda.messaging.nbtopology.request.LinkPropsPut;
import org.openkilda.messaging.nbtopology.request.RerouteFlowsForIslRequest;
import org.openkilda.messaging.nbtopology.request.UpdateLinkUnderMaintenanceRequest;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.messaging.nbtopology.response.LinkPropsData;
import org.openkilda.messaging.nbtopology.response.LinkPropsResponse;
import org.openkilda.messaging.payload.flow.FlowResponsePayload;
import org.openkilda.model.LinkProps;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.converter.FlowMapper;
import org.openkilda.northbound.converter.LinkMapper;
import org.openkilda.northbound.converter.LinkPropsMapper;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.links.LinkDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthRequest;
import org.openkilda.northbound.dto.v1.links.LinkParametersDto;
import org.openkilda.northbound.dto.v1.links.LinkPropsDto;
import org.openkilda.northbound.dto.v1.links.LinkUnderMaintenanceDto;
import org.openkilda.northbound.dto.v2.links.BfdProperties;
import org.openkilda.northbound.dto.v2.links.BfdPropertiesPayload;
import org.openkilda.northbound.error.BfdPropertyApplyException;
import org.openkilda.northbound.error.InconclusiveException;
import org.openkilda.northbound.messaging.MessagingChannel;
import org.openkilda.northbound.service.LinkService;
import org.openkilda.northbound.service.impl.link.BfdPropertiesMonitor;
import org.openkilda.northbound.utils.CorrelationIdFactory;
import org.openkilda.northbound.utils.RequestCorrelationId;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Service
public class LinkServiceImpl extends BaseService implements LinkService {

    private static final Logger logger = LoggerFactory.getLogger(LinkServiceImpl.class);

    @Autowired
    private Clock clock;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private CorrelationIdFactory idFactory;

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private FlowMapper flowMapper;

    @Autowired
    private LinkPropsMapper linkPropsMapper;

    /**
     * The kafka topic for the nb topology.
     */
    @Value("#{kafkaTopicsConfig.getTopoNbTopic()}")
    private String nbworkerTopic;

    @Autowired
    private MessagingChannel messagingChannel;

    private BfdProperties bfdPropertiesDefault;

    private Duration bfdPropertiesApplyPeriod;

    @Override
    public CompletableFuture<List<LinkDto>> getLinks(SwitchId srcSwitch, Integer srcPort,
                                                     SwitchId dstSwitch, Integer dstPort) {
        final String correlationId = RequestCorrelationId.getId();
        logger.debug("Get links request received");
        GetLinksRequest request = null;
        try {
            request = new GetLinksRequest(new NetworkEndpointMask(srcSwitch, srcPort),
                    new NetworkEndpointMask(dstSwitch, dstPort));
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage());
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments when create 'get links' request");
        }
        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(), correlationId);

        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(IslInfoData.class::cast)
                        .map(linkMapper::mapResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<LinkPropsDto>> getLinkProps(SwitchId srcSwitch, Integer srcPort,
                                                              SwitchId dstSwitch, Integer dstPort) {
        final String correlationId = RequestCorrelationId.getId();
        logger.debug("Get link properties request received");
        LinkPropsGet request = new LinkPropsGet(new NetworkEndpointMask(srcSwitch, srcPort),
                new NetworkEndpointMask(dstSwitch, dstPort));
        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(), correlationId);

        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(LinkPropsData.class::cast)
                        .map(linkPropsMapper::toDto)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<BatchResults> setLinkProps(List<LinkPropsDto> linkPropsList) {
        logger.debug("Link props \"SET\" request received (consists of {} records)", linkPropsList.size());

        List<String> errors = new ArrayList<>();
        List<CompletableFuture<?>> pendingRequest = new ArrayList<>(linkPropsList.size());

        for (LinkPropsDto requestItem : linkPropsList) {
            org.openkilda.messaging.model.LinkPropsDto linkProps;
            try {
                linkProps = linkPropsMapper.toLinkProps(requestItem);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
                continue;
            }
            LinkPropsPut commandRequest = new LinkPropsPut(linkProps);
            String requestId = idFactory.produceChained(RequestCorrelationId.getId());
            CommandMessage message = new CommandMessage(commandRequest, System.currentTimeMillis(), requestId);

            pendingRequest.add(messagingChannel.sendAndGet(nbworkerTopic, message));
        }

        return collectResponses(pendingRequest, LinkPropsResponse.class)
                .thenApply(responses -> getLinkPropsResult(responses, errors));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<LinkMaxBandwidthDto> updateLinkBandwidth(SwitchId srcSwitch, Integer srcPort,
                                                                      SwitchId dstSwitch, Integer dstPort,
                                                                      LinkMaxBandwidthRequest input) {
        if (input.getMaxBandwidth() == null || input.getMaxBandwidth() < 0) {
            throw new MessageException(ErrorType.PARAMETERS_INVALID, "Invalid value of max_bandwidth",
                    "Maximum bandwidth must not be null");
        }
        if (srcPort < 0) {
            throw new MessageException(ErrorType.PARAMETERS_INVALID, "Invalid value of source port",
                    "Port number can't be negative");
        }
        if (dstPort < 0) {
            throw new MessageException(ErrorType.PARAMETERS_INVALID, "Invalid value of destination port",
                    "Port number can't be negative");
        }

        org.openkilda.messaging.model.LinkPropsDto linkProps = org.openkilda.messaging.model.LinkPropsDto.builder()
                .source(new NetworkEndpoint(srcSwitch, srcPort))
                .dest(new NetworkEndpoint(dstSwitch, dstPort))
                .props(ImmutableMap.of(LinkProps.MAX_BANDWIDTH_PROP_NAME,
                        input.getMaxBandwidth().toString()))
                .build();
        LinkPropsPut request = new LinkPropsPut(linkProps);
        String correlationId = RequestCorrelationId.getId();
        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(), correlationId);
        return messagingChannel.sendAndGet(nbworkerTopic, message)
                .thenApply(response -> {
                    if (((LinkPropsResponse) response).getLinkProps() != null) {
                        return linkPropsMapper.toLinkMaxBandwidth(((LinkPropsResponse) response).getLinkProps());
                    } else {
                        throw new MessageException(ErrorType.REQUEST_INVALID,
                                "Requested maximum bandwidth is too small",
                                ((LinkPropsResponse) response).getError());
                    }
                });
    }

    @Override
    public CompletableFuture<BatchResults> delLinkProps(List<LinkPropsDto> linkPropsList) {
        List<CompletableFuture<List<InfoData>>> pendingRequest = new ArrayList<>(linkPropsList.size());

        for (LinkPropsDto requestItem : linkPropsList) {
            LinkPropsDrop request = new LinkPropsDrop(linkPropsMapper.toLinkPropsMask(requestItem));
            String requestId = idFactory.produceChained(RequestCorrelationId.getId());
            CommandMessage message = new CommandMessage(request, System.currentTimeMillis(), requestId);

            pendingRequest.add(messagingChannel.sendAndGetChunked(nbworkerTopic, message));
        }

        return collectChunkedResponses(pendingRequest, LinkPropsResponse.class)
                .thenApply(responses -> getLinkPropsResult(responses, new ArrayList<>()));
    }

    private BatchResults getLinkPropsResult(List<LinkPropsResponse> responses, List<String> errors) {
        int successCount = 0;
        for (LinkPropsResponse linkProps : responses) {
            if (linkProps.isSuccess()) {
                successCount += 1;
            } else {
                errors.add(linkProps.getError());
            }
        }

        return new BatchResults(errors.size(), successCount, errors);
    }

    @Override
    public CompletableFuture<List<FlowResponsePayload>> getFlowsForLink(SwitchId srcSwitch, Integer srcPort,
                                                                        SwitchId dstSwitch, Integer dstPort) {
        final String correlationId = RequestCorrelationId.getId();
        logger.debug("Get all flows for a particular link request processing");
        GetFlowsForIslRequest data = null;
        try {
            data = new GetFlowsForIslRequest(new NetworkEndpoint(srcSwitch, srcPort),
                    new NetworkEndpoint(dstSwitch, dstPort), correlationId);
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage());
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments when create \"get flows for link\" request");
        }
        CommandMessage message = new CommandMessage(data, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(FlowResponse.class::cast)
                        .map(FlowResponse::getPayload)
                        .map(flowMapper::toFlowResponseOutput)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<String>> rerouteFlowsForLink(SwitchId srcSwitch, Integer srcPort,
                                                               SwitchId dstSwitch, Integer dstPort) {
        final String correlationId = RequestCorrelationId.getId();
        logger.debug("Reroute all flows for a particular link request processing");
        RerouteFlowsForIslRequest data = null;
        try {
            data = new RerouteFlowsForIslRequest(new NetworkEndpoint(srcSwitch, srcPort),
                    new NetworkEndpoint(dstSwitch, dstPort), correlationId);
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage());
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments when create \"reroute flows for link\" request");
        }
        CommandMessage message = new CommandMessage(data, System.currentTimeMillis(), correlationId, Destination.WFM);

        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(FlowsResponse.class::cast)
                        .map(FlowsResponse::getFlowIds)
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<LinkDto>> deleteLink(LinkParametersDto linkParameters, boolean force) {
        final String correlationId = RequestCorrelationId.getId();
        logger.info("Delete link request received: {}, force={}", linkParameters, force);

        DeleteLinkRequest request;
        try {
            request = new DeleteLinkRequest(new SwitchId(linkParameters.getSrcSwitch()), linkParameters.getSrcPort(),
                    new SwitchId(linkParameters.getDstSwitch()), linkParameters.getDstPort(), force);
        } catch (IllegalArgumentException e) {
            logger.error("Could not parse delete link request arguments: {}", e.getMessage());
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments when create 'delete link' request");
        }

        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(), correlationId);
        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(IslInfoData.class::cast)
                        .map(linkMapper::mapResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<LinkDto>> updateLinkUnderMaintenance(LinkUnderMaintenanceDto link) {

        final String correlationId = RequestCorrelationId.getId();
        logger.debug("Update under maintenance link request processing");
        UpdateLinkUnderMaintenanceRequest data = null;
        try {
            data = new UpdateLinkUnderMaintenanceRequest(
                    new NetworkEndpoint(new SwitchId(link.getSrcSwitch()), link.getSrcPort()),
                    new NetworkEndpoint(new SwitchId(link.getDstSwitch()), link.getDstPort()),
                    link.isUnderMaintenance(), link.isEvacuate());
        } catch (IllegalArgumentException e) {
            logger.error("Can not parse arguments: {}", e.getMessage());
            throw new MessageException(correlationId, System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), "Can not parse arguments when create 'update ISL Under maintenance' request");
        }

        CommandMessage message = new CommandMessage(data, System.currentTimeMillis(), correlationId, Destination.WFM);
        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> response.stream()
                        .map(IslInfoData.class::cast)
                        .map(linkMapper::mapResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<LinkDto>> writeBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, boolean isEnabled) {
        BfdProperties properties;
        if (isEnabled) {
            properties = bfdPropertiesDefault;
        } else {
            properties = BfdProperties.DISABLED;
        }

        return actualWriteBfdProperties(source, dest, properties)
                .thenApply(response -> Collections.singletonList(linkMapper.mapResponse(response.getLinkData())));
    }

    @Override
    public CompletableFuture<BfdPropertiesPayload> writeBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, BfdProperties properties) {
        if (properties == null) {
            properties = bfdPropertiesDefault;
        }
        return actualWriteBfdProperties(source, dest, properties)
                .thenApply(linkMapper::mapResponse);
    }

    @Override
    public CompletableFuture<BfdPropertiesPayload> readBfdProperties(NetworkEndpoint source, NetworkEndpoint dest) {
        logger.debug("Handling link {} ==> {} BFD properties read request", source, dest);
        return readBfdProperties(source, dest, RequestCorrelationId.getId()).thenApply(linkMapper::mapResponse);
    }

    @Override
    public CompletableFuture<BfdPropertiesResponse> readBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, String correlationId) {
        BfdPropertiesReadRequest request = linkMapper.mapBfdRequest(source, dest);
        return messagingChannel.sendAndGetChunked(nbworkerTopic, makeMessage(request, correlationId))
                .thenApply(response -> unpackBfdPropertiesResponse(response, request, RequestCorrelationId.getId()));
    }

    @Override
    public CompletableFuture<BfdPropertiesPayload> deleteBfdProperties(NetworkEndpoint source, NetworkEndpoint dest) {
        logger.debug("Handling link {} ==> {} BFD properties delete request (write wrapper)", source, dest);
        return writeBfdProperties(source, dest, BfdProperties.DISABLED);
    }

    private CompletableFuture<BfdPropertiesResponse> actualWriteBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, BfdProperties properties) {
        logger.debug("Handling link {} ==> {} BFD properties write request with payload {}", source, dest, properties);

        BfdPropertiesWriteRequest request = linkMapper.mapBfdRequest(source, dest, properties);
        CommandMessage message = makeMessage(request);

        final String correlationId = RequestCorrelationId.getId();
        BfdPropertiesMonitor monitor = new BfdPropertiesMonitor(
                this, taskScheduler, request.getProperties(), correlationId, bfdPropertiesApplyPeriod, clock);
        return messagingChannel.sendAndGetChunked(nbworkerTopic, message)
                .thenApply(response -> unpackBfdPropertiesResponse(response, request, correlationId))
                .thenCompose(monitor::consume)
                .handle((response, error) -> {
                    if (error != null) {
                        handleException(error);
                    }
                    return response;
                });
    }

    private BfdPropertiesResponse unpackBfdPropertiesResponse(
            List<InfoData> responseStream, CommandData request, String correlationId) {
        InfoData response = ensureExactlyOneResponse(responseStream, request, correlationId);
        if (response instanceof BfdPropertiesResponse) {
            return (BfdPropertiesResponse) response;
        }

        String description = String.format(
                "Got %s response type, while expecting %s",
                response.getClass().getName(), BfdPropertiesResponse.class.getName());
        throw new MessageException(
                correlationId, System.currentTimeMillis(), ErrorType.INTERNAL_ERROR,
                "Unexpected inter component response type/format", description);
    }

    private <T extends InfoData> T ensureExactlyOneResponse(
            List<T> sequence, CommandData request, String correlationId) {
        if (sequence.size() == 1) {
            return sequence.get(0);
        }

        String description = String.format(
                "Got %s responses on %s, while expecting exactly one", sequence.size(), request);
        throw new MessageException(
                correlationId, System.currentTimeMillis(), ErrorType.INTERNAL_ERROR,
                "Unexpected inter component response format", description);
    }

    private CommandMessage makeMessage(CommandData payload) {
        return makeMessage(payload, RequestCorrelationId.getId());
    }

    private CommandMessage makeMessage(CommandData payload, String correlationId) {
        return new CommandMessage(payload, System.currentTimeMillis(), correlationId);
    }

    private void handleException(Throwable error) {
        try {
            throw error;
        } catch (CompletionException e) {
            handleException(e.getCause());
        } catch (BfdPropertyApplyException e) {
            throw maskConcurrentException(
                    new InconclusiveException(e.getMessage(), linkMapper.mapResponse(e.getProperties())));
        } catch (Throwable e) {
            throw maskConcurrentException(e);
        }
    }

    /**
     * Make default bfd properties from values provided into application properties file.
     */
    @Autowired
    public void setBfdProperties(
            @Value("${bfd.interval_ms.default}") Integer interval,
            @Value("${bfd.multiplier.default}") Short multiplier) {
        if (interval == null || interval < 1 || multiplier == null || multiplier < 1) {
            throw new IllegalArgumentException(String.format(
                    "Invalid bfd properties defaults - all properties must be above 1: interval=%s, multiplier=%s",
                    interval, multiplier));
        }
        bfdPropertiesDefault = new BfdProperties(interval, multiplier);
    }

    /**
     * Setup BFD properties apply period/timeout.
     */
    @Autowired
    public void setBfdPropertiesApplyPeriod(
            @Value("${bfd.apply.period.seconds}") Long period) {
        if (period == null || period < 0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid \"bfd.apply.period.seconds\" property value %s - must be defined and greater than 0",
                    period));
        }
        bfdPropertiesApplyPeriod = Duration.ofSeconds(period);
    }
}
