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

package org.openkilda.wfm.topology.flowhs.service;

import static java.lang.String.format;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSegmentCookie.FlowSegmentCookieBuilder;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.create.command.ResourcesAllocationCommand;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse.ErrorCode;
import org.openkilda.wfm.topology.flowhs.service.DbResponse.DbResponseBuilder;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.v1.exceptions.TransientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class DbWorkerService {
    private final DbCommandCarrier carrier;
    private final PathComputer pathComputer;
    private final FlowResourcesManager resourcesManager;
    private final TransactionManager transactionManager;
    private final SwitchRepository switchRepository;
    private final IslRepository islRepository;
    private final FlowRepository flowRepository;
    private final FlowPathRepository flowPathRepository;

    private final FlowPathBuilder flowPathBuilder;
    private final int transactionRetries;

    private final Map<String, DbCommand> keyToRequest = new HashMap<>();

    public DbWorkerService(DbCommandCarrier carrier, PathComputer pathComputer, FlowResourcesManager resourcesManager,
                           PersistenceManager persistenceManager, int transactionRetries) {
        this.carrier = carrier;
        this.pathComputer = pathComputer;
        this.resourcesManager = resourcesManager;
        this.transactionManager = persistenceManager.getTransactionManager();
        this.switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        this.flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        this.flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
        this.flowPathBuilder = new FlowPathBuilder(
                switchRepository, persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository());
        this.transactionRetries = transactionRetries;
    }

    /**
     * Sends command to speaker.
     * @param key unique operation's key.
     * @param command command to be executed.
     */
    public void handleCommand(String key, DbCommand command) throws PipelineException {
        log.debug("Got a request from hub bolt {}", command);
        keyToRequest.put(key, command);
        if (command instanceof ResourcesAllocationCommand) {
            handleResourceAllocationForCreate((ResourcesAllocationCommand) command);
        }
    }

    public void handleResourceAllocationForCreate(ResourcesAllocationCommand command) {
        try {
            sendResponse(allocateResources(command));
        } catch (Exception e) {
            sendResponse(DbErrorResponse.builder()
                    .errorMessage(format("Unhandled error: %s", e.getMessage()))
                    .errorCode(ErrorCode.UNKNOWN)
                    .build());
        }
    }

    private DbResponse allocateResources(ResourcesAllocationCommand command) {
        FlowCreateContext context = command.getContext();
        DbResponseBuilder response = DbResponse.builder();
        log.debug("Allocation resources has been started");
        Optional<Flow> optionalFlow = getFlow(context, command.getFlowId());
        if (!optionalFlow.isPresent()) {
            log.warn("Flow {} has been deleted while creation was in progress", command.getFlowId());
            return response.success(false).build();
        }

        Flow flow = optionalFlow.get();
        try {
            getFlowGroupFromContext(context).ifPresent(flow::setGroupId);
            createFlowWithPaths(flow, response);
            response.flow(flow);
        } catch (UnroutableFlowException e) {
            return DbErrorResponse.builder()
                    .errorCode(ErrorCode.NOT_FOUND)
                    .errorMessage("Not enough bandwidth or no path found. " + e.getMessage())
                    .build();
        } catch (ResourceAllocationException e) {
            return DbErrorResponse.builder()
                    .errorCode(ErrorCode.INTERNAL_ERROR)
                    .errorMessage("Failed to allocate flow resources. " + e.getMessage())
                    .build();
        } catch (FlowNotFoundException e) {
            return DbErrorResponse.builder()
                    .errorCode(ErrorCode.NOT_FOUND)
                    .errorMessage("Couldn't find the diverse flow. " + e.getMessage())
                    .build();
        } catch (FlowAlreadyExistException e) {
            //if (!stateMachine.retryIfAllowed()) {
            return DbErrorResponse.builder()
                    .errorCode(ErrorCode.INTERNAL_ERROR)
                    .errorMessage("Flow already exist: " + e.getMessage())
                    .build();
            //} else {
//                     we have retried the operation, no need to respond.
//                    log.debug(e.getMessage(), e);
//                    sendResponse(Optional.empty());
//                    return;
//                }
        }

        return response.build();
    }

    private Optional<Flow> getFlow(FlowCreateContext context, String flowId) {
        Optional<RequestedFlow> targetFlow = Optional.ofNullable(context)
                .map(FlowCreateContext::getTargetFlow);
        if (targetFlow.isPresent()) {
            Flow flow = RequestedFlowMapper.INSTANCE.toFlow(targetFlow.get());
            flow.setStatus(FlowStatus.IN_PROGRESS);
            flow.setSrcSwitch(switchRepository.reload(flow.getSrcSwitch()));
            flow.setDestSwitch(switchRepository.reload(flow.getDestSwitch()));

            return Optional.of(flow);
        } else {
            // if flow is not in the context - it means that we are in progress of the retry, so flow should exist in DB
            return flowRepository.findById(flowId);
        }
    }

    private Optional<String> getFlowGroupFromContext(FlowCreateContext context) throws FlowNotFoundException {
        if (context != null) {
            String diverseFlowId = context.getTargetFlow().getDiverseFlowId();
            if (StringUtils.isNotBlank(diverseFlowId)) {
                return flowRepository.getOrCreateFlowGroupId(diverseFlowId)
                        .map(Optional::of)
                        .orElseThrow(() -> new FlowNotFoundException(diverseFlowId));
            }
        }
        return Optional.empty();
    }

    private void createFlowWithPaths(Flow flow, DbResponseBuilder response) throws UnroutableFlowException,
            ResourceAllocationException, FlowNotFoundException, FlowAlreadyExistException {
        try {
            Failsafe.with(new RetryPolicy()
                    .retryOn(RecoverableException.class)
                    .retryOn(ResourceAllocationException.class)
                    .retryOn(TransientException.class)
                    .withMaxRetries(transactionRetries))
                    .onRetry(e -> log.warn("Retrying transaction for resource allocation finished with exception", e))
                    .onRetriesExceeded(e -> log.warn("TX retry attempts exceed with error", e))
                    .run(() -> transactionManager.doInTransaction(() -> {
                        allocateMainPath(response, flow);
                        if (flow.isAllocateProtectedPath()) {
                            allocateProtectedPath(response, flow);
                        }
                    }));
        } catch (FailsafeException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof UnroutableFlowException) {
                throw (UnroutableFlowException) cause;
            } else if (cause instanceof ResourceAllocationException) {
                throw (ResourceAllocationException) cause;
            } else if (cause instanceof FlowNotFoundException) {
                throw (FlowNotFoundException) cause;
            } else {
                throw ex;
            }
        } catch (ConstraintViolationException e) {
            throw new FlowAlreadyExistException(format("Failed to save flow with id %s", flow.getFlowId()), e);
        }
        log.debug("Resources allocated successfully for the flow {}", flow.getFlowId());
    }

    private void allocateMainPath(DbResponseBuilder response, Flow flow) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException {
        PathPair pathPair = pathComputer.getPath(flow);
        FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath forward = flowPathBuilder.buildFlowPath(
                flow, flowResources.getForward(), pathPair.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
        forward.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setForwardPath(forward);

        FlowPath reverse = flowPathBuilder.buildFlowPath(
                flow, flowResources.getReverse(), pathPair.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
        reverse.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setReversePath(reverse);

        flowPathRepository.lockInvolvedSwitches(forward, reverse);
        flowRepository.createOrUpdate(flow);

        updateIslsForFlowPath(forward);
        updateIslsForFlowPath(reverse);

        log.debug("Allocated resources for the flow {}: {}", flow.getFlowId(), flowResources);

        response.flowResource(flowResources);
    }

    private void allocateProtectedPath(DbResponseBuilder response, Flow flow) throws UnroutableFlowException,
            RecoverableException, ResourceAllocationException, FlowNotFoundException {
        flow.setGroupId(getGroupId(flow.getFlowId()));
        PathPair protectedPath = pathComputer.getPath(flow);

        boolean overlappingProtectedPathFound =
                flowPathBuilder.arePathsOverlapped(protectedPath.getForward(), flow.getForwardPath())
                        || flowPathBuilder.arePathsOverlapped(protectedPath.getReverse(), flow.getReversePath());
        if (overlappingProtectedPathFound) {
            log.info("Couldn't find non overlapping protected path. Result flow state: {}", flow);
            throw new UnroutableFlowException("Couldn't find non overlapping protected path", flow.getFlowId());
        }

        log.debug("Creating the protected path {} for flow {}", protectedPath, flow);

        FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath forward = flowPathBuilder.buildFlowPath(
                flow, flowResources.getForward(), protectedPath.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), false);
        forward.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setProtectedForwardPath(forward);

        FlowPath reverse = flowPathBuilder.buildFlowPath(
                flow, flowResources.getReverse(), protectedPath.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), false);
        reverse.setStatus(FlowPathStatus.IN_PROGRESS);
        flow.setProtectedReversePath(reverse);

        flowPathRepository.lockInvolvedSwitches(forward, reverse);
        flowRepository.createOrUpdate(flow);

        updateIslsForFlowPath(forward);
        updateIslsForFlowPath(reverse);
        response.flowResource(flowResources);
    }

    private void updateIslsForFlowPath(FlowPath path) {
        path.getSegments().forEach(pathSegment -> {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            updateAvailableBandwidth(pathSegment.getSrcSwitch().getSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitch().getSwitchId(), pathSegment.getDestPort());
        });
    }

    private void updateAvailableBandwidth(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort) {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);

        Optional<Isl> matchedIsl = islRepository.findByEndpoints(srcSwitch, srcPort, dstSwitch, dstPort);
        matchedIsl.ifPresent(isl -> {
            isl.setAvailableBandwidth(isl.getMaxBandwidth() - usedBandwidth);
            islRepository.createOrUpdate(isl);
        });
    }

    private String getGroupId(String flowId) throws FlowNotFoundException {
        return flowRepository.getOrCreateFlowGroupId(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId));
    }

    private void sendResponse(DbResponse message) {
        carrier.sendResponse();
    }

    /**
     * Handles operation timeout.
     * @param key operation identifier.
     */
    public void handleTimeout(String key) throws PipelineException {
        /*FlowSegmentRequest failedRequest = keyToRequest.remove(key);

        SpeakerFlowSegmentResponse response = FlowErrorResponse.errorBuilder()
                .commandId(failedRequest.getCommandId())
                .switchId(failedRequest.getSwitchId())
                .metadata(failedRequest.getMetadata())
                .errorCode(ErrorCode.OPERATION_TIMED_OUT)
                .messageContext(failedRequest.getMessageContext())
                .build();
        carrier.sendResponse(key, response);
         */
    }
}
