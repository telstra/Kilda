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

package org.openkilda.wfm.topology.reroute.service;

import static java.lang.String.format;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.command.reroute.RerouteInactiveFlows;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.reroute.bolts.MessageSender;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData;

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
    private FlowPathRepository pathRepository;
    private PathSegmentRepository pathSegmentRepository;
    private TransactionManager transactionManager;

    public RerouteService(PersistenceManager persistenceManager) {
        this.flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        this.pathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
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
    public void rerouteAffectedFlows(MessageSender sender, String correlationId, RerouteAffectedFlows command) {
        // TODO(surabujin): need better/more detailed representation of failed ISL
        PathNode pathNode = command.getPathNode();
        int port = pathNode.getPortNo();
        SwitchId switchId = pathNode.getSwitchId();
        final IslEndpoint affectedIsl = new IslEndpoint(switchId, port);

        transactionManager.doInTransaction(() -> {
            Collection<FlowPath> affectedFlowPaths = getAffectedFlowPaths(pathNode.getSwitchId(), pathNode.getPortNo());

            // swapping affected primary paths with available protected
            List<FlowPath> pathsForSwapping = getPathsForSwapping(affectedFlowPaths);
            for (FlowPath path : pathsForSwapping) {
                sender.emitPathSwapCommand(correlationId, path, command.getReason());
            }

            for (FlowWithAffectedPaths entry : groupPathsForRerouting(affectedFlowPaths)) {
                Flow flow = entry.getFlow();
                updateFlowPathsStateForFlow(switchId, port, entry.getAffectedPaths());
                flowRepository.updateStatusSafe(flow, flow.computeFlowStatus());

                FlowThrottlingData flowThrottlingData = FlowThrottlingData.builder()
                        .correlationId(correlationId)
                        .priority(flow.getPriority())
                        .timeCreate(flow.getTimeCreate())
                        .affectedIsl(Collections.singleton(affectedIsl))
                        .force(false)
                        .effectivelyDown(true)
                        .reason(command.getReason())
                        .build();
                sender.emitRerouteCommand(flow.getFlowId(), flowThrottlingData);
            }

            Set<Flow> affectedPinnedFlows = groupAffectedPinnedFlows(affectedFlowPaths);
            for (Flow flow : affectedPinnedFlows) {
                List<FlowPath> flowPaths = new ArrayList<>(flow.getPaths());
                updateFlowPathsStateForFlow(switchId, port, flowPaths);
                if (flow.getStatus() != FlowStatus.DOWN) {
                    flowDashboardLogger.onFlowStatusUpdate(flow.getFlowId(), FlowStatus.DOWN);
                    flowRepository.updateStatusSafe(flow, FlowStatus.DOWN);
                }
            }
        });
    }

    private void updateFlowPathsStateForFlow(SwitchId switchId, int port, List<FlowPath> paths) {
        for (FlowPath fp : paths) {
            boolean failedFlowPath = false;
            for (PathSegment pathSegment : fp.getSegments()) {
                if (pathSegment.getSrcPort() == port
                        && switchId.equals(pathSegment.getSrcSwitchId())
                        || (pathSegment.getDestPort() == port
                        && switchId.equals(pathSegment.getDestSwitchId()))) {
                    pathSegment.setFailed(true);
                    pathSegmentRepository.updateFailedStatus(fp, pathSegment, true);
                    failedFlowPath = true;
                    break;
                }
            }
            if (failedFlowPath) {
                updateFlowPathStatus(fp, FlowPathStatus.INACTIVE);
            }
        }
    }

    /**
     * Handles reroute on switch up events.
     *
     * @param sender transport sender
     * @param correlationId correlation id to pass through
     * @param switchId switch id
     */
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
                FlowThrottlingData flowThrottlingData = FlowThrottlingData.builder()
                        .correlationId(correlationId)
                        .priority(flow.getPriority())
                        .timeCreate(flow.getTimeCreate())
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
    public void rerouteInactiveFlows(MessageSender sender, String correlationId, RerouteInactiveFlows command) {
        PathNode pathNode = command.getPathNode();
        int port = pathNode.getPortNo();
        SwitchId switchId = pathNode.getSwitchId();

        transactionManager.doInTransaction(() -> {
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
                flowRepository.updateStatusSafe(flow, flow.computeFlowStatus());

                if (flow.isPinned()) {
                    log.info("Skipping reroute command for pinned flow {}", flow.getFlowId());
                } else {
                    log.info("Produce reroute(attempt to restore inactive flows) request for {} (affected ISL "
                                    + "endpoints: {})",
                            flow.getFlowId(), allAffectedIslEndpoints);
                    FlowThrottlingData flowThrottlingData = FlowThrottlingData.builder()
                            .correlationId(correlationId)
                            .priority(flow.getPriority())
                            .timeCreate(flow.getTimeCreate())
                            .affectedIsl(allAffectedIslEndpoints)
                            .force(false)
                            .effectivelyDown(true)
                            .reason(command.getReason())
                            .build();
                    sender.emitRerouteCommand(flow.getFlowId(), flowThrottlingData);
                }
            }
        });
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
        return pathRepository.findBySegmentEndpoint(switchId, port);
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
        return flowRepository.findDownFlows().stream()
                .collect(toMap(Function.identity(),
                        flow -> flow.getPaths().stream()
                                .filter(path -> FlowPathStatus.INACTIVE.equals(path.getStatus()))
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
        FlowThrottlingData flowThrottlingData = FlowThrottlingData.builder()
                .correlationId(correlationId)
                .priority(flow.map(Flow::getPriority).orElse(null))
                .timeCreate(flow.map(Flow::getTimeCreate).orElse(null))
                .affectedIsl(request.getAffectedIsl())
                .force(request.isForce())
                .effectivelyDown(request.isEffectivelyDown())
                .reason(request.getReason())
                .build();
        sender.emitManualRerouteCommand(request.getFlowId(), flowThrottlingData);
    }

    @Value
    private static class FlowWithAffectedPaths {
        private Flow flow;

        private List<FlowPath> affectedPaths = new ArrayList<>();
    }
}
