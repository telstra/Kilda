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

package org.openkilda.wfm.topology.nbworker.services;

import static java.lang.String.format;
import static org.apache.commons.collections4.ListUtils.union;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.model.FlowPathDto;
import org.openkilda.messaging.model.FlowPathDto.FlowPathDtoBuilder;
import org.openkilda.messaging.model.FlowPathDto.FlowProtectedPathDto;
import org.openkilda.messaging.payload.flow.PathNodePayload;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.ClientException;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.mappers.FlowPathMapper;
import org.openkilda.wfm.share.service.IntersectionComputer;
import org.openkilda.wfm.topology.nbworker.bolts.FlowOperationsCarrier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class FlowOperationsService {
    private final FlowOperationsDashboardLogger flowDashboardLogger = new FlowOperationsDashboardLogger(log);

    private static final int MAX_RETRY_COUNT = 3;
    private static final int RETRY_DELAY = 100;

    private TransactionManager transactionManager;
    private IslRepository islRepository;
    private SwitchRepository switchRepository;
    private FlowRepository flowRepository;
    private FlowPathRepository flowPathRepository;
    private SwitchConnectedDeviceRepository switchConnectedDeviceRepository;

    public FlowOperationsService(RepositoryFactory repositoryFactory, TransactionManager transactionManager) {
        this.islRepository = repositoryFactory.createIslRepository();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.flowRepository = repositoryFactory.createFlowRepository();
        this.flowPathRepository = repositoryFactory.createFlowPathRepository();
        this.switchConnectedDeviceRepository = repositoryFactory.createSwitchConnectedDeviceRepository();
        this.transactionManager = transactionManager;
    }

    private SyncFailsafe getReadOperationFailsafe() {
        return Failsafe.with(new RetryPolicy()
                .retryOn(ClientException.class)
                .withDelay(RETRY_DELAY, TimeUnit.MILLISECONDS)
                .withMaxRetries(MAX_RETRY_COUNT))
                .onRetry(e -> log.warn("Retrying transaction finished with exception", e))
                .onRetriesExceeded(e -> log.warn("TX retry attempts exceed with error", e));
    }

    /**
     * Return flow by flow id.
     */
    public Flow getFlow(String flowId) throws FlowNotFoundException {
        Optional<Flow> found = (Optional<Flow>) getReadOperationFailsafe().get(() ->
                transactionManager.doInTransaction(() -> flowRepository.findById(flowId)));
        return found.orElseThrow(() -> new FlowNotFoundException(flowId));
    }

    /**
     * Return flows in the same flow group.
     *
     * @param flowId flow id
     * @param groupId group id
     * @return list of flow ids
     */
    public Set<String> getDiverseFlowsId(String flowId, String groupId) {
        if (groupId == null) {
            return null;
        }

        return flowRepository.findFlowsIdByGroupId(groupId).stream()
                .filter(id -> !id.equals(flowId))
                .collect(Collectors.toSet());
    }

    /**
     * Get flows.
     */
    public Collection<Flow> getAllFlows() {
        return (Collection<Flow>) getReadOperationFailsafe().get(() ->
                transactionManager.doInTransaction(() -> flowRepository.findAll())
        );
    }

    /**
     * Return all paths for a particular link.
     *
     * @param srcSwitchId source switch id.
     * @param srcPort source port.
     * @param dstSwitchId destination switch id.
     * @param dstPort destination port.
     * @return all paths for a particular link.
     * @throws IslNotFoundException if there is no link with these parameters.
     */
    public Collection<FlowPath> getFlowPathsForLink(SwitchId srcSwitchId, Integer srcPort,
                                                    SwitchId dstSwitchId, Integer dstPort)
            throws IslNotFoundException {

        flowDashboardLogger.onFlowPathsDumpByLink(srcSwitchId, srcPort, dstSwitchId, dstPort);

        if (!islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort).isPresent()) {
            throw new IslNotFoundException(srcSwitchId, srcPort, dstSwitchId, dstPort);
        }

        return flowPathRepository.findWithPathSegment(srcSwitchId, srcPort, dstSwitchId, dstPort);
    }

    /**
     * Return all flows for a particular endpoint or for a particular switch if port is null.
     *
     * @param switchId switch id.
     * @param port port.
     * @return all flows for a particular endpoint.
     * @throws SwitchNotFoundException if there is no switch with this switch id.
     */
    public Collection<Flow> getFlowsForEndpoint(SwitchId switchId, Integer port)
            throws SwitchNotFoundException {

        flowDashboardLogger.onFlowPathsDumpByEndpoint(switchId, port);

        if (!switchRepository.findById(switchId).isPresent()) {
            throw new SwitchNotFoundException(switchId);
        }
        Set<Flow> flows = new HashSet<>();

        if (port != null) {
            flowPathRepository.findBySegmentEndpoint(switchId, port).stream()
                    .map(FlowPath::getFlow)
                    .forEach(flows::add);
            flows.addAll(flowRepository.findByEndpoint(switchId, port));
        } else {
            flowPathRepository.findBySegmentSwitch(switchId).stream()
                    .map(FlowPath::getFlow)
                    .forEach(flows::add);
            flows.addAll(flowRepository.findByEndpointSwitch(switchId));
        }
        // need to return Flows unique by id
        return flows.stream()
                .collect(Collectors.toMap(Flow::getFlowId, Function.identity()))
                .values();
    }

    /**
     * Return flow paths for a switch.
     *
     * @param switchId switch id.
     * @return all flow paths for a switch.
     */
    public Collection<FlowPath> getFlowPathsForSwitch(SwitchId switchId) {
        flowDashboardLogger.onFlowPathsDumpBySwitch(switchId);

        return flowPathRepository.findBySegmentSwitch(switchId);
    }

    /**
     * Returns flow path. If flow has group, returns also path for each flow in group.
     *
     * @param flowId the flow to get a path.
     */
    public List<FlowPathDto> getFlowPath(String flowId) throws FlowNotFoundException {
        flowDashboardLogger.onFlowPathsRead(flowId);

        Flow flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new FlowNotFoundException(flowId));

        String groupId = flow.getGroupId();
        if (groupId == null) {
            return Collections.singletonList(
                    toFlowPathDtoBuilder(flow).build());
        } else {
            Collection<Flow> flowsInGroup = flowRepository.findByGroupId(groupId);
            Collection<FlowPath> flowPathsInGroup = flowPathRepository.findByFlowGroupId(groupId);

            IntersectionComputer primaryIntersectionComputer = new IntersectionComputer(
                    flow.getFlowId(), flow.getForwardPathId(), flow.getReversePathId(), flowPathsInGroup);

            // target flow primary path
            FlowPathDtoBuilder targetFlowDtoBuilder = this.toFlowPathDtoBuilder(flow)
                    .segmentsStats(primaryIntersectionComputer.getOverlappingStats());

            // other flows in the the group
            List<FlowPathDto> payloads = flowsInGroup.stream()
                    .filter(e -> !e.getFlowId().equals(flowId))
                    .map(e -> this.mapGroupPathFlowDto(e, true, primaryIntersectionComputer))
                    .collect(Collectors.toList());

            if (flow.isAllocateProtectedPath()) {
                IntersectionComputer protectedIntersectionComputer = new IntersectionComputer(
                        flow.getFlowId(), flow.getProtectedForwardPathId(), flow.getProtectedReversePathId(),
                        flowPathsInGroup);

                // target flow protected path
                targetFlowDtoBuilder.protectedPath(FlowProtectedPathDto.builder()
                        .forwardPath(buildPathFromFlow(flow, flow.getProtectedForwardPath()))
                        .reversePath(buildPathFromFlow(flow, flow.getProtectedReversePath()))
                        .segmentsStats(
                                protectedIntersectionComputer.getOverlappingStats())
                        .build());

                // other flows in the the group
                List<FlowPathDto> protectedPathPayloads = flowsInGroup.stream()
                        .filter(e -> !e.getFlowId().equals(flowId))
                        .map(e -> this.mapGroupPathFlowDto(e, false, protectedIntersectionComputer))
                        .collect(Collectors.toList());
                payloads = union(payloads, protectedPathPayloads);
            }

            payloads.add(targetFlowDtoBuilder.build());

            return payloads;
        }
    }

    private FlowPathDto mapGroupPathFlowDto(Flow flow, boolean primaryPathCorrespondStat,
                                            IntersectionComputer intersectionComputer) {
        FlowPathDtoBuilder builder = this.toFlowPathDtoBuilder(flow)
                .primaryPathCorrespondStat(primaryPathCorrespondStat)
                .segmentsStats(
                        intersectionComputer.getOverlappingStats(flow.getForwardPathId(), flow.getReversePathId()));
        if (flow.isAllocateProtectedPath()) {
            builder.protectedPath(FlowProtectedPathDto.builder()
                    .forwardPath(buildPathFromFlow(flow, flow.getProtectedForwardPath()))
                    .reversePath(buildPathFromFlow(flow, flow.getProtectedReversePath()))
                    .segmentsStats(
                            intersectionComputer.getOverlappingStats(
                                    flow.getProtectedForwardPathId(), flow.getProtectedReversePathId()))
                    .build());
        }
        return builder.build();
    }

    private FlowPathDtoBuilder toFlowPathDtoBuilder(Flow flow) {
        return FlowPathDto.builder()
                .id(flow.getFlowId())
                .forwardPath(buildPathFromFlow(flow, flow.getForwardPath()))
                .reversePath(buildPathFromFlow(flow, flow.getReversePath()));
    }

    private List<PathNodePayload> buildPathFromFlow(Flow flow, FlowPath flowPath) {
        return flowPath != null ? FlowPathMapper.INSTANCE.mapToPathNodes(flow, flowPath) : Collections.emptyList();
    }

    /**
     * Update flow.
     *
     * @param flow flow.
     * @return updated flow.
     */
    public Flow updateFlow(FlowOperationsCarrier carrier, FlowDto flow) throws FlowNotFoundException {
        UpdateFlowResult updateFlowResult = transactionManager.doInTransaction(() -> {
            Optional<Flow> foundFlow = flowRepository.findById(flow.getFlowId());
            if (!foundFlow.isPresent()) {
                return Optional.<UpdateFlowResult>empty();
            }
            Flow currentFlow = foundFlow.get();

            final UpdateFlowResult.UpdateFlowResultBuilder result = prepareFlowUpdateResult(flow, currentFlow);

            if (flow.getMaxLatency() != null) {
                currentFlow.setMaxLatency(flow.getMaxLatency());
            }
            if (flow.getPriority() != null) {
                currentFlow.setPriority(flow.getPriority());
            }
            if (flow.getPeriodicPings() != null) {
                boolean oldPeriodicPings = currentFlow.isPeriodicPings();
                currentFlow.setPeriodicPings(flow.getPeriodicPings());
                if (oldPeriodicPings != currentFlow.isPeriodicPings()) {
                    carrier.emitPeriodicPingUpdate(flow.getFlowId(), flow.getPeriodicPings());
                }
            }
            if (flow.getTargetPathComputationStrategy() != null) {
                currentFlow.setTargetPathComputationStrategy(flow.getTargetPathComputationStrategy());
            }

            flowDashboardLogger.onFlowPatchUpdate(currentFlow);

            return Optional.of(result.updatedFlow(currentFlow).build());

        }).orElseThrow(() -> new FlowNotFoundException(flow.getFlowId()));

        if (updateFlowResult.isNeedRerouteFlow()) {
            Flow updatedFlow = updateFlowResult.getUpdatedFlow();
            Set<IslEndpoint> affectedIslEndpoints =
                    Sets.newHashSet(new IslEndpoint(flow.getSourceSwitch(), flow.getSourcePort()),
                            new IslEndpoint(flow.getDestinationSwitch(), flow.getDestinationPort()));
            carrier.sendRerouteRequest(updatedFlow.getPaths(), affectedIslEndpoints,
                    updateFlowResult.getRerouteReason());
        }

        return updateFlowResult.getUpdatedFlow();
    }

    @VisibleForTesting
    UpdateFlowResult.UpdateFlowResultBuilder prepareFlowUpdateResult(FlowDto flowDto, Flow flow) {
        boolean changedStrategy = flowDto.getPathComputationStrategy() != null
                && !flowDto.getPathComputationStrategy().equals(flow.getPathComputationStrategy());
        boolean changedMaxLatency = flowDto.getMaxLatency() != null
                && !flowDto.getMaxLatency().equals(flow.getMaxLatency());
        boolean strategyIsMaxLatency = PathComputationStrategy.MAX_LATENCY.equals(flowDto.getPathComputationStrategy())
                || flowDto.getPathComputationStrategy() == null
                && PathComputationStrategy.MAX_LATENCY.equals(flow.getPathComputationStrategy());

        String reason = null;
        if (changedStrategy) {
            reason = format("initiated via Northbound, path computation strategy was changed from %s to %s",
                    flow.getPathComputationStrategy(), flowDto.getPathComputationStrategy());
        } else if (changedMaxLatency && strategyIsMaxLatency) {
            reason = format("initiated via Northbound, max latency was changed from %d to %d",
                    flow.getMaxLatency(), flowDto.getMaxLatency());
        }

        return UpdateFlowResult.builder()
                .needRerouteFlow(changedStrategy || changedMaxLatency && strategyIsMaxLatency)
                .rerouteReason(reason);
    }

    /**
     * Get connected devices for Flow.
     *
     * @param flowId flow ID
     * @return connected devices for flow
     */
    public Collection<SwitchConnectedDevice> getFlowConnectedDevice(String flowId) throws FlowNotFoundException {
        return transactionManager.doInTransaction(() -> {
            if (!flowRepository.exists(flowId)) {
                throw new FlowNotFoundException(flowId);
            }

            return switchConnectedDeviceRepository.findByFlowId(flowId);
        });
    }

    /**
     * Produce reroute request for all affected paths/flows.
     */
    public List<FlowRerouteRequest> makeRerouteRequests(
            Collection<FlowPath> targetPaths, Set<IslEndpoint> affectedIslEndpoints, String reason) {
        List<FlowRerouteRequest> results = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        for (FlowPath entry : targetPaths) {
            Flow flow = entry.getFlow();
            if (processed.add(flow.getFlowId())) {
                FlowRerouteRequest request = new FlowRerouteRequest(
                        flow.getFlowId(), false, false, affectedIslEndpoints, reason);
                results.add(request);
            }
        }

        return results;
    }

    @Data
    @Builder
    static class UpdateFlowResult {
        private Flow updatedFlow;
        private boolean needRerouteFlow;
        private String rerouteReason;
    }
}
