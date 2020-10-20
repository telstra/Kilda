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

import org.openkilda.messaging.MessageContext;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathId;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocateProtectedResourcesResponse;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocateProtectedResourcesResponse.AllocatePathStatus;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocateProtectedResourcesResponse.AllocateProtectedResourcesResponseBuilder;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.BaseAllocateResourcesResponse;
import org.openkilda.wfm.topology.flowhs.service.FlowPathBuilder;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import com.google.common.collect.Lists;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public class AllocateProtectedResourcesCommand extends BaseResourceAllocationCommand {

    private final PathId newPrimaryForwardPathId;
    private final PathId newPrimaryReversePathId;

    public AllocateProtectedResourcesCommand(@NonNull MessageContext messageContext, @NonNull UUID commandId,
                                             CommandContext commandContext, FlowRerouteContext context, String flowId,
                                             FlowEncapsulationType newEncapsulationType, boolean recreateIfSamePath,
                                             Collection<PathId> rejectedPaths, PathId newPrimaryForwardPathId,
                                             PathId newPrimaryReversePathId) {
        super(messageContext, commandId, commandContext, context, flowId, newEncapsulationType, recreateIfSamePath,
                rejectedPaths);
        this.newPrimaryForwardPathId = newPrimaryForwardPathId;
        this.newPrimaryReversePathId = newPrimaryReversePathId;
    }

    @Override
    protected BaseAllocateResourcesResponse allocate(SpeakerWorkerService service)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {

        final long time = System.currentTimeMillis();
        Flow tmpFlowCopy = service.getFlow(flowId);
        // Detach the entity to avoid propagation to the database.
        service.getFlowRepository().detach(tmpFlowCopy);
        if (newEncapsulationType != null) {
            // This is for PCE to use proper (updated) encapsulation type.
            tmpFlowCopy.setEncapsulationType(newEncapsulationType);
        }

        log.debug("Finding a new protected path for flow {}", flowId);
        GetPathsResult potentialPath = service.getPathComputer().getPath(tmpFlowCopy,
                Stream.of(tmpFlowCopy.getProtectedForwardPathId(), tmpFlowCopy.getProtectedReversePathId())
                        .filter(Objects::nonNull).collect(Collectors.toList()),
                getBackUpStrategies(tmpFlowCopy.getPathComputationStrategy()));

        AllocateProtectedResourcesResponseBuilder responseBuilder = AllocateProtectedResourcesResponse.builder()
                .messageContext(messageContext)
                .commandId(commandId)
                .newPrimaryPathComputationStrategy(potentialPath.getUsedStrategy());

        FlowPath primaryForwardPath = tmpFlowCopy.getPath(newPrimaryForwardPathId)
                .orElse(tmpFlowCopy.getForwardPath());
        FlowPath primaryReversePath = tmpFlowCopy.getPath(newPrimaryReversePathId)
                .orElse(tmpFlowCopy.getReversePath());
        boolean overlappingProtectedPathFound = primaryForwardPath != null
                && FlowPathBuilder.arePathsOverlapped(potentialPath.getForward(), primaryForwardPath)
                || primaryReversePath != null
                && FlowPathBuilder.arePathsOverlapped(potentialPath.getReverse(), primaryReversePath);
        if (overlappingProtectedPathFound) {
            // Update the status here as no reroute is going to be performed for the protected.
            FlowPath protectedForwardPath = tmpFlowCopy.getProtectedForwardPath();
            if (protectedForwardPath != null) {
                protectedForwardPath.setStatus(FlowPathStatus.INACTIVE);
            }

            FlowPath protectedReversePath = tmpFlowCopy.getProtectedReversePath();
            if (protectedReversePath != null) {
                protectedReversePath.setStatus(FlowPathStatus.INACTIVE);
            }

            FlowStatus flowStatus = tmpFlowCopy.computeFlowStatus();
            if (flowStatus != tmpFlowCopy.getStatus()) {
                service.getDashboardLogger().onFlowStatusUpdate(flowId, flowStatus);
                service.getFlowRepository().updateStatus(flowId, flowStatus);
            }

            responseBuilder.flow(tmpFlowCopy);
            responseBuilder.allocatePathStatus(AllocatePathStatus.FOUND_OVERLAPPING_PATH);
        } else {
            FlowPathPair oldPaths = FlowPathPair.builder()
                    .forward(tmpFlowCopy.getProtectedForwardPath())
                    .reverse(tmpFlowCopy.getProtectedReversePath())
                    .build();

            boolean newPathFound = isNotSamePath(potentialPath, oldPaths);
            if (newPathFound || recreateIfSamePath) {
                if (!newPathFound) {
                    log.debug("Found the same protected path for flow {}. Proceed with recreating it", flowId);
                }

                FlowPathPair createdPaths = service.getTransactionManager().doInTransaction(() -> {
                    log.debug("Allocating resources for a new protected path of flow {}", flowId);
                    Flow flow = service.getFlow(flowId);
                    FlowResources flowResources = service.getResourcesManager().allocateFlowResources(flow);
                    log.debug("Resources have been allocated: {}", flowResources);
                    responseBuilder.flowResources(flowResources);

                    List<FlowPath> pathsToReuse
                            = Lists.newArrayList(flow.getProtectedForwardPath(), flow.getProtectedReversePath());
                    pathsToReuse.addAll(rejectedPaths.stream()
                            .map(flow::getPath)
                            .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
                            .collect(Collectors.toList()));
                    FlowPathPair newPaths = createFlowPathPair(
                            service, flow, pathsToReuse, potentialPath, flowResources, false);
                    log.debug("New protected path has been created: {}", newPaths);
                    responseBuilder.forwardPathId(newPaths.getForward().getPathId());
                    responseBuilder.reversePathId(newPaths.getReverse().getPathId());
                    return newPaths;
                });

                service.getFlowPathRepository().detach(createdPaths.getForward());
                service.getFlowPathRepository().detach(createdPaths.getReverse());

                responseBuilder.flow(tmpFlowCopy);
                responseBuilder.newPaths(createdPaths);
                responseBuilder.allocatePathStatus(AllocatePathStatus.FOUND_NEW_PATH);

            } else {
                responseBuilder.allocatePathStatus(AllocatePathStatus.FOUND_SAME_PATH);
                log.info("Found the same protected path for flow {}. Skipped creating of primary path", flowId);
            }
        }

        log.warn("HSTIME reroute resource allocation protected (not full, just apply) "
                + (System.currentTimeMillis() - time));

        return responseBuilder.build();
    }

    @Override
    protected void onFailure(SpeakerWorkerService service) {

    }
}
