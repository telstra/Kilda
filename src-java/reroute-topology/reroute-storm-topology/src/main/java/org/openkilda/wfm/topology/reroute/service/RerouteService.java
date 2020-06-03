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

package org.openkilda.wfm.topology.reroute.service;

import static java.lang.String.format;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.command.reroute.RerouteInactiveFlows;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.info.reroute.SwitchStateChanged;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.EntityNotFoundException;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.reroute.bolts.MessageSender;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData.FlowThrottlingDataBuilder;

import lombok.Data;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class RerouteService {
    private final FlowOperationsDashboardLogger flowDashboardLogger = new FlowOperationsDashboardLogger(log);
    private FlowRepository flowRepository;
    private FlowPathRepository flowPathRepository;
    private PathSegmentRepository pathSegmentRepository;
    private TransactionManager transactionManager;

    public RerouteService(PersistenceManager persistenceManager) {
        this.flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        this.pathSegmentRepository = persistenceManager.getRepositoryFactory().createPathSegmentRepository();
        this.transactionManager = persistenceManager.getTransactionManager();
    }

    /**
     * Handles reroute on ISL down events.
     *
     * @param sender transport sender
     * @param correlationId correlation id to pass through
     * @param command origin command
     */
    @TimedExecution("reroute_affected_flows")
    public void rerouteAffectedFlows(MessageSender sender, String correlationId, RerouteAffectedFlows command) {
        // TODO(surabujin): need better/more detailed representation of failed ISL
        PathNode pathNode = command.getPathNode();
        int port = pathNode.getPortNo();
        SwitchId switchId = pathNode.getSwitchId();
        final IslEndpoint affectedIsl = new IslEndpoint(switchId, port);

        RerouteResult rerouteResult = transactionManager.doInTransaction(() -> {
            RerouteResult result = new RerouteResult();
            Collection<FlowPath> affectedFlowPaths = getAffectedFlowPaths(pathNode.getSwitchId(), pathNode.getPortNo());

            // swapping affected primary paths with available protected
            List<FlowPath> pathsForSwapping = getPathsForSwapping(affectedFlowPaths);
            for (FlowPath path : pathsForSwapping) {
                result.flowIdsForSwapPaths.add(path.getFlowId());
            }

            for (FlowWithAffectedPaths entry : groupPathsForRerouting(affectedFlowPaths)) {
                Flow flow = entry.getFlow();
                boolean flowPathFound = updateFlowPathsStateForFlow(switchId, port, entry.getAffectedPaths());
                FlowStatus flowStatus = flow.computeFlowStatus();
                String flowStatusInfo = null;
                if (!FlowStatus.UP.equals(flowStatus)) {
                    flowStatusInfo = command.getReason();
                }
                flowRepository.updateStatusSafe(flow, flowStatus, flowStatusInfo);

                if (flowPathFound) {
                    result.flowsForReroute.add(flow);
                }
            }

            Set<Flow> affectedPinnedFlows = groupAffectedPinnedFlows(affectedFlowPaths);
            for (Flow flow : affectedPinnedFlows) {
                List<FlowPath> flowPaths = new ArrayList<>(flow.getPaths());
                updateFlowPathsStateForFlow(switchId, port, flowPaths);
                if (flow.getStatus() != FlowStatus.DOWN) {
                    flowDashboardLogger.onFlowStatusUpdate(flow.getFlowId(), FlowStatus.DOWN);
                    flowRepository.updateStatusSafe(flow, FlowStatus.DOWN, command.getReason());
                }
            }
            return result;
        });

        for (String flowId : rerouteResult.flowIdsForSwapPaths) {
            sender.emitPathSwapCommand(correlationId, flowId, command.getReason());
        }
        for (Flow flow : rerouteResult.flowsForReroute) {
            FlowThrottlingData flowThrottlingData = getFlowThrottlingDataBuilder(flow)
                    .correlationId(correlationId)
                    .affectedIsl(Collections.singleton(affectedIsl))
                    .force(false)
                    .effectivelyDown(true)
                    .reason(command.getReason())
                    .build();
            sender.emitRerouteCommand(flow.getFlowId(), flowThrottlingData);
        }
    }

    private boolean updateFlowPathsStateForFlow(SwitchId switchId, int port, List<FlowPath> paths) {
        boolean rerouteRequired = false;
        for (FlowPath fp : paths) {
            boolean failedFlowPath = false;
            for (PathSegment pathSegment : fp.getSegments()) {
                if (pathSegment.getSrcPort() == port
                        && switchId.equals(pathSegment.getSrcSwitchId())
                        || (pathSegment.getDestPort() == port
                        && switchId.equals(pathSegment.getDestSwitchId()))) {
                    pathSegment.setFailed(true);
                    try {
                        pathSegmentRepository.updateFailedStatus(fp, pathSegment, true);
                        failedFlowPath = true;
                        rerouteRequired = true;
                    } catch (EntityNotFoundException e) {
                        log.warn("Path segment not found for flow {} and path {}. Skipping path segment status update.",
                                fp.getFlow().getFlowId(), fp.getPathId(), e);
                    }
                    break;
                }
            }
            if (failedFlowPath) {
                updateFlowPathStatus(fp, FlowPathStatus.INACTIVE);
            }
        }
        return rerouteRequired;
    }

    /**
     * Handles reroute on switch up events.
     *
     * @param sender transport sender
     * @param correlationId correlation id to pass through
     * @param switchId switch id
     */
    @TimedExecution("reroute_inactive_affected_flows")
    public void rerouteInactiveAffectedFlows(MessageSender sender, String correlationId,
                                             SwitchId switchId) {
        Set<Flow> flowsForRerouting = getAffectedInactiveFlowsForRerouting(switchId);

        for (Flow flow : flowsForRerouting) {
            if (flow.isPinned()) {
                log.info("Skipping reroute command for pinned flow {}", flow.getFlowId());
            } else {
                log.info("Produce reroute (attempt to restore inactive flow) request for {} (switch online {})",
                        flow.getFlowId(), switchId);
                // Emit reroute command with empty affectedIsls to force flow to reroute despite it's current paths
                FlowThrottlingData flowThrottlingData = getFlowThrottlingDataBuilder(flow)
                        .correlationId(correlationId)
                        .affectedIsl(Collections.emptySet())
                        .force(false)
                        .effectivelyDown(true)
                        .reason(format("Switch '%s' online", switchId))
                        .build();
                sender.emitRerouteCommand(flow.getFlowId(), flowThrottlingData);
            }
        }
    }

    /**
     * Handles reroute on ISL up events.
     *
     * @param sender transport sender
     * @param correlationId correlation id to pass through
     * @param command origin command
     */
    @TimedExecution("reroute_inactive_flows")
    public void rerouteInactiveFlows(MessageSender sender, String correlationId, RerouteInactiveFlows command) {
        PathNode pathNode = command.getPathNode();
        int port = pathNode.getPortNo();
        SwitchId switchId = pathNode.getSwitchId();

        Map<String, FlowThrottlingData> flowsForReroute = transactionManager.doInTransaction(() -> {
            Map<String, FlowThrottlingData> forReroute = new HashMap<>();
            Map<Flow, Set<PathId>> flowsForRerouting = getInactiveFlowsForRerouting();

            for (Entry<Flow, Set<PathId>> entry : flowsForRerouting.entrySet()) {
                Flow flow = entry.getKey();
                Set<IslEndpoint> allAffectedIslEndpoints = new HashSet<>();
                for (FlowPath flowPath : flow.getPaths()) {
                    Set<IslEndpoint> affectedIslEndpoints = new HashSet<>();
                    PathSegment firstSegment = null;
                    int failedSegmentsCount = 0;
                    for (PathSegment pathSegment : flowPath.getSegments()) {
                        if (firstSegment == null) {
                            firstSegment = pathSegment;
                        }

                        if (pathSegment.isFailed()) {
                            affectedIslEndpoints.add(new IslEndpoint(
                                    pathSegment.getSrcSwitchId(), pathSegment.getSrcPort()));
                            affectedIslEndpoints.add(new IslEndpoint(
                                    pathSegment.getDestSwitchId(), pathSegment.getDestPort()));

                            if (pathSegment.containsNode(switchId, port)) {
                                pathSegment.setFailed(false);
                                pathSegmentRepository.updateFailedStatus(flowPath, pathSegment, false);
                            } else {
                                failedSegmentsCount++;
                            }
                        }
                    }

                    if (flowPath.getStatus().equals(FlowPathStatus.INACTIVE) && failedSegmentsCount == 0) {
                        updateFlowPathStatus(flowPath, FlowPathStatus.ACTIVE);

                        // force reroute of failed path only (required due to inaccurate path/segment state management)
                        if (affectedIslEndpoints.isEmpty() && firstSegment != null) {
                            affectedIslEndpoints.add(new IslEndpoint(
                                    firstSegment.getSrcSwitchId(), firstSegment.getSrcPort()));
                        }
                    }

                    allAffectedIslEndpoints.addAll(affectedIslEndpoints);
                }
                FlowStatus flowStatus = flow.computeFlowStatus();
                String flowStatusInfo = null;
                if (!FlowStatus.UP.equals(flowStatus)) {
                    flowStatusInfo = command.getReason();
                }
                flowRepository.updateStatusSafe(flow, flowStatus, flowStatusInfo);

                if (flow.isPinned()) {
                    log.info("Skipping reroute command for pinned flow {}", flow.getFlowId());
                } else {
                    log.info("Create reroute command (attempt to restore inactive flows) request for {} (affected ISL "
                                    + "endpoints: {})",
                            flow.getFlowId(), allAffectedIslEndpoints);
                    FlowThrottlingData flowThrottlingData = getFlowThrottlingDataBuilder(flow)
                            .correlationId(correlationId)
                            .affectedIsl(allAffectedIslEndpoints)
                            .force(false)
                            .effectivelyDown(true)
                            .reason(command.getReason())
                            .build();
                    forReroute.put(flow.getFlowId(), flowThrottlingData);
                }
            }
            return forReroute;
        });

        for (Entry<String, FlowThrottlingData> entry : flowsForReroute.entrySet()) {
            log.info("Produce reroute (attempt to restore inactive flows) request for {} (affected ISL endpoints: {})",
                    entry.getKey(), entry.getValue().getAffectedIsl());
            sender.emitRerouteCommand(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Get list of active affected flow paths with flows.
     *
     * @param switchId switch id.
     * @param port port.
     * @return list affected flows and flow paths.
     */
    public Collection<FlowPath> getAffectedFlowPaths(SwitchId switchId, int port) {
        log.info("Get affected flow paths by node {}_{}", switchId, port);
        return flowPathRepository.findBySegmentEndpoint(switchId, port);
    }


    /**
     * Get flow paths list to swap.
     *
     * @return list of flows paths.
     */
    public List<FlowPath> getPathsForSwapping(Collection<FlowPath> paths) {
        return paths.stream()
                .filter(path -> path.getStatus() == null || FlowPathStatus.ACTIVE.equals(path.getStatus()))
                .filter(path -> path.getFlow().isAllocateProtectedPath())
                .filter(this::filterForwardPrimaryPath)
                .collect(Collectors.toList());
    }

    private boolean filterForwardPrimaryPath(FlowPath path) {
        return path.getPathId().equals(path.getFlow().getForwardPathId());
    }

    /**
     * Returns map with flow for reroute and set of reroute pathId.
     *
     * @return map with flow for reroute and set of reroute pathId.
     */
    public List<FlowWithAffectedPaths> groupPathsForRerouting(Collection<FlowPath> paths) {
        Map<String, FlowWithAffectedPaths> results = new HashMap<>();
        for (FlowPath entry : paths) {
            Flow flow = entry.getFlow();
            if (flow.isPinned()) {
                continue;
            }
            results.computeIfAbsent(flow.getFlowId(), key -> new FlowWithAffectedPaths(flow))
                    .getAffectedPaths().add(entry);
        }
        return new ArrayList<>(results.values());
    }

    /**
     * Filters out unique pinned flow from paths.
     *
     * @param paths affected paths
     */
    public Set<Flow> groupAffectedPinnedFlows(Collection<FlowPath> paths) {
        return paths.stream()
                .filter(path -> path.getFlow().isPinned())
                .map(FlowPath::getFlow)
                .collect(Collectors.toSet());
    }

    /**
     * Returns map with inactive flow and flow pathId set for rerouting.
     */
    public Map<Flow, Set<PathId>> getInactiveFlowsForRerouting() {
        log.info("Get inactive flows");
        return flowRepository.findInactiveFlows().stream()
                .filter(flow -> !flow.isOneSwitchFlow())
                .collect(toMap(Function.identity(),
                        flow -> flow.getPaths().stream()
                                .filter(path -> FlowPathStatus.INACTIVE.equals(path.getStatus())
                                        || FlowPathStatus.DEGRADED.equals(path.getStatus()))
                                .map(FlowPath::getPathId)
                                .collect(Collectors.toSet()))
                );
    }

    /**
     * Returns affected inactive flow set for rerouting.
     */
    public Set<Flow> getAffectedInactiveFlowsForRerouting(SwitchId switchId) {
        log.info("Get affected inactive flows for switch {}", switchId);
        return flowPathRepository.findInactiveBySegmentSwitch(switchId).stream()
                .map(FlowPath::getFlow)
                .filter(flow -> ! flow.isOneSwitchFlow())
                .collect(toSet());
    }

    private void updateFlowPathStatus(FlowPath path, FlowPathStatus status) {
        try {
            path.setStatus(status);
        } catch (PersistenceException e) {
            log.error("Unable to set path {} status to {}: {}", path.getPathId(), status, e.getMessage());
        }
    }

    /**
     * Process manual reroute request.
     */
    public void processManualRerouteRequest(MessageSender sender, String correlationId, FlowRerouteRequest request) {
        Optional<Flow> flow = flowRepository.findById(request.getFlowId());
        FlowThrottlingData flowThrottlingData = getFlowThrottlingDataBuilder(flow.orElse(null))
                .correlationId(correlationId)
                .affectedIsl(request.getAffectedIsl())
                .force(request.isForce())
                .effectivelyDown(request.isEffectivelyDown())
                .reason(request.getReason())
                .build();
        sender.emitManualRerouteCommand(request.getFlowId(), flowThrottlingData);
    }

    /**
     * Handles request to update single switch flow status.
     */
    public void processSingleSwitchFlowStatusUpdate(SwitchStateChanged request) {
        transactionManager.doInTransaction(() -> {
            Collection<Flow> affectedFlows = flowRepository.findOneSwitchFlows(request.getSwitchId());
            FlowStatus newFlowStatus = request.getStatus() == SwitchStatus.ACTIVE ? FlowStatus.UP : FlowStatus.DOWN;
            String newFlowStatusInfo = request.getStatus() == SwitchStatus.ACTIVE
                    ? null : format("Switch %s is inactive", request.getSwitchId());
            FlowPathStatus newFlowPathStatus = request.getStatus() == SwitchStatus.ACTIVE
                    ? FlowPathStatus.ACTIVE : FlowPathStatus.INACTIVE;
            for (Flow flow : affectedFlows) {
                log.info("Updating flow and path statuses for flow {} to {}, {}", flow.getFlowId(), newFlowStatus,
                        newFlowPathStatus);
                flowDashboardLogger.onFlowStatusUpdate(flow.getFlowId(), newFlowStatus);
                flow.setStatus(newFlowStatus);
                flow.setStatusInfo(newFlowStatusInfo);
                flow.getForwardPath().setStatus(newFlowPathStatus);
                flow.getReversePath().setStatus(newFlowPathStatus);
            }
        });
    }

    private FlowThrottlingDataBuilder getFlowThrottlingDataBuilder(Flow flow) {
        return flow == null ? FlowThrottlingData.builder() :
                FlowThrottlingData.builder()
                        .priority(flow.getPriority())
                        .timeCreate(flow.getTimeCreate())
                        .pathComputationStrategy(flow.getPathComputationStrategy())
                        .bandwidth(flow.getBandwidth());
    }

    @Value
    private static class FlowWithAffectedPaths {
        private Flow flow;

        private List<FlowPath> affectedPaths = new ArrayList<>();
    }

    @Data
    private static class RerouteResult {
        Set<String> flowIdsForSwapPaths = new HashSet<>();
        Set<Flow> flowsForReroute = new HashSet<>();
    }
}
