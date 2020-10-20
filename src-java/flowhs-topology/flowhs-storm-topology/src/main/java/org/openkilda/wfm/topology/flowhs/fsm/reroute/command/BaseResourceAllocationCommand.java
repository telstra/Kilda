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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.command;

import static java.lang.String.format;
import static org.openkilda.messaging.error.ErrorType.INTERNAL_ERROR;
import static org.openkilda.messaging.error.ErrorType.NOT_FOUND;
import static org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType.FLOW_PROCESSING;

import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSegmentCookie.FlowSegmentCookieBuilder;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.tx.TransactionRequired;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.BaseAllocateResourcesResponse;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;
import org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType;
import org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import com.google.common.annotations.VisibleForTesting;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public abstract class BaseResourceAllocationCommand extends DbCommand implements ICommand<SpeakerWorkerService> {
    CommandContext commandContext;
    FlowRerouteContext context;
    String flowId;
    FlowEncapsulationType newEncapsulationType;
    boolean recreateIfSamePath;
    Collection<PathId> rejectedPaths;

    public BaseResourceAllocationCommand(@NonNull MessageContext messageContext, @NonNull UUID commandId,
                                         CommandContext commandContext, FlowRerouteContext context, String flowId,
                                         FlowEncapsulationType newEncapsulationType, boolean recreateIfSamePath,
                                         Collection<PathId> rejectedPaths) {
        super(messageContext, commandId);
        this.commandContext = commandContext;
        this.context = context;
        this.flowId = flowId;
        this.newEncapsulationType = newEncapsulationType;
        this.recreateIfSamePath = recreateIfSamePath;
        this.rejectedPaths = rejectedPaths;
    }

    /**
     * Perform resource allocation, returns the allocated resources.
     */
    protected abstract BaseAllocateResourcesResponse allocate(SpeakerWorkerService service)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException;

    /**
     * Called in a case of allocation failure.
     */
    // TODO remove
    protected abstract void onFailure(SpeakerWorkerService service);

    @Override
    public void apply(SpeakerWorkerService service) {
        log.info("Resources allocation for flow {} has been started", flowId);
        final long time = System.currentTimeMillis();

        try {
            BaseAllocateResourcesResponse response = allocateWithRetries(service);
            log.warn("HSTIME reroute resource allocation (primary or protected) "
                    + (System.currentTimeMillis() - time));
            service.sendResponse(response);
        } catch (UnroutableFlowException e) {
            String errorMessage;
            if (e.isIgnoreBandwidth()) {
                errorMessage = format("No path found. %s", e.getMessage());
            } else {
                errorMessage = format("Not enough bandwidth or no path found. %s", e.getMessage());
            }
            log.error(errorMessage, e);
            sendError(service, NOT_FOUND, FLOW_PROCESSING, errorMessage);
        } catch (RecoverableException e) {
            String errorMessage = format("Failed to find a path. %s", e.getMessage());
            log.error(errorMessage, e);
            sendError(service, INTERNAL_ERROR, FLOW_PROCESSING, errorMessage);
        } catch (Exception e) {
            String errorMessage = format("Failed to allocate flow resources. %s", e.getMessage());
            log.error(errorMessage, e);
            sendError(service, INTERNAL_ERROR, FLOW_PROCESSING, errorMessage);
        }
    }

    @TransactionRequired
    protected FlowPathPair createFlowPathPair(SpeakerWorkerService service, Flow flow,
                                              List<FlowPath> pathsToReuseBandwidth, GetPathsResult pathPair,
                                              FlowResources flowResources, boolean forceToIgnoreBandwidth)
            throws ResourceAllocationException {
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath newForwardPath = service.getFlowPathBuilder().buildFlowPath(
                flow, flowResources.getForward(), pathPair.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), forceToIgnoreBandwidth);
        newForwardPath.setStatus(FlowPathStatus.IN_PROGRESS);
        FlowPath newReversePath = service.getFlowPathBuilder().buildFlowPath(
                flow, flowResources.getReverse(), pathPair.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), forceToIgnoreBandwidth);
        newReversePath.setStatus(FlowPathStatus.IN_PROGRESS);
        log.debug("Persisting the paths {}/{}", newForwardPath, newReversePath);

        service.getFlowPathRepository().add(newForwardPath);
        service.getFlowPathRepository().add(newReversePath);
        flow.addPaths(newForwardPath, newReversePath);

        updateIslsForFlowPath(service, newForwardPath, pathsToReuseBandwidth, forceToIgnoreBandwidth);
        updateIslsForFlowPath(service, newReversePath, pathsToReuseBandwidth, forceToIgnoreBandwidth);

        return FlowPathPair.builder().forward(newForwardPath).reverse(newReversePath).build();
    }

    private void updateIslsForFlowPath(SpeakerWorkerService service, FlowPath flowPath,
                                       List<FlowPath> pathsToReuseBandwidth, boolean forceToIgnoreBandwidth)
            throws ResourceAllocationException {
        for (PathSegment pathSegment : flowPath.getSegments()) {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            long allowedOverprovisionedBandwidth = 0;
            if (pathsToReuseBandwidth != null) {
                for (FlowPath pathToReuseBandwidth : pathsToReuseBandwidth) {
                    if (pathToReuseBandwidth != null) {
                        for (PathSegment reuseSegment : pathToReuseBandwidth.getSegments()) {
                            if (pathSegment.getSrcSwitch().equals(reuseSegment.getSrcSwitch())
                                    && pathSegment.getSrcPort() == reuseSegment.getSrcPort()
                                    && pathSegment.getDestSwitch().equals(reuseSegment.getDestSwitch())
                                    && pathSegment.getDestPort() == reuseSegment.getDestPort()) {
                                allowedOverprovisionedBandwidth += pathToReuseBandwidth.getBandwidth();
                            }
                        }
                    }
                }
            }

            updateAvailableBandwidth(service, pathSegment.getSrcSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitchId(), pathSegment.getDestPort(),
                    allowedOverprovisionedBandwidth, forceToIgnoreBandwidth);
        }
    }

    @VisibleForTesting
    void updateAvailableBandwidth(SpeakerWorkerService service, SwitchId srcSwitch, int srcPort, SwitchId dstSwitch,
                                  int dstPort, long allowedOverprovisionedBandwidth,
                                  boolean forceToIgnoreBandwidth) throws ResourceAllocationException {
        long usedBandwidth = service.getFlowPathRepository().getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);
        log.debug("Updating ISL {}_{}-{}_{} with used bandwidth {}", srcSwitch, srcPort, dstSwitch, dstPort,
                usedBandwidth);
        long islAvailableBandwidth = service.getIslRepository().updateAvailableBandwidth(srcSwitch, srcPort,
                dstSwitch, dstPort, usedBandwidth);
        if (!forceToIgnoreBandwidth && (islAvailableBandwidth + allowedOverprovisionedBandwidth) < 0) {
            throw new ResourceAllocationException(format("ISL %s_%d-%s_%d was overprovisioned",
                    srcSwitch, srcPort, dstSwitch, dstPort));
        }
    }

    @SneakyThrows
    private BaseAllocateResourcesResponse allocateWithRetries(SpeakerWorkerService service)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        RetryPolicy pathAllocationRetryPolicy = new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceAllocationException.class)
                .retryOn(UnroutableFlowException.class)
                .retryOn(PersistenceException.class)
                .withMaxRetries(service.getPathAllocationRetriesLimit());
        if (service.getPathAllocationRetryDelay() > 0) {
            pathAllocationRetryPolicy.withDelay(service.getPathAllocationRetryDelay(), TimeUnit.MILLISECONDS);
        }
        SyncFailsafe<Object> failsafe = Failsafe.with(pathAllocationRetryPolicy)
                .onRetry(e -> log.warn("Failure in resource allocation. Retrying...", e))
                .onRetriesExceeded(e -> log.warn("Failure in resource allocation. No more retries", e));

        BaseAllocateResourcesResponse response;
        try {
            response = failsafe.get(() -> allocate(service));
        } catch (FailsafeException ex) {
            //onFailure(service);
            throw ex.getCause();
        } catch (Exception ex) {
            //onFailure(service);
            throw ex;
        }
        log.debug("Resources allocated successfully for the flow {}", flowId);
        // TODO handle rejected
        // try {
        //     checkAllocatedPaths(stateMachine);
        // } catch (ResourceAllocationException ex) {
        //     saveRejectedResources(stateMachine);
        //     throw ex;
        // }
        return response;
    }

    protected PathComputationStrategy[] getBackUpStrategies(PathComputationStrategy strategy) {
        if (PathComputationStrategy.MAX_LATENCY.equals(strategy)) {
            return new PathComputationStrategy[]{PathComputationStrategy.LATENCY};
        }
        return new PathComputationStrategy[0];
    }

    private void sendError(SpeakerWorkerService service, ErrorType errorType, DbOperationErrorType operationErrorType,
                           String message) {
        DbErrorResponse dbErrorResponse = new DbErrorResponse(
                messageContext, commandId, errorType, operationErrorType, message);
        service.sendResponse(dbErrorResponse);
    }

    protected static boolean isNotSamePath(GetPathsResult pathPair, FlowPathPair flowPathPair) {
        return flowPathPair.getForward() == null
                || !FlowPathBuilder.isSamePath(pathPair.getForward(), flowPathPair.getForward())
                || flowPathPair.getReverse() == null
                || !FlowPathBuilder.isSamePath(pathPair.getReverse(), flowPathPair.getReverse());
    }

    ////////////////////////////////////////////////////////////////////////////


    //    private void checkAllocatedPaths(T stateMachine) throws ResourceAllocationException {
    //        List<PathId> pathIds = makeAllocatedPathIdsList(stateMachine);
    //
    //        if (!pathIds.isEmpty()) {
    //            Collection<Isl> pathIsls = islRepository.findByPathIds(pathIds);
    //            for (Isl isl : pathIsls) {
    //                if (!IslStatus.ACTIVE.equals(isl.getStatus())) {
    //                    throw new ResourceAllocationException(
    //                            format("ISL %s_%d-%s_%d is not active on the allocated path",
    //                                    isl.getSrcSwitch().getSwitchId(), isl.getSrcPort(),
    //                                    isl.getDestSwitch().getSwitchId(), isl.getDestPort()));
    //                }
    //            }
    //        }
    //    }

    //    private void saveRejectedResources(T stateMachine) {
    //        stateMachine.getRejectedPaths().addAll(makeAllocatedPathIdsList(stateMachine));
    //        Optional.ofNullable(stateMachine.getNewPrimaryResources())
    //                .ifPresent(stateMachine.getRejectedResources()::add);
    //        Optional.ofNullable(stateMachine.getNewProtectedResources())
    //                .ifPresent(stateMachine.getRejectedResources()::add);
    //
    //        stateMachine.setNewPrimaryResources(null);
    //        stateMachine.setNewPrimaryForwardPath(null);
    //        stateMachine.setNewPrimaryReversePath(null);
    //        stateMachine.setNewProtectedResources(null);
    //        stateMachine.setNewProtectedForwardPath(null);
    //        stateMachine.setNewProtectedReversePath(null);
    //    }
    //
    //    private List<PathId> makeAllocatedPathIdsList(T stateMachine) {
    //        List<PathId> pathIds = new ArrayList<>();
    //        Optional.ofNullable(stateMachine.getNewPrimaryForwardPath()).ifPresent(pathIds::add);
    //        Optional.ofNullable(stateMachine.getNewPrimaryReversePath()).ifPresent(pathIds::add);
    //        Optional.ofNullable(stateMachine.getNewProtectedForwardPath()).ifPresent(pathIds::add);
    //        Optional.ofNullable(stateMachine.getNewProtectedReversePath()).ifPresent(pathIds::add);
    //        return pathIds;
    //    }
}
