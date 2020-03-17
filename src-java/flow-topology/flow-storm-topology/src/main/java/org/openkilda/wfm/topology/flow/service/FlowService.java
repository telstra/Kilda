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

package org.openkilda.wfm.topology.flow.service;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.apache.commons.collections4.ListUtils.union;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandGroup;
import org.openkilda.messaging.command.CommandGroup.FailureReaction;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.DeallocateFlowResourcesRequest;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.flow.UpdateFlowPathStatusRequest;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.model.Cookie;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.pce.Path;
import org.openkilda.pce.Path.Segment;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.FetchStrategy;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.FlowAlreadyExistException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.flow.resources.ResourceNotAvailableException;
import org.openkilda.wfm.share.flow.service.FlowCommandFactory;
import org.openkilda.wfm.share.mappers.FlowMapper;
import org.openkilda.wfm.share.service.IntersectionComputer;
import org.openkilda.wfm.topology.flow.model.FlowData;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flow.model.FlowPathWithEncapsulation;
import org.openkilda.wfm.topology.flow.model.FlowPathsWithEncapsulation;
import org.openkilda.wfm.topology.flow.model.FlowPathsWithEncapsulation.FlowPathsWithEncapsulationBuilder;
import org.openkilda.wfm.topology.flow.model.ReroutedFlowPaths;
import org.openkilda.wfm.topology.flow.model.UpdatedFlowPathsWithEncapsulation;
import org.openkilda.wfm.topology.flow.validation.FlowValidationException;
import org.openkilda.wfm.topology.flow.validation.FlowValidator;
import org.openkilda.wfm.topology.flow.validation.SwitchValidationException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.exceptions.TransientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class FlowService extends BaseFlowService {

    private static final int MAX_TRANSACTION_RETRY_COUNT = 3;
    private static final int RETRY_DELAY = 100;

    private final SwitchRepository switchRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;
    private final FlowPathRepository flowPathRepository;
    private final IslRepository islRepository;
    private final KildaConfigurationRepository kildaConfigurationRepository;
    private final FeatureTogglesRepository featureTogglesRepository;
    private final SwitchConnectedDeviceRepository switchConnectedDeviceRepository;
    private final PathComputerFactory pathComputerFactory;
    private final FlowValidator flowValidator;
    private final FlowCommandFactory flowCommandFactory;

    public FlowService(@NonNull PersistenceManager persistenceManager, @NonNull PathComputerFactory pathComputerFactory,
                       @NonNull FlowResourcesManager flowResourcesManager, @NonNull FlowValidator flowValidator,
                       @NonNull FlowCommandFactory flowCommandFactory) {
        super(persistenceManager, flowResourcesManager);
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        islRepository = repositoryFactory.createIslRepository();
        kildaConfigurationRepository = repositoryFactory.createKildaConfigurationRepository();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        switchConnectedDeviceRepository = repositoryFactory.createSwitchConnectedDeviceRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        this.pathComputerFactory = pathComputerFactory;
        this.flowValidator = flowValidator;
        this.flowCommandFactory = flowCommandFactory;
    }

    /**
     * Creates a flow by allocating a path and resources. Stores the flow entities into DB, and
     * invokes flow rules installation via the command sender.
     * <p/>
     * The flow and paths are created with IN_PROGRESS status.
     *
     * @param flow the flow to be created.
     * @param diverseFlowId the flow id to build diverse group.
     * @param sender the command sender for flow rules installation.
     * @return the created flow with the path and resources set.
     */
    public Flow createFlow(Flow flow, String diverseFlowId, FlowCommandSender sender)
            throws RecoverableException, UnroutableFlowException, FlowAlreadyExistException, FlowValidationException,
            SwitchValidationException, FlowNotFoundException, ResourceAllocationException {
        dashboardLogger.onFlowCreate(flow);

        flowValidator.validate(flow);

        if (doesFlowExist(flow.getFlowId())) {
            throw new FlowAlreadyExistException(flow.getFlowId());
        }

        if (diverseFlowId != null) {
            checkDiverseFlow(flow, diverseFlowId);
            flow.setGroupId(getOrCreateFlowGroupId(diverseFlowId));
        }

        FlowPathsWithEncapsulation result = null;
        try {
            result = (FlowPathsWithEncapsulation) getFailsafe().get(
                    () -> transactionManager.doInTransaction(() -> {
                        Optional<SwitchProperties> srcSwitchProperties = switchPropertiesRepository.findBySwitchId(
                                flow.getSrcSwitch().getSwitchId());
                        boolean srcWithMultiTable = false;
                        boolean srcWithSwitchLldp = false;
                        boolean srcWithSwitchArp = false;
                        if (srcSwitchProperties.isPresent()) {
                            srcWithMultiTable = srcSwitchProperties.get().isMultiTable();
                            srcWithSwitchLldp = srcSwitchProperties.get().isSwitchLldp();
                            srcWithSwitchArp = srcSwitchProperties.get().isSwitchArp();
                        }
                        flow.setSrcWithMultiTable(srcWithMultiTable);
                        flow.getDetectConnectedDevices().setSrcSwitchLldp(srcWithSwitchLldp);
                        flow.getDetectConnectedDevices().setSrcSwitchArp(srcWithSwitchArp);
                        Optional<SwitchProperties> destSwitchProperties = switchPropertiesRepository.findBySwitchId(
                                flow.getDestSwitch().getSwitchId());
                        boolean destWithMultiTable = false;
                        boolean destWithSwitchLldp = false;
                        boolean destWithSwitchArp = false;
                        if (destSwitchProperties.isPresent()) {
                            destWithMultiTable = destSwitchProperties.get().isMultiTable();
                            destWithSwitchLldp = destSwitchProperties.get().isSwitchLldp();
                            destWithSwitchArp = destSwitchProperties.get().isSwitchArp();
                        }
                        flow.setDestWithMultiTable(destWithMultiTable);
                        flow.getDetectConnectedDevices().setDstSwitchLldp(destWithSwitchLldp);
                        flow.getDetectConnectedDevices().setDstSwitchArp(destWithSwitchArp);

                        // TODO: the strategy is defined either per flow or system-wide.
                        PathComputer pathComputer = pathComputerFactory.getPathComputer();
                        PathPair pathPair = pathComputer.getPath(flow);

                        FlowResources flowResources = flowResourcesManager.allocateFlowResources(flow);

                        Instant timestamp = Instant.now();
                        // Build and store the flow with a path pair, use allocated resources for paths.
                        FlowPathPair flowPathPair =
                                buildFlowPathPair(flow, pathPair, flowResources, FlowPathStatus.IN_PROGRESS, timestamp);

                        Flow flowWithPaths = buildFlowWithPaths(flow, flowPathPair, FlowStatus.IN_PROGRESS, timestamp);
                        flowWithPaths.setTimeCreate(timestamp);

                        log.info("Creating the flow: {}", flowWithPaths);

                        flowPathRepository.lockInvolvedSwitches(flowPathPair.getForward(), flowPathPair.getReverse());

                        // Store the flow and both paths
                        flowRepository.createOrUpdate(flowWithPaths);

                        updateIslsForFlowPath(flowPathPair.getForward());
                        updateIslsForFlowPath(flowPathPair.getReverse());

                        FlowResources protectedFlowResources = null;
                        if (flowWithPaths.isAllocateProtectedPath()) {
                            protectedFlowResources = createProtectedPath(flowWithPaths, timestamp);
                        }

                        return buildFlowPathsWithEncapsulation(flowWithPaths, flowResources, protectedFlowResources);
                    }));
        } catch (FailsafeException e) {
            unwrapCrudFaisafeException(e);
        }

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(flow.getFlowId(),
                createInstallRulesGroups(result),
                createFlowPathStatusRequests(result, FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(result, FlowPathStatus.INACTIVE));

        return result.getFlow();
    }

    @VisibleForTesting
    FlowResources createProtectedPath(Flow flow, Instant timestamp)
            throws RecoverableException, UnroutableFlowException, FlowNotFoundException, ResourceAllocationException,
            FlowValidationException {
        if (flow.isOneSwitchFlow()) {
            throw new FlowValidationException("Couldn't setup protected path for one-switch flow",
                    ErrorType.PARAMETERS_INVALID);
        }

        flow.setGroupId(
                getOrCreateFlowGroupId(flow.getFlowId()));

        PathComputer pathComputer = pathComputerFactory.getPathComputer();
        PathPair protectedPathPair = pathComputer.getPath(flow);

        log.info("Creating the protected path {} for flow {}", protectedPathPair, flow);

        FlowResources flowResources = flowResourcesManager.allocateFlowResources(flow);
        FlowPathPair pathPair =
                buildFlowPathPair(flow, protectedPathPair, flowResources, FlowPathStatus.IN_PROGRESS, timestamp);

        checkProtectedPathsDontOverlapsWithPrimary(flow, pathPair);

        flow.setProtectedForwardPath(pathPair.getForward());
        flow.setProtectedReversePath(pathPair.getReverse());

        flowRepository.createOrUpdate(flow);

        updateIslsForFlowPath(flow.getProtectedForwardPath());
        updateIslsForFlowPath(flow.getProtectedReversePath());

        return flowResources;
    }

    /**
     * Stores a flow and related entities into DB, and invokes flow rules installation via the command sender.
     *
     * @param flow the flow to be saved.
     * @param sender the command sender for flow rules installation.
     */
    public void saveFlow(Flow flow, FlowCommandSender sender) throws FlowAlreadyExistException,
            ResourceAllocationException {

        dashboardLogger.onFlowPush(flow);

        String flowId = flow.getFlowId();
        if (doesFlowExist(flowId)) {
            throw new FlowAlreadyExistException(flowId);
        }

        FlowResources flowResources = flowResourcesManager.allocateFlowResources(flow);

        // Store the flow, use allocated resources for paths.

        FlowPathsWithEncapsulation result = transactionManager.doInTransaction(() -> {
            Instant timestamp = Instant.now();
            FlowPathPair flowPathPair = buildFlowPathPair(flow, flowResources, timestamp);

            Flow flowWithPaths = buildFlowWithPaths(flow, flowPathPair, flow.getStatus(), timestamp);
            flowWithPaths.setTimeCreate(timestamp);

            log.info("Saving (pushing) the flow: {}", flowWithPaths);

            flowPathRepository.lockInvolvedSwitches(flowWithPaths.getForwardPath(), flowWithPaths.getReversePath());

            //TODO(siakovenko): flow needs to be validated (existence of switches, same end-points, etc.)

            // Store the flow and both paths
            flowRepository.createOrUpdate(flowWithPaths);

            updateIslsForFlowPath(flowWithPaths.getForwardPath());
            updateIslsForFlowPath(flowWithPaths.getReversePath());

            return buildFlowPathsWithEncapsulation(flowWithPaths, flowResources, null);
        });

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(flowId,
                createInstallRulesGroups(result),
                createFlowPathStatusRequests(result, FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(result, FlowPathStatus.INACTIVE));
    }

    /**
     * Deletes a flow and its paths from DB. Initiates resource deallocation.
     * Invokes flow rules deletion via the command sender.
     *
     * @param flowId the flow to be deleted.
     * @param sender the command sender for flow rules deletion.
     * @return the deleted flow.
     */
    public FlowDto deleteFlow(String flowId, FlowCommandSender sender) throws FlowNotFoundException {
        dashboardLogger.onFlowDelete(flowId);

        RetryPolicy retryPolicy = new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceNotAvailableException.class)
                .retryOn(TransientException.class)
                .withDelay(RETRY_DELAY, TimeUnit.MILLISECONDS)
                .withMaxRetries(MAX_TRANSACTION_RETRY_COUNT);

        FlowPathsWithEncapsulation result;
        try {
            result = transactionManager.doInTransaction(retryPolicy, () -> {
                FlowPathsWithEncapsulation flowPathsWithEncapsulation = getFlowPathPairWithEncapsulation(flowId)
                        .orElseThrow(() -> new FlowNotFoundException(flowId));

                Flow flow = flowPathsWithEncapsulation.getFlow();

                log.info("Deleting the flow: {}", flow);

                flowPathRepository.lockInvolvedSwitches(flow.getPaths().toArray(new FlowPath[0]));

                switchConnectedDeviceRepository.findByFlowId(flowId).forEach(switchConnectedDeviceRepository::delete);

                // Remove flow and all associated paths
                flowRepository.delete(flow);

                flow.getPaths().forEach(this::updateIslsForFlowPath);

                return flowPathsWithEncapsulation;
            });
        } catch (FailsafeException e) {
            if (e.getCause() instanceof FlowNotFoundException) {
                throw (FlowNotFoundException) e.getCause();
            } else {
                throw e;
            }
        }

        // Assemble a command batch with RemoveRule commands and resource deallocation requests.

        List<CommandGroup> commandGroups = new ArrayList<>();
        // We can assemble all paths into a single command batch as regardless of each execution result,
        // the TransactionBolt will try to perform all of them.
        // This is because FailureReaction.IGNORE used in createRemoveXXX methods.
        commandGroups.addAll(createRemoveRulesGroups(result));
        commandGroups.addAll(createDeallocateResourcesGroups(result));

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(flowId, commandGroups, emptyList(), emptyList());

        return FlowMapper.INSTANCE.map(result.getFlow());
    }

    /**
     * Replaces a flow with the new one. Allocates a path and resources.
     * Stores the flow entities into DB, and invokes flow rules installation and deletion via the command sender.
     * <p/>
     * The updated flow has IN_PROGRESS status.
     *
     * @param updatingFlow the flow to be updated.
     * @param diverseFlowId the flow id to build diverse group.
     * @param sender the command sender for flow rules installation and deletion.
     * @return the updated flow with the path and resources set.
     */
    public Flow updateFlow(Flow updatingFlow, String diverseFlowId, FlowCommandSender sender)
            throws RecoverableException, UnroutableFlowException, FlowNotFoundException, FlowValidationException,
            SwitchValidationException, ResourceAllocationException {
        dashboardLogger.onFlowUpdate(updatingFlow);

        flowValidator.validate(updatingFlow);

        String flowId = updatingFlow.getFlowId();
        FlowPathsWithEncapsulation currentFlow =
                getFlowPathPairWithEncapsulation(flowId).orElseThrow(() -> new FlowNotFoundException(flowId));

        if (diverseFlowId == null) {
            updatingFlow.setGroupId(null);
        } else {
            checkDiverseFlow(updatingFlow, diverseFlowId);
            updatingFlow.setGroupId(getOrCreateFlowGroupId(diverseFlowId));
        }

        UpdatedFlowPathsWithEncapsulation result = updateFlowAndPaths(currentFlow, updatingFlow, sender);

        return result.getFlow();
    }

    private UpdatedFlowPathsWithEncapsulation updateFlowAndPaths(FlowPathsWithEncapsulation currentFlow,
                                                                 Flow updatingFlow, FlowCommandSender sender)
            throws ResourceAllocationException, UnroutableFlowException, RecoverableException, FlowNotFoundException,
            FlowValidationException {

        UpdatedFlowPathsWithEncapsulation result = null;
        try {
            result = (UpdatedFlowPathsWithEncapsulation) getFailsafe().get(
                    () -> transactionManager.doInTransaction(() -> {
                        Optional<SwitchProperties> srcSwitchProperties = switchPropertiesRepository.findBySwitchId(
                                updatingFlow.getSrcSwitch().getSwitchId());
                        boolean srcWithMultiTable = false;
                        boolean srcWithSwitchLldp = false;
                        boolean srcWithSwitchArp = false;
                        if (srcSwitchProperties.isPresent()) {
                            srcWithMultiTable = srcSwitchProperties.get().isMultiTable();
                            srcWithSwitchLldp = srcSwitchProperties.get().isSwitchLldp();
                            srcWithSwitchArp = srcSwitchProperties.get().isSwitchArp();
                        }
                        updatingFlow.setSrcWithMultiTable(srcWithMultiTable);
                        updatingFlow.getDetectConnectedDevices().setSrcSwitchLldp(srcWithSwitchLldp);
                        updatingFlow.getDetectConnectedDevices().setSrcSwitchArp(srcWithSwitchArp);
                        Optional<SwitchProperties> destSwitchProperties = switchPropertiesRepository.findBySwitchId(
                                updatingFlow.getDestSwitch().getSwitchId());
                        boolean destWithMultiTable = false;
                        boolean destWithSwitchLldp = false;
                        boolean destWithSwitchArp = false;
                        if (destSwitchProperties.isPresent()) {
                            destWithMultiTable = destSwitchProperties.get().isMultiTable();
                            destWithSwitchLldp = destSwitchProperties.get().isSwitchLldp();
                            destWithSwitchArp = destSwitchProperties.get().isSwitchArp();
                        }
                        updatingFlow.setDestWithMultiTable(destWithMultiTable);
                        updatingFlow.getDetectConnectedDevices().setDstSwitchLldp(destWithSwitchLldp);
                        updatingFlow.getDetectConnectedDevices().setDstSwitchArp(destWithSwitchArp);
                        PathComputer pathComputer = pathComputerFactory.getPathComputer();
                        PathPair newPathPair = pathComputer.getPath(updatingFlow,
                                currentFlow.getFlow().getFlowPathIds());

                        log.info("Updating the flow with {} and path: {}", updatingFlow, newPathPair);

                        FlowResources flowResources = flowResourcesManager.allocateFlowResources(updatingFlow);

                        // Recreate the flow, use allocated resources for new paths.
                        Instant timestamp = Instant.now();
                        FlowPathPair newFlowPathPair =
                                buildFlowPathPair(updatingFlow, newPathPair, flowResources, FlowPathStatus.IN_PROGRESS,
                                        timestamp);

                        Flow newFlowWithPaths =
                                buildFlowWithPaths(updatingFlow, newFlowPathPair, FlowStatus.IN_PROGRESS, timestamp);

                        FlowPath currentForwardPath = currentFlow.getForwardPath();
                        FlowPath currentReversePath = currentFlow.getReversePath();
                        FlowPath newForwardPath = newFlowWithPaths.getForwardPath();
                        FlowPath newReversePath = newFlowWithPaths.getReversePath();

                        flowPathRepository.lockInvolvedSwitches(currentForwardPath, currentReversePath,
                                newForwardPath, newReversePath);

                        flowRepository.delete(currentFlow.getFlow());

                        updateIslsForFlowPath(currentForwardPath);
                        updateIslsForFlowPath(currentReversePath);
                        if (currentFlow.getProtectedForwardPath() != null) {
                            updateIslsForFlowPath(currentFlow.getProtectedForwardPath());
                        }
                        if (currentFlow.getProtectedReversePath() != null) {
                            updateIslsForFlowPath(currentFlow.getProtectedReversePath());
                        }

                        flowRepository.createOrUpdate(newFlowWithPaths);

                        updateIslsForFlowPath(newForwardPath);
                        updateIslsForFlowPath(newReversePath);

                        FlowResources protectedResources = null;
                        if (newFlowWithPaths.isAllocateProtectedPath()) {
                            protectedResources = createProtectedPath(newFlowWithPaths, timestamp);
                        }

                        return new UpdatedFlowPathsWithEncapsulation(currentFlow,
                                buildFlowPathsWithEncapsulation(newFlowWithPaths, flowResources, protectedResources));
                    }));
        } catch (FailsafeException e) {
            unwrapCrudFaisafeException(e);
        }

        // Assemble a command batch with InstallXXXRule, RemoveRule commands and a resource deallocation request.

        List<CommandGroup> commandGroups = new ArrayList<>();
        commandGroups.addAll(createInstallRulesGroups(result));
        commandGroups.addAll(createRemoveRulesGroups(result.getOldFlowPair()));
        commandGroups.addAll(createDeallocateResourcesGroups(result.getOldFlowPair()));

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(currentFlow.getFlow().getFlowId(),
                commandGroups,
                createFlowPathStatusRequests(result, FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(result, FlowPathStatus.INACTIVE));

        return result;
    }

    /**
     * Reroutes a flow via a new path. Allocates new path and resources.
     * Stores the flow entities into DB, and invokes flow rules installation and deletion via the command sender.
     * <p/>
     * The rerouted flow has IN_PROGRESS status.
     *
     * @param flowId the flow to be rerouted.
     * @param forceToReroute if true the flow will be recreated even there's no better path found.
     * @param affectedIsl the set of path if to reroute.
     * @param sender the command sender for flow rules installation and deletion.
     */
    public ReroutedFlowPaths rerouteFlow(String flowId, boolean forceToReroute, Set<IslEndpoint> affectedIsl,
                                         FlowCommandSender sender) throws RecoverableException, UnroutableFlowException,
            FlowNotFoundException, ResourceAllocationException {
        // due to completely switch on H&S flow's CRUD implementation goal new features are not backported
        // into old/this implementation, including reroute targeting based on affected ISL set
        if (affectedIsl != null && ! affectedIsl.isEmpty()) {
            log.warn("Pre H&S reroute implementation do not support reroute flow path targeting based on affected ISLs "
                            + "set, provided set of affected ISLs {} are ignored, all path(s) belongs to flows {} will "
                            + "be rerouted",
                    affectedIsl.stream().map(IslEndpoint::toString).collect(Collectors.joining(", ")),
                    flowId);
        }
        Set<PathId> affectedPaths = Collections.emptySet();

        dashboardLogger.onFlowPathReroute(flowId, affectedIsl, forceToReroute);

        RerouteResult result;
        try {
            result = doRerouteWithRetries(flowId, forceToReroute, affectedPaths);
        } catch (UnroutableFlowException e) {
            dashboardLogger.onFailedFlowReroute(flowId, "Path was not found. " + e.getMessage());
            throw e;
        } catch (Exception e) {
            dashboardLogger.onFailedFlowReroute(flowId, e.getMessage());
            throw e;
        }

        log.warn("Reroute finished. Paths to create: {}. Paths to remove: {}",
                result.getToCreateFlow(), result.getToRemoveFlow());

        // Assemble a command batch with InstallXXXRule, RemoveRule commands and a resource deallocation request.
        List<CommandGroup> commandGroups = new ArrayList<>();

        commandGroups.addAll(createInstallRulesGroups(result.getToCreateFlow()));
        commandGroups.addAll(createRemoveRulesGroups(result.getToRemoveFlow()));
        commandGroups.addAll(createDeallocateResourcesGroups(result.getToRemoveFlow()));

        if (!commandGroups.isEmpty()) {
            dashboardLogger.onSuccessfulFlowReroute(flowId);
        }

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(flowId,
                commandGroups,
                createFlowPathStatusRequests(result.getToCreateFlow(), FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(result.getToCreateFlow(), FlowPathStatus.INACTIVE));

        return new ReroutedFlowPaths(result.getInitialFlow(), result.getUpdatedFlow());
    }

    private RerouteResult doRerouteWithRetries(String flowId, boolean forceToReroute, Set<PathId> pathIds)
            throws ResourceAllocationException, FlowNotFoundException, UnroutableFlowException {
        RerouteResult result = null;
        try {
            result = (RerouteResult) getFailsafe().get(() ->
                    transactionManager.doInTransaction(() -> doReroute(flowId, forceToReroute, pathIds)));
        } catch (FailsafeException e) {
            unwrapFaisafeException(e);
        }
        return result;
    }

    private RerouteResult doReroute(String flowId, boolean forceToReroute, Set<PathId> pathIds)
            throws FlowNotFoundException, RecoverableException, UnroutableFlowException, ResourceAllocationException {
        FlowPathsWithEncapsulation currentFlow =
                getFlowPathPairWithEncapsulation(flowId).orElseThrow(() -> new FlowNotFoundException(flowId));

        Flow flow = currentFlow.getFlow();
        Flow initialFlow = flow.toBuilder().build();

        featureTogglesRepository.find().ifPresent(featureToggles ->
                Optional.ofNullable(featureToggles.getFlowsRerouteUsingDefaultEncapType()).ifPresent(toggle -> {
                    if (toggle) {
                        flow.setEncapsulationType(kildaConfigurationRepository.get().getFlowEncapsulationType());
                    }
                }));

        FlowPathsWithEncapsulationBuilder toCreateBuilder = FlowPathsWithEncapsulation.builder();
        FlowPathsWithEncapsulationBuilder toRemoveBuilder = currentFlow.toBuilder().flow(initialFlow);
        Instant timestamp = Instant.now();

        boolean reroutePrimary = pathIds.isEmpty() || pathIds.contains(flow.getForwardPathId())
                || pathIds.contains(flow.getReversePathId());
        boolean rerouteProtected = flow.isAllocateProtectedPath() && (pathIds.isEmpty()
                || pathIds.contains(flow.getProtectedForwardPathId())
                || pathIds.contains(flow.getProtectedReversePathId()));

        // primary path
        if (reroutePrimary) {
            log.warn("Origin flow {} path: {}", flowId, flow.getForwardPath());

            PathComputer pathComputer = pathComputerFactory.getPathComputer();
            PathPair pathPair = pathComputer.getPath(flow, flow.getFlowPathIds());

            log.warn("Potential New Path for flow {} with LEFT path: {}, RIGHT path: {}",
                    flowId, pathPair.getForward(), pathPair.getReverse());

            if (flow.isAllocateProtectedPath()) {
                log.warn("Rerouting primary path for flow with protected path available, with flow id {}", flowId);
            }

            boolean isFoundNewPath = !isSamePath(pathPair.getForward(), currentFlow.getForwardPath())
                    || !isSamePath(pathPair.getReverse(), currentFlow.getReversePath());

            //no need to emit changes if path wasn't changed and flow is active.
            //force means to update flow even if path is not changed.
            if (!isFoundNewPath && flow.isActive() && !forceToReroute) {
                dashboardLogger.onFailedFlowReroute(flowId,
                        format("Reroute %s is unsuccessful: can't find new path(s).", flowId));
                toRemoveBuilder.forwardPath(null).reversePath(null);
            } else {
                FlowResources flowResources = flowResourcesManager.allocateFlowResources(flow);

                // Recreate the flow, use allocated resources for new paths.
                FlowPathPair newFlowPathPair =
                        buildFlowPathPair(flow, pathPair, flowResources, FlowPathStatus.IN_PROGRESS, timestamp);

                final FlowPath currentForwardPath = flow.getForwardPath();
                final FlowPath currentReversePath = flow.getReversePath();
                if (currentForwardPath != null && currentReversePath != null) {
                    deletePaths(currentForwardPath, currentReversePath);
                }

                FlowPath newForwardPath = newFlowPathPair.getForward();
                FlowPath newReversePath = newFlowPathPair.getReverse();
                createPaths(newForwardPath, newReversePath);
                Optional<SwitchProperties> srcSwitchProperties = switchPropertiesRepository.findBySwitchId(
                        flow.getSrcSwitch().getSwitchId());
                boolean srcWithMultiTable = false;
                boolean srcWithLldp = false;
                boolean srcWithArp = false;
                if (srcSwitchProperties.isPresent()) {
                    srcWithMultiTable = srcSwitchProperties.get().isMultiTable();
                    srcWithLldp = srcSwitchProperties.get().isSwitchLldp();
                    srcWithArp = srcSwitchProperties.get().isSwitchArp();
                }

                flow.setSrcWithMultiTable(srcWithMultiTable);
                flow.getDetectConnectedDevices().setSrcSwitchLldp(srcWithLldp);
                flow.getDetectConnectedDevices().setSrcSwitchArp(srcWithArp);

                if (flow.isOneSwitchFlow()) {
                    flow.setDestWithMultiTable(srcWithMultiTable);
                    flow.getDetectConnectedDevices().setDstLldp(srcWithLldp);
                    flow.getDetectConnectedDevices().setDstArp(srcWithArp);
                } else {
                    Optional<SwitchProperties> dstSwitchProperties = switchPropertiesRepository.findBySwitchId(
                            flow.getDestSwitch().getSwitchId());
                    boolean dstWithMultiTable = false;
                    boolean dstWithLldp = false;
                    boolean dstWithArp = false;
                    if (dstSwitchProperties.isPresent()) {
                        dstWithMultiTable = dstSwitchProperties.get().isMultiTable();
                        dstWithLldp = dstSwitchProperties.get().isSwitchLldp();
                        dstWithArp = dstSwitchProperties.get().isSwitchArp();
                    }
                    flow.setDestWithMultiTable(dstWithMultiTable);
                    flow.getDetectConnectedDevices().setDstSwitchLldp(dstWithLldp);
                    flow.getDetectConnectedDevices().setDstSwitchArp(dstWithArp);
                }



                flow.setStatus(FlowStatus.IN_PROGRESS);
                flow.setTimeModify(timestamp);
                flow.setForwardPath(newFlowPathPair.getForward());
                flow.setReversePath(newFlowPathPair.getReverse());
                flowRepository.createOrUpdate(flow);

                toCreateBuilder.forwardPath(newForwardPath)
                        .reversePath(newReversePath)
                        .forwardEncapsulation(flowResources.getForward().getEncapsulationResources())
                        .reverseEncapsulation(flowResources.getReverse().getEncapsulationResources());
            }
        } else {
            toRemoveBuilder.forwardPath(null).reversePath(null);
        }

        // protected path
        if (rerouteProtected) {
            log.warn("Origin flow {} protected path: {}", flowId, flow.getProtectedForwardPath());

            final FlowPath currentForwardPath = flow.getProtectedForwardPath();
            final FlowPath currentReversePath = flow.getProtectedReversePath();

            PathComputer pathComputer = pathComputerFactory.getPathComputer();
            PathPair pathPair = pathComputer.getPath(flow,
                    Arrays.asList(flow.getProtectedForwardPathId(), flow.getProtectedReversePathId()));

            log.warn("Potential New Path for flow {} with LEFT path: {}, RIGHT path: {}",
                    flowId, pathPair.getForward(), pathPair.getReverse());

            boolean isFoundNewPath = !isSamePath(pathPair.getForward(), currentFlow.getProtectedForwardPath())
                    || !isSamePath(pathPair.getReverse(), currentFlow.getProtectedReversePath());

            if (!isFoundNewPath && flow.isActive() && !forceToReroute) {
                dashboardLogger.onFailedFlowReroute(flowId,
                        format("Reroute %s is unsuccessful: can't find new protected path(s).", flowId));
                toRemoveBuilder.protectedForwardPath(null).protectedReversePath(null);
            } else {
                // Create new flow paths, without resources
                FlowPathPair newFlowPathPair =
                        buildFlowPathPair(flow, pathPair, FlowPathStatus.IN_PROGRESS, timestamp);

                flow.setTimeModify(timestamp);

                if (isProtectedPathsDontOverlapsWithPrimary(flow, newFlowPathPair)) {

                    // allocate and setup resources
                    FlowResources flowResources = flowResourcesManager.allocateFlowResources(flow);
                    setResourcesInPaths(newFlowPathPair, flowResources);

                    if (currentForwardPath != null && currentReversePath != null) {
                        deletePaths(currentForwardPath, currentReversePath);
                    }

                    FlowPath newForwardPath = newFlowPathPair.getForward();
                    FlowPath newReversePath = newFlowPathPair.getReverse();
                    createPaths(newForwardPath, newReversePath);

                    flow.setStatus(FlowStatus.IN_PROGRESS);
                    flow.setProtectedForwardPath(newFlowPathPair.getForward());
                    flow.setProtectedReversePath(newFlowPathPair.getReverse());
                    flowRepository.createOrUpdate(flow);

                    toCreateBuilder.protectedForwardPath(newForwardPath)
                            .protectedReversePath(newReversePath)
                            .protectedForwardEncapsulation(flowResources.getForward().getEncapsulationResources())
                            .protectedReverseEncapsulation(flowResources.getReverse().getEncapsulationResources());
                } else {
                    dashboardLogger.onFailedFlowReroute(flowId,
                            format("Reroute %s is unsuccessful: can't find non overlapping new protected path.",
                                    flowId));

                    currentForwardPath.setStatus(FlowPathStatus.INACTIVE);
                    currentReversePath.setStatus(FlowPathStatus.INACTIVE);

                    flowPathRepository.createOrUpdate(currentForwardPath);
                    flowPathRepository.createOrUpdate(currentReversePath);

                    flow.setStatus(flow.computeFlowStatus());
                    flowRepository.createOrUpdate(flow);

                    toRemoveBuilder.protectedForwardPath(null).protectedReversePath(null);
                }
            }
        } else {
            toRemoveBuilder.protectedForwardPath(null).protectedReversePath(null);
        }

        FlowPathsWithEncapsulation updatedFlow =
                getFlowPathPairWithEncapsulation(flowId).orElseThrow(() -> new FlowNotFoundException(flowId));

        return new RerouteResult(currentFlow.toBuilder().flow(initialFlow).build(), updatedFlow,
                toCreateBuilder.flow(flow).build(), toRemoveBuilder.build());
    }

    /**
     * Swaps primary path for the flow with protected paths.
     *
     * @param flowId the flow id to be updated.
     * @param pathId the primary path id to move from.
     * @param sender the command sender for flow rules installation and deletion.
     * @return the updated flow.
     */
    public Flow pathSwap(String flowId, PathId pathId, FlowCommandSender sender)
            throws FlowNotFoundException, FlowValidationException {
        FlowPathsWithEncapsulation result = transactionManager.doInTransaction(() -> {
            FlowPathsWithEncapsulation currentFlow =
                    getFlowPathPairWithEncapsulation(flowId).orElseThrow(() -> new FlowNotFoundException(flowId));

            Flow flow = currentFlow.getFlow();

            dashboardLogger.onFlowPathsSwap(flow);

            if (pathId != null && !(pathId.equals(flow.getForwardPathId()) || pathId.equals(flow.getReversePathId()))) {
                throw new FlowValidationException(format("Requested pathId %s doesn't belongs to primary "
                        + "flow path for flow with id %s", pathId, flowId),
                        ErrorType.PARAMETERS_INVALID);
            }

            if (!flow.isAllocateProtectedPath()) {
                throw new FlowValidationException(format("Flow %s doesn't have protected path", flowId),
                        ErrorType.PARAMETERS_INVALID);
            }
            if (FlowPathStatus.ACTIVE != flow.getProtectedForwardPath().getStatus()
                    || FlowPathStatus.ACTIVE != flow.getProtectedReversePath().getStatus()) {
                throw new FlowValidationException(
                        format("Protected flow path %s is not in ACTIVE state", flowId), ErrorType.PARAMETERS_INVALID);
            }

            log.info("Swapping path with id {} in flow {}", pathId, flow);

            flow.setStatus(FlowStatus.IN_PROGRESS);
            flow.setTimeModify(Instant.now());

            FlowPath oldPrimaryForward = flow.getForwardPath();
            FlowPath oldPrimaryReverse = flow.getReversePath();
            flow.setForwardPath(flow.getProtectedForwardPath());
            flow.setReversePath(flow.getProtectedReversePath());
            flow.setProtectedForwardPath(oldPrimaryForward);
            flow.setProtectedReversePath(oldPrimaryReverse);

            flowRepository.createOrUpdate(flow);

            return currentFlow;
        });

        // Assemble a command batch with InstallXXXRule, RemoveRule commands and a resource deallocation request.

        List<CommandGroup> commandGroups = createSwapIngressCommand(result);

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(result.getFlow().getFlowId(),
                commandGroups,
                createFlowPathStatusRequests(result, FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(result, FlowPathStatus.INACTIVE));

        return result.getFlow();
    }

    /**
     * Deallocates resources of the flow path.
     *
     * @param pathId             the flow path to be used to identify resources.
     * @param unmaskedCookie     the flow cookie to be released.
     * @param encapsulationType  determine the encapsulation type used for resource allocation.
     */
    public void deallocateResources(PathId pathId, long unmaskedCookie, FlowEncapsulationType encapsulationType) {
        flowResourcesManager.deallocatePathResources(pathId, unmaskedCookie, encapsulationType);
    }

    /**
     * Updates the status of a flow(s).
     *
     * @param flowId a flow ID used to locate the flow(s).
     * @param status the status to set.
     */
    public void updateFlowStatus(String flowId, FlowStatus status, Set<PathId> pathIdSet) {
        dashboardLogger.onFlowStatusUpdate(flowId, status);

        transactionManager.doInTransaction(() -> {
            flowRepository.updateStatus(flowId, status);

            Stream<FlowPath> pathsStream = flowPathRepository.findByFlowId(flowId).stream();
            if (!pathIdSet.isEmpty()) {
                pathsStream = pathsStream.filter(path -> pathIdSet.contains(path.getPathId()));
            }
            pathsStream.forEach(path -> {
                path.setStatusLikeFlow(status);
                flowPathRepository.updateStatus(path.getPathId(), path.getStatus());
            });
        });
    }

    /**
     * Updates the flow path status.
     * <p/>
     * It also affects the flow status if both (forward and reverse) paths are active.
     *
     * @param flowId the flow to be updated.
     * @param pathId the flow path to be updated.
     * @param flowPathStatus the status to be set.
     */

    public void updateFlowPathStatus(String flowId, PathId pathId, FlowPathStatus flowPathStatus) {
        transactionManager.doInTransaction(() -> {
            FlowPath flowPath = flowPathRepository.findById(pathId, FetchStrategy.NO_RELATIONS)
                    .orElseThrow(() -> new FlowNotFoundException(flowId, format("Flow path %s not found.", pathId)));

            if (flowPathStatus != flowPath.getStatus()) {
                flowPath.setStatus(flowPathStatus);
                flowPathRepository.updateStatus(flowPath.getPathId(), flowPathStatus);
            }
        });

        transactionManager.doInTransaction(() -> {
            Flow flow = flowRepository.findById(flowId).orElseThrow(() -> new FlowNotFoundException(flowId));

            FlowStatus flowStatus = flow.computeFlowStatus();
            if (flowStatus != flow.getStatus()) {
                dashboardLogger.onFlowStatusUpdate(flowId, flowStatus);
                flow.setStatus(flowStatus);
                flowRepository.updateStatus(flow.getFlowId(), flowStatus);
            }
        });
    }

    /**
     * Returns list of flows id in diverse group.
     *
     * @param flowId the flow to get diverse group.
     * @param groupId the group of flows with which the target flow is diverse.
     * @return list of flows id.
     */
    public List<String> getDiverseFlowsId(String flowId, String groupId) {
        if (groupId == null) {
            return null;
        }

        return flowRepository.findFlowsIdByGroupId(groupId).stream()
                .filter(id -> !id.equals(flowId))
                .collect(Collectors.toList());
    }

    /**
     * Loads a flow by specified flow id.
     */
    public Optional<FlowData> getFlowById(String flowId) {
        // NB: workaround for an issue with OGM/neo4j, when ClientException 'Unable to load NODE with id' is thrown
        return (Optional<FlowData>) getReadOperationFailsafe().get(() ->
                transactionManager.doInTransaction(() -> getFlow(flowId))
        );
    }

    /**
     * Loads all available flows.
     */
    public List<FlowData> getAllFlows() {
        // NB: workaround for an issue with OGM/neo4j, when ClientException 'Unable to load NODE with id' is thrown
        return (List<FlowData>) getReadOperationFailsafe().get(() ->
                transactionManager.doInTransaction(() -> getFlows())
        );
    }

    private FlowPathPair buildFlowPathPair(Flow flow, FlowResources flowResources, Instant timeCreate) {
        FlowPathStatus pathStatus = flow.getStatus() == FlowStatus.IN_PROGRESS
                ? FlowPathStatus.IN_PROGRESS : FlowPathStatus.ACTIVE;

        FlowPath forwardPath = flow.getForwardPath().toBuilder()
                .srcSwitch(switchRepository.reload(flow.getForwardPath().getSrcSwitch()))
                .destSwitch(switchRepository.reload(flow.getForwardPath().getDestSwitch()))
                .status(pathStatus)
                .timeCreate(timeCreate)
                .build();
        FlowPath reversePath = flow.getReversePath().toBuilder()
                .srcSwitch(switchRepository.reload(flow.getReversePath().getSrcSwitch()))
                .destSwitch(switchRepository.reload(flow.getReversePath().getDestSwitch()))
                .status(pathStatus)
                .timeCreate(timeCreate)
                .build();

        FlowPathPair pathPair = FlowPathPair.builder()
                .forward(forwardPath)
                .reverse(reversePath)
                .build();
        setResourcesInPaths(pathPair, flowResources);
        return pathPair;
    }

    private FlowPathPair buildFlowPathPair(Flow flow, PathPair pathPair, FlowResources flowResources,
                                           FlowPathStatus pathStatus, Instant timeCreate) {
        FlowPathPair flowPathPair = buildFlowPathPair(flow, pathPair, pathStatus, timeCreate);
        setResourcesInPaths(flowPathPair, flowResources);
        return flowPathPair;
    }

    private FlowPathPair buildFlowPathPair(Flow flow, PathPair pathPair, FlowPathStatus pathStatus,
                                           Instant timeCreate) {
        FlowPath forwardPath = buildFlowPath(flow, pathPair.getForward(), pathStatus, timeCreate);
        FlowPath reversePath = buildFlowPath(flow, pathPair.getReverse(), pathStatus, timeCreate);

        return FlowPathPair.builder()
                .forward(forwardPath)
                .reverse(reversePath)
                .build();
    }

    private void setResourcesInPaths(FlowPathPair pathPair, FlowResources flowResources) {
        final Cookie pathCookie = FlowSegmentCookieSchema.INSTANCE.make(flowResources.getUnmaskedCookie());

        FlowPath forwardPath = pathPair.getForward();
        forwardPath.setPathId(flowResources.getForward().getPathId());
        forwardPath.setCookie(FlowSegmentCookieSchema.INSTANCE.setDirection(pathCookie, FlowPathDirection.FORWARD));
        if (flowResources.getForward().getMeterId() != null) {
            forwardPath.setMeterId(flowResources.getForward().getMeterId());
        }

        FlowPath reversePath = pathPair.getReverse();
        reversePath.setPathId(flowResources.getReverse().getPathId());
        reversePath.setCookie(FlowSegmentCookieSchema.INSTANCE.setDirection(pathCookie, FlowPathDirection.REVERSE));
        if (flowResources.getReverse().getMeterId() != null) {
            reversePath.setMeterId(flowResources.getReverse().getMeterId());
        }
    }

    private FlowPath buildFlowPath(Flow flow, Path path, FlowPathStatus pathStatus, Instant timeCreate) {
        PathId pathId = new PathId(UUID.randomUUID().toString());

        FlowPath flowPath = FlowPath.builder()
                .flow(flow)
                .pathId(pathId)
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth())
                .srcSwitch(switchRepository.reload(Switch.builder()
                        .switchId(path.getSrcSwitchId()).build()))
                .destSwitch(switchRepository.reload(Switch.builder()
                        .switchId(path.getDestSwitchId()).build()))
                .status(pathStatus)
                .timeCreate(timeCreate)
                .build();

        List<PathSegment> segments = path.getSegments().stream()
                .map(segment -> buildPathSegment(segment))
                .collect(Collectors.toList());
        flowPath.setSegments(segments);

        return flowPath;
    }

    private PathSegment buildPathSegment(Segment segment) {
        Optional<SwitchProperties> srcSwitchProperties = switchPropertiesRepository.findBySwitchId(
                segment.getSrcSwitchId());
        boolean srcWithMultiTable = false;
        if (srcSwitchProperties.isPresent()) {
            srcWithMultiTable = srcSwitchProperties.get().isMultiTable();
        }
        Optional<SwitchProperties> destSwitchProperties = switchPropertiesRepository.findBySwitchId(
                segment.getDestSwitchId());
        boolean destWithMultiTable = false;
        if (destSwitchProperties.isPresent()) {
            destWithMultiTable = destSwitchProperties.get().isMultiTable();
        }
        return PathSegment.builder()
                .srcSwitch(switchRepository.reload(Switch.builder()
                        .switchId(segment.getSrcSwitchId()).build()))
                .srcPort(segment.getSrcPort())
                .srcWithMultiTable(srcWithMultiTable)
                .destSwitch(switchRepository.reload(Switch.builder()
                        .switchId(segment.getDestSwitchId()).build()))
                .destPort(segment.getDestPort())
                .destWithMultiTable(destWithMultiTable)
                .latency(segment.getLatency())
                .build();

    }

    private Flow buildFlowWithPaths(Flow flow, FlowPathPair flowPathPair, FlowStatus status, Instant timeModify) {
        Flow copied = flow.toBuilder()
                .srcSwitch(switchRepository.reload(flow.getSrcSwitch()))
                .destSwitch(switchRepository.reload(flow.getDestSwitch()))
                .encapsulationType(flow.getEncapsulationType())
                .pathComputationStrategy(flow.getPathComputationStrategy())
                .timeModify(timeModify)
                .status(status)
                .build();

        FlowPath forward = flowPathPair.getForward();
        forward.setFlow(copied);
        copied.setForwardPath(forward);

        FlowPath reverse = flowPathPair.getReverse();
        reverse.setFlow(copied);
        copied.setReversePath(reverse);
        return copied;
    }

    private boolean isSamePath(@NonNull Path path, FlowPath flowPath) {
        if (flowPath == null) {
            return false;
        }

        if (!path.getSrcSwitchId().equals(flowPath.getSrcSwitch().getSwitchId())
                || !path.getDestSwitchId().equals(flowPath.getDestSwitch().getSwitchId())
                || path.getSegments().size() != flowPath.getSegments().size()) {
            return false;
        }

        Iterator<Path.Segment> pathIt = path.getSegments().iterator();
        Iterator<PathSegment> flowPathIt = flowPath.getSegments().iterator();
        while (pathIt.hasNext() && flowPathIt.hasNext()) {
            Path.Segment pathSegment = pathIt.next();
            PathSegment flowSegment = flowPathIt.next();
            if (!pathSegment.getSrcSwitchId().equals(flowSegment.getSrcSwitch().getSwitchId())
                    || !pathSegment.getDestSwitchId().equals(flowSegment.getDestSwitch().getSwitchId())
                    || pathSegment.getSrcPort() != flowSegment.getSrcPort()
                    || pathSegment.getDestPort() != flowSegment.getDestPort()) {
                return false;
            }
        }

        return true;
    }

    private void updateIslsForFlowPath(FlowPath path) {
        path.getSegments().forEach(pathSegment -> {
            log.debug("Updating ISL for the path segment: {}", pathSegment);

            updateIslAvailableBandwidth(pathSegment.getSrcSwitch().getSwitchId(), pathSegment.getSrcPort(),
                    pathSegment.getDestSwitch().getSwitchId(), pathSegment.getDestPort());
        });
    }

    private void updateIslAvailableBandwidth(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(
                srcSwitchId, srcPort, dstSwitchId, dstPort);

        islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort)
                .ifPresent(isl -> {
                    isl.setAvailableBandwidth(isl.getMaxBandwidth() - usedBandwidth);

                    islRepository.createOrUpdate(isl);
                });
    }

    private String getOrCreateFlowGroupId(String flowId) throws FlowNotFoundException {
        log.info("Getting flow group for flow with id {}", flowId);
        return flowRepository.getOrCreateFlowGroupId(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId));
    }

    private void checkDiverseFlow(Flow targetFlow, String flowId) throws FlowNotFoundException,
            FlowValidationException {
        if (targetFlow.isOneSwitchFlow()) {
            throw new FlowValidationException("Couldn't add one-switch flow into diverse group",
                    ErrorType.PARAMETERS_INVALID);
        }

        Flow diverseFlow = flowRepository.findById(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId));

        if (diverseFlow.isOneSwitchFlow()) {
            throw new FlowValidationException("Couldn't create diverse group with one-switch flow",
                    ErrorType.PARAMETERS_INVALID);
        }
    }

    private void checkProtectedPathsDontOverlapsWithPrimary(Flow flow, FlowPathPair newProtectedPathPair)
            throws UnroutableFlowException {
        if (!isProtectedPathsDontOverlapsWithPrimary(flow, newProtectedPathPair)) {
            log.warn("Couldn't find non overlapping protected path. Result flow state: {}", flow);
            throw new UnroutableFlowException("Couldn't find non overlapping protected path",
                    flow.getFlowId());
        }
    }

    private boolean isProtectedPathsDontOverlapsWithPrimary(Flow flow, FlowPathPair newProtectedPathPair) {
        List<PathSegment> segments = union(newProtectedPathPair.getForward().getSegments(),
                newProtectedPathPair.getReverse().getSegments());

        List<PathSegment> primaryFlowSegments = Stream.of(flow.getForwardPath(), flow.getReversePath())
                .map(FlowPath::getSegments)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        return !IntersectionComputer.isProtectedPathOverlaps(primaryFlowSegments, segments);
    }

    private FlowPathsWithEncapsulation buildFlowPathsWithEncapsulation(Flow flow, FlowResources primaryResources,
                                                                       FlowResources protectedResources) {
        FlowPathsWithEncapsulationBuilder builder =
                FlowPathsWithEncapsulation.builder()
                        .flow(flow)
                        .forwardPath(flow.getForwardPath())
                        .reversePath(flow.getReversePath())
                        .forwardEncapsulation(primaryResources.getForward().getEncapsulationResources())
                        .reverseEncapsulation(primaryResources.getReverse().getEncapsulationResources())
                        .protectedForwardPath(flow.getProtectedForwardPath())
                        .protectedReversePath(flow.getProtectedReversePath());

        if (protectedResources != null) {
            builder.protectedForwardEncapsulation(protectedResources.getForward().getEncapsulationResources())
                    .protectedReverseEncapsulation(protectedResources.getReverse().getEncapsulationResources());
        }
        return builder.build();
    }

    private List<CommandGroup> createSwapIngressCommand(FlowPathsWithEncapsulation pathsToSwap) {
        Preconditions.checkArgument(pathsToSwap.getFlow().isAllocateProtectedPath());

        List<CommandGroup> commandGroups = new ArrayList<>();

        // new primary path
        commandGroups.add(createInstallIngressRules(pathsToSwap.getProtectedForwardPath(),
                pathsToSwap.getProtectedForwardEncapsulation()));
        commandGroups.add(createInstallIngressRules(pathsToSwap.getProtectedReversePath(),
                pathsToSwap.getProtectedReverseEncapsulation()));

        if (pathsToSwap.getForwardPath().getMeterId() != null) {
            commandGroups.add(createRemoveMeter(pathsToSwap.getForwardPath()));
        }
        if (pathsToSwap.getReversePath().getMeterId() != null) {
            commandGroups.add(createRemoveMeter(pathsToSwap.getReversePath()));
        }

        return commandGroups;
    }

    private List<CommandGroup> createInstallRulesGroups(FlowPathsWithEncapsulation pathsToInstall) {
        List<CommandGroup> commandGroups = new ArrayList<>();

        EncapsulationResources forwardEncapsulationResources = pathsToInstall.getForwardEncapsulation();
        EncapsulationResources reverseEncapsulationResources = pathsToInstall.getReverseEncapsulation();

        if (pathsToInstall.getForwardPath() != null) {
            createInstallLldpTransitAndEgressRules(pathsToInstall.getForwardPath(),
                    forwardEncapsulationResources).ifPresent(commandGroups::add);
        }
        if (pathsToInstall.getReversePath() != null) {
            createInstallLldpTransitAndEgressRules(pathsToInstall.getReversePath(),
                    reverseEncapsulationResources).ifPresent(commandGroups::add);
        }

        if (pathsToInstall.getProtectedForwardPath() != null) {
            createInstallLldpTransitAndEgressRules(pathsToInstall.getProtectedForwardPath(),
                    pathsToInstall.getProtectedForwardEncapsulation()).ifPresent(commandGroups::add);
        }
        if (pathsToInstall.getProtectedReversePath() != null) {
            createInstallLldpTransitAndEgressRules(pathsToInstall.getProtectedReversePath(),
                    pathsToInstall.getProtectedReverseEncapsulation()).ifPresent(commandGroups::add);
        }

        // The ingress rule must be installed after the egress and transit ones.
        if (pathsToInstall.getForwardPath() != null) {
            commandGroups.add(createInstallIngressRules(pathsToInstall.getForwardPath(),
                    forwardEncapsulationResources));
        }
        if (pathsToInstall.getReversePath() != null) {
            commandGroups.add(createInstallIngressRules(pathsToInstall.getReversePath(),
                    reverseEncapsulationResources));
        }

        return commandGroups;
    }

    private Optional<CommandGroup> createInstallLldpTransitAndEgressRules(
            FlowPath flowPath, EncapsulationResources encapsulationResources) {
        List<BaseInstallFlow> rules = flowCommandFactory.createInstallLldpTransitAndEgressRulesForFlow(flowPath,
                encapsulationResources);
        return !rules.isEmpty() ? Optional.of(new CommandGroup(rules, FailureReaction.ABORT_BATCH))
                : Optional.empty();
    }

    private CommandGroup createInstallIngressRules(FlowPath flowPath, EncapsulationResources encapsulationResources) {
        return new CommandGroup(singletonList(
                flowCommandFactory.createInstallIngressRulesForFlow(flowPath, encapsulationResources)),
                FailureReaction.ABORT_BATCH);
    }

    private CommandGroup createRemoveMeter(FlowPath flowPath) {
        return new CommandGroup(singletonList(flowCommandFactory.createDeleteMeter(flowPath)),
                FailureReaction.ABORT_BATCH);
    }

    private List<CommandGroup> createRemoveRulesGroups(FlowPathsWithEncapsulation pathsToRemove) {
        List<CommandGroup> commandGroups = new ArrayList<>();

        if (pathsToRemove.getForwardPath() != null) {
            // NOTE: replacing flow object in flow path to deal with old data here
            pathsToRemove.getForwardPath().setFlow(pathsToRemove.getFlow());
            commandGroups.addAll(createRemoveRulesGroups(pathsToRemove.getForward(), true));
        }
        if (pathsToRemove.getReversePath() != null) {
            // NOTE: replacing flow object in flow path to deal with old data here
            pathsToRemove.getReversePath().setFlow(pathsToRemove.getFlow());
            commandGroups.addAll(createRemoveRulesGroups(pathsToRemove.getReverse(), true));
        }

        if (pathsToRemove.getProtectedForwardPath() != null) {
            commandGroups.addAll(createRemoveRulesGroups(pathsToRemove.getProtectedForward(), false));
        }
        if (pathsToRemove.getProtectedReversePath() != null) {
            commandGroups.addAll(createRemoveRulesGroups(pathsToRemove.getProtectedReverse(), false));
        }

        return commandGroups;
    }

    private List<CommandGroup> createRemoveRulesGroups(FlowPathWithEncapsulation pathToRemove, boolean isPrimary) {
        List<CommandGroup> commandGroups = new ArrayList<>();
        FlowPath flowPath = pathToRemove.getFlowPath();

        EncapsulationResources encapsulationResources = pathToRemove.getEncapsulation();

        if (isPrimary) {
            commandGroups.add(createRemoveIngressRules(flowPath));
        }
        createRemoveLldpTransitAndEgressRules(flowPath, encapsulationResources)
                .ifPresent(commandGroups::add);

        return commandGroups;
    }

    private CommandGroup createRemoveIngressRules(FlowPath flowPath) {
        SwitchId ingressSwitchId = flowPath.getSrcSwitch().getSwitchId();
        int ingressPort;
        if (flowPath.isForward()) {
            ingressPort = flowPath.getFlow().getSrcPort();
        } else {
            ingressPort = flowPath.getFlow().getDestPort();
        }

        Collection<Flow> flowWithMultiTable = flowRepository.findByEndpointWithMultiTableSupport(
                ingressSwitchId, ingressPort);
        boolean cleanUpIngress = flowWithMultiTable.isEmpty();
        boolean cleanUpIngressLldp = flowWithMultiTable.stream()
                .noneMatch(flow -> isLldpEnabledOnFlowEndpoint(flow, ingressSwitchId, ingressPort));
        boolean cleanUpIngressArp = flowWithMultiTable.stream()
                .noneMatch(flow -> isArpEnabledOnFlowEndpoint(flow, ingressSwitchId, ingressPort));
        return new CommandGroup(singletonList(
                flowCommandFactory.createRemoveIngressRulesForFlow(flowPath, cleanUpIngress, cleanUpIngressLldp,
                        cleanUpIngressArp)),
                FailureReaction.IGNORE);
    }

    private boolean isLldpEnabledOnFlowEndpoint(Flow flow, SwitchId switchId, int port) {
        DetectConnectedDevices detect = flow.getDetectConnectedDevices();
        return (flow.getSrcSwitch().getSwitchId().equals(switchId)
                && flow.getSrcPort() == port
                && (detect.isSrcLldp() || detect.isSrcSwitchLldp()))
                || (flow.getDestSwitch().getSwitchId().equals(switchId)
                && flow.getDestPort() == port
                && (detect.isDstLldp() || detect.isDstSwitchLldp()));
    }

    private boolean isArpEnabledOnFlowEndpoint(Flow flow, SwitchId switchId, int port) {
        DetectConnectedDevices detect = flow.getDetectConnectedDevices();
        return (flow.getSrcSwitch().getSwitchId().equals(switchId)
                && flow.getSrcPort() == port
                && (detect.isSrcArp() || detect.isSrcSwitchArp()))
                || (flow.getDestSwitch().getSwitchId().equals(switchId)
                && flow.getDestPort() == port
                && (detect.isDstArp() || detect.isDstSwitchArp()));
    }

    private Optional<CommandGroup> createRemoveLldpTransitAndEgressRules(
            FlowPath flowPath, EncapsulationResources encapsulationResources) {
        List<RemoveFlow> rules =
                flowCommandFactory.createRemoveLldpTransitAndEgressRulesForFlow(flowPath, encapsulationResources);
        return !rules.isEmpty() ? Optional.of(new CommandGroup(rules, FailureReaction.IGNORE))
                : Optional.empty();
    }

    private List<CommandGroup> createDeallocateResourcesGroups(FlowPathsWithEncapsulation pathsToDeallocate) {
        List<FlowPathWithEncapsulation> flowPaths = new ArrayList<>();

        if (pathsToDeallocate.getForwardPath() != null) {
            flowPaths.add(pathsToDeallocate.getForward());
        }
        if (pathsToDeallocate.getReversePath() != null) {
            flowPaths.add(pathsToDeallocate.getReverse());
        }

        if (pathsToDeallocate.getProtectedForwardPath() != null) {
            flowPaths.add(pathsToDeallocate.getProtectedForward());
        }
        if (pathsToDeallocate.getProtectedReversePath() != null) {
            flowPaths.add(pathsToDeallocate.getProtectedReverse());
        }

        return createDeallocateResourcesGroups(pathsToDeallocate.getFlow().getFlowId(), flowPaths);
    }

    private List<CommandGroup> createDeallocateResourcesGroups(String flowId,
                                                               List<FlowPathWithEncapsulation> flowPaths) {
        List<CommandData> deallocationCommands = flowPaths.stream()
                .map(flowPath ->
                        new DeallocateFlowResourcesRequest(flowId,
                                FlowSegmentCookieSchema.INSTANCE.getFlowEffectiveId(flowPath.getFlowPath().getCookie()),
                                flowPath.getFlowPath().getPathId(),
                                flowPath.getFlowPath().getFlow().getEncapsulationType()))
                .collect(Collectors.toList());
        return singletonList(new CommandGroup(deallocationCommands, FailureReaction.IGNORE));
    }

    private List<UpdateFlowPathStatusRequest> createFlowPathStatusRequests(FlowPathsWithEncapsulation flowPaths,
                                                                           FlowPathStatus status) {
        String flowId = flowPaths.getFlow().getFlowId();
        List<UpdateFlowPathStatusRequest> commands = new ArrayList<>();

        if (flowPaths.getForwardPath() != null) {
            commands.add(new UpdateFlowPathStatusRequest(flowId, flowPaths.getForwardPath().getPathId(), status));
        }
        if (flowPaths.getReversePath() != null) {
            commands.add(new UpdateFlowPathStatusRequest(flowId, flowPaths.getReversePath().getPathId(), status));
        }

        if (flowPaths.getProtectedForwardPath() != null) {
            commands.add(new UpdateFlowPathStatusRequest(
                    flowId, flowPaths.getProtectedForwardPath().getPathId(), status));
        }
        if (flowPaths.getProtectedReversePath() != null) {
            commands.add(new UpdateFlowPathStatusRequest(
                    flowId, flowPaths.getProtectedReversePath().getPathId(), status));
        }

        return commands;
    }

    private SyncFailsafe getReadOperationFailsafe() {
        return Failsafe.with(new RetryPolicy()
                .retryOn(ClientException.class)
                .withDelay(RETRY_DELAY, TimeUnit.MILLISECONDS)
                .withMaxRetries(MAX_TRANSACTION_RETRY_COUNT))
                .onRetry(e -> log.warn("Retrying transaction finished with exception", e))
                .onRetriesExceeded(e -> log.warn("TX retry attempts exceed with error", e));
    }

    private SyncFailsafe getFailsafe() {
        return Failsafe.with(new RetryPolicy()
                .retryOn(RecoverableException.class)
                .retryOn(ResourceAllocationException.class)
                .retryOn(TransientException.class)
                .withDelay(RETRY_DELAY, TimeUnit.MILLISECONDS)
                .withMaxRetries(MAX_TRANSACTION_RETRY_COUNT))
                .onRetry(e -> log.warn("Retrying transaction finished with exception", e))
                .onRetriesExceeded(e -> log.warn("TX retry attempts exceed with error", e));
    }

    private void unwrapFaisafeException(FailsafeException e) throws UnroutableFlowException,
            ResourceAllocationException, FlowNotFoundException {
        unwrapBaseFaisafeException(e);
        throw e;
    }

    private void unwrapCrudFaisafeException(FailsafeException e) throws UnroutableFlowException,
            ResourceAllocationException, FlowNotFoundException, FlowValidationException {
        unwrapBaseFaisafeException(e);

        Throwable cause = e.getCause();
        if (cause instanceof FlowValidationException) {
            throw (FlowValidationException) cause;
        }
        throw e;
    }

    private void unwrapBaseFaisafeException(FailsafeException e) throws UnroutableFlowException,
            ResourceAllocationException, FlowNotFoundException {
        Throwable cause = e.getCause();
        if (cause instanceof UnroutableFlowException) {
            throw (UnroutableFlowException) cause;
        }
        if (cause instanceof ResourceAllocationException) {
            throw (ResourceAllocationException) cause;
        }
        if (cause instanceof FlowNotFoundException) {
            throw (FlowNotFoundException) cause;
        }
    }

    /**
     * Swap a two flows endpoints.
     *
     * @param firstFlow a first flow.
     * @param secondFlow a second flow.
     * @param sender a command sender for flow rules installation and deletion.
     * @return the flows with swapped endpoints.
     */
    public List<Flow> swapFlowEndpoints(Flow firstFlow, Flow secondFlow, FlowCommandSender sender)
            throws FlowNotFoundException, FlowValidationException, ResourceAllocationException,
            UnroutableFlowException {
        dashboardLogger.onFlowEndpointSwap(firstFlow, secondFlow);

        String firstFlowId = firstFlow.getFlowId();
        String secondFlowId = secondFlow.getFlowId();

        FlowPathsWithEncapsulation currentFirstFlow =
                getFlowPathPairWithEncapsulation(firstFlowId).orElseThrow(() ->
                        new FlowNotFoundException(firstFlowId));
        FlowPathsWithEncapsulation currentSecondFlow =
                getFlowPathPairWithEncapsulation(secondFlowId).orElseThrow(() ->
                        new FlowNotFoundException(secondFlowId));

        Flow existingFirstFlow = currentFirstFlow.getFlow();
        Flow existingSecondFlow = currentSecondFlow.getFlow();

        flowValidator.validateFowSwap(firstFlow, secondFlow);

        existingFirstFlow = existingFirstFlow.toBuilder()
                .srcSwitch(Switch.builder().switchId(firstFlow.getSrcSwitch().getSwitchId()).build())
                .srcPort(firstFlow.getSrcPort())
                .srcVlan(firstFlow.getSrcVlan())
                .destSwitch(Switch.builder().switchId(firstFlow.getDestSwitch().getSwitchId()).build())
                .destPort(firstFlow.getDestPort())
                .destVlan(firstFlow.getDestVlan())
                .encapsulationType(existingFirstFlow.getEncapsulationType())
                .build();

        existingSecondFlow = existingSecondFlow.toBuilder()
                .srcSwitch(Switch.builder().switchId(secondFlow.getSrcSwitch().getSwitchId()).build())
                .srcPort(secondFlow.getSrcPort())
                .srcVlan(secondFlow.getSrcVlan())
                .destSwitch(Switch.builder().switchId(secondFlow.getDestSwitch().getSwitchId()).build())
                .destPort(secondFlow.getDestPort())
                .destVlan(secondFlow.getDestVlan())
                .encapsulationType(existingSecondFlow.getEncapsulationType())
                .build();

        return swapFlows(currentFirstFlow, existingFirstFlow, currentSecondFlow, existingSecondFlow, sender);
    }

    /**
     * Fetch flow and provide update on it's periodic pings.
     * @param flowId flow id
     * @param sender interface
     */
    public void notifyOnPeriodicPingChanges(String flowId,
                                            FlowCommandSender sender) {
        Optional<Flow> flow = flowRepository.findById(flowId);
        if (flow.isPresent()) {
            sender.sendPeriodicPingNotification(flowId, flow.get().isPeriodicPings());
        } else {
            log.warn("unable to send periodic ping update since the flow {} is not found", flowId);
        }
    }

    private UpdatedFlowPathsWithEncapsulation processUpdateFlow(FlowPathsWithEncapsulation currentFlow,
                                                                Flow updatingFlow, List<PathId> pathIds)
            throws ResourceAllocationException, RecoverableException, FlowValidationException, UnroutableFlowException,
            FlowNotFoundException {
        PathComputer pathComputer = pathComputerFactory.getPathComputer();
        PathPair newPathPair = pathComputer.getPath(updatingFlow, pathIds);

        log.info("Updating the flow with {} and path: {}", updatingFlow, newPathPair);

        FlowResources flowResources = flowResourcesManager.allocateFlowResources(updatingFlow);

        // Recreate the flow, use allocated resources for new paths.
        Instant timestamp = Instant.now();
        FlowPathPair newFlowPathPair = buildFlowPathPair(updatingFlow, newPathPair, flowResources,
                FlowPathStatus.IN_PROGRESS, timestamp);

        Flow newFlowWithPaths = buildFlowWithPaths(updatingFlow, newFlowPathPair, FlowStatus.IN_PROGRESS, timestamp);

        FlowPath currentForwardPath = currentFlow.getForwardPath();
        FlowPath currentReversePath = currentFlow.getReversePath();
        FlowPath newForwardPath = newFlowWithPaths.getForwardPath();
        FlowPath newReversePath = newFlowWithPaths.getReversePath();

        flowPathRepository.lockInvolvedSwitches(currentForwardPath, currentReversePath, newForwardPath, newReversePath);

        flowRepository.delete(currentFlow.getFlow());

        updateIslsForFlowPath(currentForwardPath);
        updateIslsForFlowPath(currentReversePath);
        if (currentFlow.getProtectedForwardPath() != null) {
            updateIslsForFlowPath(currentFlow.getProtectedForwardPath());
        }
        if (currentFlow.getProtectedReversePath() != null) {
            updateIslsForFlowPath(currentFlow.getProtectedReversePath());
        }

        flowRepository.createOrUpdate(newFlowWithPaths);

        updateIslsForFlowPath(newForwardPath);
        updateIslsForFlowPath(newReversePath);

        FlowResources protectedResources = null;
        if (newFlowWithPaths.isAllocateProtectedPath()) {
            protectedResources = createProtectedPath(newFlowWithPaths, timestamp);
        }

        return new UpdatedFlowPathsWithEncapsulation(currentFlow,
                buildFlowPathsWithEncapsulation(newFlowWithPaths, flowResources, protectedResources));
    }

    private List<Flow> swapFlows(FlowPathsWithEncapsulation currentFirstFlow,
                                     Flow updatingFirstFlow,
                                     FlowPathsWithEncapsulation currentSecondFlow,
                                     Flow updatingSecondFlow, FlowCommandSender sender)
            throws ResourceAllocationException, FlowValidationException, UnroutableFlowException,
            FlowNotFoundException {
        List<PathId> flowsPath = new ArrayList<>(currentFirstFlow.getFlow().getFlowPathIds());
        flowsPath.addAll(currentSecondFlow.getFlow().getFlowPathIds());
        List<UpdatedFlowPathsWithEncapsulation> flows = null;
        try {
            flows = (List<UpdatedFlowPathsWithEncapsulation>) getFailsafe().get(
                    () -> transactionManager.doInTransaction(() -> {
                        UpdatedFlowPathsWithEncapsulation firstUpdatedFlow =
                                processUpdateFlow(currentFirstFlow, updatingFirstFlow, flowsPath);
                        UpdatedFlowPathsWithEncapsulation secondUpdatedFlow =
                                processUpdateFlow(currentSecondFlow, updatingSecondFlow, flowsPath);
                        return Arrays.asList(firstUpdatedFlow, secondUpdatedFlow);
                    }));
        } catch (FailsafeException e) {
            unwrapCrudFaisafeException(e);
        }


        List<CommandGroup> firstCommandGroup = new ArrayList<>();
        firstCommandGroup.addAll(createInstallRulesGroups(flows.get(0)));
        firstCommandGroup.addAll(createRemoveRulesGroups(flows.get(0).getOldFlowPair()));
        firstCommandGroup.addAll(createDeallocateResourcesGroups(flows.get(0).getOldFlowPair()));

        // To avoid race condition in DB updates, we should send commands only after DB transaction commit.
        sender.sendFlowCommands(currentFirstFlow.getFlow().getFlowId(),
                firstCommandGroup,
                createFlowPathStatusRequests(flows.get(0), FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(flows.get(0), FlowPathStatus.INACTIVE));

        List<CommandGroup> secondCommandGroup = new ArrayList<>();
        secondCommandGroup.addAll(createInstallRulesGroups(flows.get(1)));
        secondCommandGroup.addAll(createRemoveRulesGroups(flows.get(1).getOldFlowPair()));
        secondCommandGroup.addAll(createDeallocateResourcesGroups(flows.get(1).getOldFlowPair()));

        sender.sendFlowCommands(currentSecondFlow.getFlow().getFlowId(),
                secondCommandGroup,
                createFlowPathStatusRequests(flows.get(1), FlowPathStatus.ACTIVE),
                createFlowPathStatusRequests(flows.get(1), FlowPathStatus.INACTIVE));
        return flows.stream().map(FlowPathsWithEncapsulation::getFlow).collect(Collectors.toList());
    }

    private void createPaths(FlowPath forward, FlowPath reverse) {
        flowPathRepository.lockInvolvedSwitches(forward, reverse);
        flowPathRepository.createOrUpdate(forward);
        flowPathRepository.createOrUpdate(reverse);
        updateIslsForFlowPath(forward);
        updateIslsForFlowPath(reverse);
    }

    private void deletePaths(FlowPath forward, FlowPath reverse) {
        flowPathRepository.lockInvolvedSwitches(forward, reverse);
        flowPathRepository.delete(forward);
        flowPathRepository.delete(reverse);
        updateIslsForFlowPath(forward);
        updateIslsForFlowPath(reverse);
    }

    @Value
    private class RerouteResult {
        FlowPathsWithEncapsulation initialFlow;
        FlowPathsWithEncapsulation updatedFlow;
        FlowPathsWithEncapsulation toCreateFlow;
        FlowPathsWithEncapsulation toRemoveFlow;
    }
}
