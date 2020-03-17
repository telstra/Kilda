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

package org.openkilda.wfm.topology.flowhs.fsm.common.actions;

import static java.lang.String.format;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.RecoverablePersistenceException;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowPathSwappingFsm;
import org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A base for action classes that allocate resources for flow paths.
 */
@Slf4j
public abstract class BaseResourceAllocationAction<T extends FlowPathSwappingFsm<T, S, E, C>, S, E, C> extends
        NbTrackableAction<T, S, E, C> {
    protected final int transactionRetriesLimit;
    protected final int pathAllocationRetriesLimit;
    protected final int pathAllocationRetryDelay;
    protected final SwitchRepository switchRepository;
    protected final IslRepository islRepository;
    protected final PathComputer pathComputer;
    protected final FlowResourcesManager resourcesManager;
    protected final FlowPathBuilder flowPathBuilder;
    protected final FlowOperationsDashboardLogger dashboardLogger;

    public BaseResourceAllocationAction(PersistenceManager persistenceManager, int transactionRetriesLimit,
                                        int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                        PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                        FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager);
        this.transactionRetriesLimit = transactionRetriesLimit;
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

    @Override
    protected final Optional<Message> performWithResponse(S from, S to, E event, C context, T stateMachine) {
        if (!isAllocationRequired(stateMachine)) {
            return Optional.empty();
        }

        try {
            allocateInTransaction(stateMachine);

            return Optional.empty();
        } catch (UnroutableFlowException  ex) {
            String errorMessage = format("Not enough bandwidth or no path found. %s", ex.getMessage());
            stateMachine.saveActionToHistory(errorMessage);
            stateMachine.fireNoPathFound(errorMessage);

            return Optional.of(buildErrorMessage(stateMachine, ErrorType.NOT_FOUND,
                    getGenericErrorMessage(), errorMessage));
        } catch (RecoverableException ex) {
            String errorMessage = format("Failed to find a path. %s", ex.getMessage());
            stateMachine.saveActionToHistory(errorMessage);
            stateMachine.fireError(errorMessage);

            return Optional.of(buildErrorMessage(stateMachine, ErrorType.INTERNAL_ERROR,
                    getGenericErrorMessage(), errorMessage));
        } catch (ResourceAllocationException ex) {
            String errorMessage = format("Failed to allocate flow resources. %s", ex.getMessage());
            stateMachine.saveErrorToHistory(errorMessage, ex);
            stateMachine.fireError(errorMessage);

            return Optional.of(buildErrorMessage(stateMachine, ErrorType.INTERNAL_ERROR,
                    getGenericErrorMessage(), errorMessage));
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
     * Perform resource allocation in transactions, returns the allocated resources.
     */
    @SneakyThrows
    private void allocateInTransaction(T stateMachine) throws RecoverableException, UnroutableFlowException,
            ResourceAllocationException {
        RetryPolicy pathAllocationRetryPolicy = new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceAllocationException.class)
                .withMaxRetries(pathAllocationRetriesLimit);
        if (pathAllocationRetryDelay > 0) {
            pathAllocationRetryPolicy.withDelay(pathAllocationRetryDelay, TimeUnit.MILLISECONDS);
        }

        try {
            Failsafe.with(pathAllocationRetryPolicy)
                    .onRetry(e -> log.warn("Retrying path allocation as finished with exception", e))
                    .onRetriesExceeded(e -> log.warn("No more retry attempt for path allocation, final failure", e))
                    .run(() -> doAllocateInTransaction(stateMachine));
        } catch (Exception ex) {
            onFailure(stateMachine);

            if (ex instanceof FailsafeException) {
                throw ex.getCause();
            } else {
                throw ex;
            }
        }
    }

    @SneakyThrows
    private void doAllocateInTransaction(T stateMachine) {
        RetryPolicy txRetryPolicy = new RetryPolicy()
                .retryOn(RecoverablePersistenceException.class)
                .withMaxRetries(transactionRetriesLimit);
        try {
            persistenceManager.getTransactionManager().doInTransaction(txRetryPolicy, () -> allocate(stateMachine));
        } catch (FailsafeException ex) {
            throw ex.getCause();
        }
    }

    protected boolean isNotSamePath(PathPair pathPair, FlowPathPair flowPathPair) {
        return !flowPathBuilder.isSamePath(pathPair.getForward(), flowPathPair.getForward())
                || !flowPathBuilder.isSamePath(pathPair.getReverse(), flowPathPair.getReverse());
    }

    protected FlowPathPair createFlowPathPair(Flow flow, FlowPathPair pathsToReuseBandwidth,
                                              PathPair pathPair, FlowResources flowResources) {
        Cookie blank = FlowSegmentCookieSchema.INSTANCE.makeBlank();
        Cookie flowCookie = FlowSegmentCookieSchema.INSTANCE.setFlowEffectiveId(
                blank, flowResources.getUnmaskedCookie());

        FlowPath newForwardPath = flowPathBuilder.buildFlowPath(
                flow, flowResources.getForward(), pathPair.getForward(),
                FlowSegmentCookieSchema.INSTANCE.setDirection(flowCookie, FlowPathDirection.FORWARD));
        newForwardPath.setStatus(FlowPathStatus.IN_PROGRESS);
        FlowPath newReversePath = flowPathBuilder.buildFlowPath(
                flow, flowResources.getReverse(), pathPair.getReverse(),
                FlowSegmentCookieSchema.INSTANCE.setDirection(flowCookie, FlowPathDirection.REVERSE));
        newReversePath.setStatus(FlowPathStatus.IN_PROGRESS);
        FlowPathPair newFlowPaths = FlowPathPair.builder().forward(newForwardPath).reverse(newReversePath).build();

        log.debug("Persisting the paths {}", newFlowPaths);

        persistenceManager.getTransactionManager().doInTransaction(() -> {
            flowPathRepository.lockInvolvedSwitches(newForwardPath, newReversePath);

            flowPathRepository.createOrUpdate(newForwardPath);
            flowPathRepository.createOrUpdate(newReversePath);

            updateIslsForFlowPath(newForwardPath, pathsToReuseBandwidth.getForward());
            updateIslsForFlowPath(newReversePath, pathsToReuseBandwidth.getReverse());
        });

        return newFlowPaths;
    }

    private void updateIslsForFlowPath(FlowPath flowPath, FlowPath pathToReuseBandwidth)
            throws ResourceAllocationException {
        for (PathSegment pathSegment : flowPath.getSegments()) {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            long allowedOverprovisionedBandwidth = 0;
            if (pathToReuseBandwidth != null) {
                for (PathSegment reuseSegment : pathToReuseBandwidth.getSegments()) {
                    if (pathSegment.getSrcSwitch().equals(reuseSegment.getSrcSwitch())
                            && pathSegment.getSrcPort() == reuseSegment.getSrcPort()
                            && pathSegment.getDestSwitch().equals(reuseSegment.getDestSwitch())
                            && pathSegment.getDestPort() == reuseSegment.getDestPort()) {
                        allowedOverprovisionedBandwidth = pathToReuseBandwidth.getBandwidth();
                    }
                }
            }

            updateAvailableBandwidth(pathSegment.getSrcSwitch().getSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitch().getSwitchId(), pathSegment.getDestPort(),
                    allowedOverprovisionedBandwidth);
        }
    }

    private void updateAvailableBandwidth(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort,
                                          long allowedOverprovisionedBandwidth) throws ResourceAllocationException {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);
        log.debug("Updating ISL {}_{}-{}_{} with used bandwidth {}", srcSwitch, srcPort, dstSwitch, dstPort,
                usedBandwidth);
        long islAvailableBandwidth =
                islRepository.updateAvailableBandwidth(srcSwitch, srcPort, dstSwitch, dstPort, usedBandwidth);
        if ((islAvailableBandwidth + allowedOverprovisionedBandwidth) < 0) {
            throw new ResourceAllocationException(format("ISL %s_%d-%s_%d was overprovisioned",
                    srcSwitch, srcPort, dstSwitch, dstPort));
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
}
