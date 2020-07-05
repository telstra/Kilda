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

package org.openkilda.wfm.topology.nbworker.services;

import static org.apache.commons.collections4.ListUtils.union;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.model.FlowPatch;
import org.openkilda.messaging.model.FlowPathDto;
import org.openkilda.messaging.model.FlowPathDto.FlowPathDtoBuilder;
import org.openkilda.messaging.model.FlowPathDto.FlowProtectedPathDto;
import org.openkilda.messaging.payload.flow.PathNodePayload;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
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
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.share.mappers.FlowMapper;
import org.openkilda.wfm.share.mappers.FlowPathMapper;
import org.openkilda.wfm.share.mappers.RequestedFlowMapper;
import org.openkilda.wfm.share.service.IntersectionComputer;
import org.openkilda.wfm.topology.nbworker.bolts.FlowOperationsCarrier;

import com.google.common.annotations.VisibleForTesting;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.SyncFailsafe;
import org.neo4j.driver.v1.exceptions.ClientException;

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

    private static final int MAX_TRANSACTION_RETRY_COUNT = 3;
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
                .withMaxRetries(MAX_TRANSACTION_RETRY_COUNT))
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
                    // NOTE(tdurakov): filter out paths here that are orphaned for the flow
                    .filter(flowPath -> flowPath.getFlow().isActualPathId(flowPath.getPathId()))
                    .map(FlowPath::getFlow)
                    .forEach(flows::add);
            flows.addAll(flowRepository.findByEndpoint(switchId, port));
        } else {
            flowPathRepository.findBySegmentSwitch(switchId).stream()
                    // NOTE(tdurakov): filter out paths here that are orphaned for the flow
                    .filter(flowPath -> flowPath.getFlow().isActualPathId(flowPath.getPathId()))
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
     * Partial update flow.
     */
    public Flow updateFlow(FlowOperationsCarrier carrier, FlowPatch flowPatch) throws FlowNotFoundException {
        UpdateFlowResult updateFlowResult = transactionManager.doInTransaction(() -> {
            Optional<Flow> foundFlow = flowRepository.findById(flowPatch.getFlowId());
            if (!foundFlow.isPresent()) {
                return Optional.<UpdateFlowResult>empty();
            }
            Flow currentFlow = foundFlow.get();

            final UpdateFlowResult.UpdateFlowResultBuilder result = prepareFlowUpdateResult(flowPatch, currentFlow);

            if (flowPatch.getMaxLatency() != null) {
                currentFlow.setMaxLatency(flowPatch.getMaxLatency());
            }
            if (flowPatch.getPriority() != null) {
                currentFlow.setPriority(flowPatch.getPriority());
            }
            if (flowPatch.getPeriodicPings() != null) {
                boolean oldPeriodicPings = currentFlow.isPeriodicPings();
                currentFlow.setPeriodicPings(flowPatch.getPeriodicPings());
                if (oldPeriodicPings != currentFlow.isPeriodicPings()) {
                    carrier.emitPeriodicPingUpdate(flowPatch.getFlowId(), flowPatch.getPeriodicPings());
                }
            }
            if (flowPatch.getTargetPathComputationStrategy() != null) {
                currentFlow.setTargetPathComputationStrategy(flowPatch.getTargetPathComputationStrategy());
            }
            if (flowPatch.getPinned() != null) {
                currentFlow.setPinned(flowPatch.getPinned());
            }

            flowDashboardLogger.onFlowPatchUpdate(currentFlow);

            flowRepository.createOrUpdate(currentFlow);

            return Optional.of(result.updatedFlow(currentFlow).build());

        }).orElseThrow(() -> new FlowNotFoundException(flowPatch.getFlowId()));

        Flow updatedFlow = updateFlowResult.getUpdatedFlow();
        if (updateFlowResult.isNeedUpdateFlow()) {
            FlowRequest flowRequest = RequestedFlowMapper.INSTANCE.toFlowRequest(updatedFlow);
            carrier.sendUpdateRequest(addChangedFields(flowRequest, flowPatch, updateFlowResult.getDiverseFlowId()));
        } else {
            carrier.sendNorthboundResponse(new FlowResponse(FlowMapper.INSTANCE.map(updatedFlow)));
        }

        return updateFlowResult.getUpdatedFlow();
    }

    @VisibleForTesting
    UpdateFlowResult.UpdateFlowResultBuilder prepareFlowUpdateResult(FlowPatch flowPatch, Flow flow) {
        boolean updateRequired = flowPatch.getPathComputationStrategy() != null
                && !flowPatch.getPathComputationStrategy().equals(flow.getPathComputationStrategy());
        boolean changedMaxLatency = flowPatch.getMaxLatency() != null
                && !flowPatch.getMaxLatency().equals(flow.getMaxLatency());
        boolean strategyIsMaxLatency =
                PathComputationStrategy.MAX_LATENCY.equals(flowPatch.getPathComputationStrategy())
                || flowPatch.getPathComputationStrategy() == null
                && PathComputationStrategy.MAX_LATENCY.equals(flow.getPathComputationStrategy());
        updateRequired |= changedMaxLatency && strategyIsMaxLatency;

        // source endpoint
        updateRequired |= flowPatch.getSourceSwitch() != null
                && !flow.getSrcSwitch().getSwitchId().equals(flowPatch.getSourceSwitch());
        updateRequired |= flowPatch.getSourcePort() != null
                && flow.getSrcPort() != flowPatch.getSourcePort();
        updateRequired |= flowPatch.getSourceVlan() != null
                && flow.getSrcVlan() != flowPatch.getSourceVlan();

        // destination endpoint
        updateRequired |= flowPatch.getDestinationSwitch() != null
                && !flow.getDestSwitch().getSwitchId().equals(flowPatch.getDestinationSwitch());
        updateRequired |= flowPatch.getDestinationPort() != null
                && flow.getDestPort() != flowPatch.getDestinationPort();
        updateRequired |= flowPatch.getDestinationVlan() != null
                && flow.getDestVlan() != flowPatch.getDestinationVlan();

        updateRequired |= flowPatch.getBandwidth() != null && flow.getBandwidth() != flowPatch.getBandwidth();
        updateRequired |= flowPatch.getAllocateProtectedPath() != null
                && !flowPatch.getAllocateProtectedPath().equals(flow.isAllocateProtectedPath());

        String diverseFlowId = null;
        boolean changedDiverseFlowId = false;
        if ("".equals(flowPatch.getDiverseFlowId())) {
            changedDiverseFlowId = true;
        } else if (flowPatch.getDiverseFlowId() == null) {
            diverseFlowId = flowRepository.getOrCreateFlowGroupId(flow.getFlowId())
                    .map(groupId -> flowRepository.findFlowsIdByGroupId(groupId))
                    .orElse(Collections.emptyList()).stream()
                    .filter(flowId -> !flow.getFlowId().equals(flowId))
                    .findAny().orElse(null);
        } else if (flowPatch.getDiverseFlowId() != null) {
            changedDiverseFlowId = flowRepository.getOrCreateFlowGroupId(flowPatch.getDiverseFlowId())
                    .map(groupId -> !flowRepository.findFlowsIdByGroupId(groupId).contains(flow.getFlowId()))
                    .orElse(true);
            diverseFlowId = flowPatch.getDiverseFlowId();
        }
        updateRequired |= changedDiverseFlowId;

        return UpdateFlowResult.builder()
                .needUpdateFlow(updateRequired)
                .diverseFlowId(diverseFlowId);
    }

    private FlowRequest addChangedFields(FlowRequest flowRequest, FlowPatch flowPatch, String diverseFlowId) {
        SwitchId srcSwitchId = Optional.ofNullable(flowPatch.getSourceSwitch())
                .orElse(flowRequest.getSource().getSwitchId());
        int srcPort = Optional.ofNullable(flowPatch.getSourcePort()).orElse(flowRequest.getSource().getPortNumber());
        int srcVlan = Optional.ofNullable(flowPatch.getSourceVlan()).orElse(flowRequest.getSource().getOuterVlanId());
        flowRequest.setSource(new FlowEndpoint(srcSwitchId, srcPort, srcVlan));

        SwitchId dstSwitchId = Optional.ofNullable(flowPatch.getDestinationSwitch())
                .orElse(flowRequest.getDestination().getSwitchId());
        int dstPort = Optional.ofNullable(flowPatch.getDestinationPort())
                .orElse(flowRequest.getDestination().getPortNumber());
        int dstVlan = Optional.ofNullable(flowPatch.getDestinationVlan())
                .orElse(flowRequest.getDestination().getOuterVlanId());
        flowRequest.setDestination(new FlowEndpoint(dstSwitchId, dstPort, dstVlan));

        Optional.ofNullable(flowPatch.getBandwidth()).ifPresent(flowRequest::setBandwidth);
        Optional.ofNullable(flowPatch.getAllocateProtectedPath()).ifPresent(flowRequest::setAllocateProtectedPath);
        Optional.ofNullable(diverseFlowId).ifPresent(flowRequest::setDiverseFlowId);

        return flowRequest;
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
                        flow.getFlowId(), false, false, false, affectedIslEndpoints, reason);
                results.add(request);
            }
        }

        return results;
    }

    @Data
    @Builder
    static class UpdateFlowResult {
        private Flow updatedFlow;
        private boolean needUpdateFlow;
        private String diverseFlowId;
    }
}
