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

package org.openkilda.wfm.topology.flowhs.fsm.common.actions;

import static java.lang.String.format;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.model.cookie.FlowSegmentCookie.FlowSegmentCookieBuilder;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.IslRepository.IslEndpoints;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.tx.TransactionRequired;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowPathSwappingFsm;
import org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder;

import com.google.common.base.Suppliers;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A base for action classes that allocate resources for flow paths.
 */
@Slf4j
public abstract class BaseResourceAllocationAction<T extends FlowPathSwappingFsm<T, S, E, C>, S, E, C> extends
        NbTrackableAction<T, S, E, C> {
    protected final int pathAllocationRetriesLimit;
    protected final int pathAllocationRetryDelay;
    protected final SwitchRepository switchRepository;
    protected final IslRepository islRepository;
    protected final PathComputer pathComputer;
    protected final FlowResourcesManager resourcesManager;
    protected final FlowPathBuilder flowPathBuilder;
    protected final FlowOperationsDashboardLogger dashboardLogger;

    public BaseResourceAllocationAction(PersistenceManager persistenceManager,
                                        int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                        PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                        FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager);
        this.pathAllocationRetriesLimit = pathAllocationRetriesLimit;
        this.pathAllocationRetryDelay = pathAllocationRetryDelay;

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        islRepository = repositoryFactory.createIslRepository();
        SwitchPropertiesRepository switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        flowPathBuilder = new FlowPathBuilder(switchRepository, switchPropertiesRepository);

        this.pathComputer = pathComputer;
        this.resourcesManager = resourcesManager;
        this.dashboardLogger = dashboardLogger;
    }

    @TimedExecution("fsm.resource_allocation_with_retries")
    @Override
    protected final Optional<Message> performWithResponse(S from, S to, E event, C context, T stateMachine) {
        if (!isAllocationRequired(stateMachine)) {
            return Optional.empty();
        }

        try {
            allocateWithRetries(stateMachine);

            return Optional.empty();
        } catch (UnroutableFlowException ex) {
            String errorMessage;
            if (ex.isIgnoreBandwidth()) {
                errorMessage = format("No path found. %s", ex.getMessage());
            } else {
                errorMessage = format("Not enough bandwidth or no path found. %s", ex.getMessage());
            }
            stateMachine.saveActionToHistory(errorMessage);
            stateMachine.fireNoPathFound(errorMessage);

            Message message = buildErrorMessage(stateMachine, ErrorType.NOT_FOUND,
                    getGenericErrorMessage(), errorMessage);
            stateMachine.setOperationResultMessage(message);
            return Optional.of(message);
        } catch (RecoverableException ex) {
            String errorMessage = format("Failed to find a path. %s", ex.getMessage());
            stateMachine.saveActionToHistory(errorMessage);
            stateMachine.fireError(errorMessage);

            Message message = buildErrorMessage(stateMachine, ErrorType.INTERNAL_ERROR,
                    getGenericErrorMessage(), errorMessage);
            stateMachine.setOperationResultMessage(message);
            return Optional.of(message);
        } catch (ResourceAllocationException ex) {
            String errorMessage = format("Failed to allocate flow resources. %s", ex.getMessage());
            stateMachine.saveErrorToHistory(errorMessage, ex);
            stateMachine.fireError(errorMessage);

            Message message = buildErrorMessage(stateMachine, ErrorType.INTERNAL_ERROR,
                    getGenericErrorMessage(), errorMessage);
            stateMachine.setOperationResultMessage(message);
            return Optional.of(message);
        }
    }

    /**
     * Check whether allocation is required, otherwise it's being skipped.
     */
    protected abstract boolean isAllocationRequired(T stateMachine);

    /**
     * Perform resource allocation, returns the allocated resources.
     */
    protected abstract void allocate(T stateMachine)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException;

    /**
     * Called in a case of allocation failure.
     */
    protected abstract void onFailure(T stateMachine);

    /**
     * Perform resource allocation in a transaction.
     */
    @SneakyThrows
    private void allocateWithRetries(T stateMachine) throws RecoverableException, UnroutableFlowException,
            ResourceAllocationException {
        RetryPolicy pathAllocationRetryPolicy = new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceAllocationException.class)
                .retryOn(UnroutableFlowException.class)
                .retryOn(PersistenceException.class)
                .withMaxRetries(pathAllocationRetriesLimit);
        if (pathAllocationRetryDelay > 0) {
            pathAllocationRetryPolicy.withDelay(pathAllocationRetryDelay, TimeUnit.MILLISECONDS);
        }
        SyncFailsafe failsafe = Failsafe.with(pathAllocationRetryPolicy)
                .onRetry(e -> log.warn("Failure in resource allocation. Retrying...", e))
                .onRetriesExceeded(e -> log.warn("Failure in resource allocation. No more retries", e));

        try {
            failsafe.run(() -> {
                try {
                    allocate(stateMachine);
                } catch (ResourceAllocationException ex) {
                    saveRejectedResources(stateMachine);
                    throw ex;
                }
            });
        } catch (FailsafeException ex) {
            onFailure(stateMachine);
            throw ex.getCause();
        } catch (Exception ex) {
            onFailure(stateMachine);
            throw ex;
        }
        log.debug("Resources allocated successfully for the flow {}", stateMachine.getFlowId());

        try {
            checkAllocatedPaths(stateMachine);
        } catch (ResourceAllocationException ex) {
            saveRejectedResources(stateMachine);
            throw ex;
        }
    }

    protected boolean isNotSamePath(GetPathsResult pathPair, FlowPathPair flowPathPair) {
        return flowPathPair.getForward() == null
                || !flowPathBuilder.isSamePath(pathPair.getForward(), flowPathPair.getForward())
                || flowPathPair.getReverse() == null
                || !flowPathBuilder.isSamePath(pathPair.getReverse(), flowPathPair.getReverse());
    }

    @TransactionRequired
    protected FlowPathPair createFlowPathPair(Flow flow,
                                              GetPathsResult pathPair, FlowResources flowResources,
                                              boolean forceToIgnoreBandwidth) throws ResourceAllocationException {
        final FlowSegmentCookieBuilder cookieBuilder = FlowSegmentCookie.builder()
                .flowEffectiveId(flowResources.getUnmaskedCookie());

        FlowPath newForwardPath = flowPathBuilder.buildFlowPath(
                flow, flowResources.getForward(), pathPair.getForward(),
                cookieBuilder.direction(FlowPathDirection.FORWARD).build(), forceToIgnoreBandwidth);
        newForwardPath.setStatus(FlowPathStatus.IN_PROGRESS);
        FlowPath newReversePath = flowPathBuilder.buildFlowPath(
                flow, flowResources.getReverse(), pathPair.getReverse(),
                cookieBuilder.direction(FlowPathDirection.REVERSE).build(), forceToIgnoreBandwidth);
        newReversePath.setStatus(FlowPathStatus.IN_PROGRESS);
        log.debug("Persisting the paths {}/{}", newForwardPath, newReversePath);

        flowPathRepository.add(newForwardPath);
        flowPathRepository.add(newReversePath);
        flow.addPaths(newForwardPath, newReversePath);

        return FlowPathPair.builder().forward(newForwardPath).reverse(newReversePath).build();
    }

    @TransactionRequired
    protected void updateIslsForFlowPath(PathId pathId, List<FlowPath> pathsToReuseBandwidth,
                                         boolean forceToIgnoreBandwidth)
            throws ResourceAllocationException {
        // Lazy initialisable map with reused bandwidth...
        Supplier<Map<IslEndpoints, Long>> reuseBandwidth = Suppliers.memoize(() -> {
            Map<IslEndpoints, Long> result = new HashMap<>();
            if (pathsToReuseBandwidth != null) {
                for (FlowPath pathToReuseBandwidth : pathsToReuseBandwidth) {
                    if (pathToReuseBandwidth != null) {
                        for (PathSegment reuseSegment : pathToReuseBandwidth.getSegments()) {
                            IslEndpoints isl = new IslEndpoints(reuseSegment.getSrcSwitchId().toString(),
                                    reuseSegment.getSrcPort(), reuseSegment.getDestSwitchId().toString(),
                                    reuseSegment.getDestPort());
                            result.put(isl, result.getOrDefault(isl, 0L) + pathToReuseBandwidth.getBandwidth());
                        }
                    }
                }
            }
            return result;
        });
        // Update ISLs which are occupied by the path.
        Map<IslEndpoints, Long> updatedIsls = islRepository.updateAvailableBandwidthOnIslsOccupiedByPath(pathId);
        for (Entry<IslEndpoints, Long> entry : updatedIsls.entrySet()) {
            IslEndpoints isl = entry.getKey();
            log.debug("ISL {}_{}-{}_{} was updated with used bandwidth",
                    isl.getSrcSwitch(), isl.getSrcPort(), isl.getDestSwitch(), isl.getDestPort());
            if (!forceToIgnoreBandwidth && entry.getValue() < 0) {
                log.debug("ISL {} is being over-provisioned, check if it's allowed", isl);

                long allowedOverprovisionedBandwidth = reuseBandwidth.get().getOrDefault(isl, 0L);
                if ((entry.getValue() + allowedOverprovisionedBandwidth) < 0) {
                    throw new ResourceAllocationException(format("ISL %s_%d-%s_%d was overprovisioned",
                            isl.getSrcSwitch(), isl.getSrcPort(), isl.getDestSwitch(), isl.getDestPort()));
                }
            }
        }
    }

    protected void saveAllocationActionWithDumpsToHistory(T stateMachine, Flow flow, String pathType,
                                                          FlowPathPair newFlowPaths) {
        FlowDumpData dumpData = HistoryMapper.INSTANCE.map(flow, newFlowPaths.getForward(), newFlowPaths.getReverse(),
                DumpType.STATE_AFTER);
        stateMachine.saveActionWithDumpToHistory(format("New %s paths were created", pathType),
                format("The flow paths %s / %s were created (with allocated resources)",
                        newFlowPaths.getForward().getPathId(), newFlowPaths.getReverse().getPathId()),
                dumpData);
    }

    private void checkAllocatedPaths(T stateMachine) throws ResourceAllocationException {
        List<PathId> pathIds = makeAllocatedPathIdsList(stateMachine);

        if (!pathIds.isEmpty()) {
            Collection<Isl> pathIsls = islRepository.findByPathIds(pathIds);
            for (Isl isl : pathIsls) {
                if (!IslStatus.ACTIVE.equals(isl.getStatus())) {
                    throw new ResourceAllocationException(
                            format("ISL %s_%d-%s_%d is not active on the allocated path",
                                    isl.getSrcSwitch().getSwitchId(), isl.getSrcPort(),
                                    isl.getDestSwitch().getSwitchId(), isl.getDestPort()));
                }
            }
        }
    }

    private void saveRejectedResources(T stateMachine) {
        stateMachine.getRejectedPaths().addAll(makeAllocatedPathIdsList(stateMachine));
        Optional.ofNullable(stateMachine.getNewPrimaryResources())
                .ifPresent(stateMachine.getRejectedResources()::add);
        Optional.ofNullable(stateMachine.getNewProtectedResources())
                .ifPresent(stateMachine.getRejectedResources()::add);

        stateMachine.setNewPrimaryResources(null);
        stateMachine.setNewPrimaryForwardPath(null);
        stateMachine.setNewPrimaryReversePath(null);
        stateMachine.setNewProtectedResources(null);
        stateMachine.setNewProtectedForwardPath(null);
        stateMachine.setNewProtectedReversePath(null);
    }

    private List<PathId> makeAllocatedPathIdsList(T stateMachine) {
        List<PathId> pathIds = new ArrayList<>();
        Optional.ofNullable(stateMachine.getNewPrimaryForwardPath()).ifPresent(pathIds::add);
        Optional.ofNullable(stateMachine.getNewPrimaryReversePath()).ifPresent(pathIds::add);
        Optional.ofNullable(stateMachine.getNewProtectedForwardPath()).ifPresent(pathIds::add);
        Optional.ofNullable(stateMachine.getNewProtectedReversePath()).ifPresent(pathIds::add);
        return pathIds;
    }
}
