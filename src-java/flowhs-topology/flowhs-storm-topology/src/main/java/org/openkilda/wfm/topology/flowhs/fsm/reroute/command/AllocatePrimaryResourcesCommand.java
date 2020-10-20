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
import org.openkilda.model.PathId;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocatePrimaryResourcesResponse;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocatePrimaryResourcesResponse.AllocatePrimaryResourcesResponseBuilder;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.BaseAllocateResourcesResponse;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import com.google.common.collect.Lists;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public class AllocatePrimaryResourcesCommand extends BaseResourceAllocationCommand {

    public AllocatePrimaryResourcesCommand(@NonNull MessageContext messageContext, @NonNull UUID commandId,
                                           CommandContext commandContext, FlowRerouteContext context, String flowId,
                                           FlowEncapsulationType newEncapsulationType, boolean recreateIfSamePath,
                                           Collection<PathId> rejectedPaths) {
        super(messageContext, commandId, commandContext, context, flowId, newEncapsulationType, recreateIfSamePath,
                rejectedPaths);
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

        log.debug("Finding a new primary path for flow {}", flowId);
        GetPathsResult potentialPath;
        if (context.isIgnoreBandwidth()) {
            boolean originalIgnoreBandwidth = tmpFlowCopy.isIgnoreBandwidth();
            tmpFlowCopy.setIgnoreBandwidth(true);
            potentialPath = service.getPathComputer().getPath(tmpFlowCopy,
                    getBackUpStrategies(tmpFlowCopy.getPathComputationStrategy()));
            tmpFlowCopy.setIgnoreBandwidth(originalIgnoreBandwidth);
        } else {
            potentialPath = service.getPathComputer().getPath(tmpFlowCopy, tmpFlowCopy.getPathIds(),
                    getBackUpStrategies(tmpFlowCopy.getPathComputationStrategy()));
        }

        AllocatePrimaryResourcesResponseBuilder responseBuilder = AllocatePrimaryResourcesResponse.builder()
                .messageContext(messageContext)
                .commandId(commandId)
                .newPrimaryPathComputationStrategy(potentialPath.getUsedStrategy());

        FlowPathPair oldPaths = FlowPathPair.builder()
                .forward(tmpFlowCopy.getForwardPath())
                .reverse(tmpFlowCopy.getReversePath())
                .build();
        boolean newPathFound = isNotSamePath(potentialPath, oldPaths);
        if (newPathFound || recreateIfSamePath) {
            if (!newPathFound) {
                log.debug("Found the same primary path for flow {}. Proceed with recreating it", flowId);
            }

            FlowPathPair createdPaths = service.getTransactionManager().doInTransaction(() -> {
                log.debug("Allocating resources for a new primary path of flow {}", flowId);
                Flow flow = service.getFlow(flowId);
                FlowResources flowResources = service.getResourcesManager().allocateFlowResources(flow);
                log.debug("Resources have been allocated: {}", flowResources);
                responseBuilder.flowResources(flowResources);

                List<FlowPath> pathsToReuse = Lists.newArrayList(flow.getForwardPath(), flow.getReversePath());
                pathsToReuse.addAll(rejectedPaths.stream()
                        .map(flow::getPath)
                        .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
                        .collect(Collectors.toList()));
                FlowPathPair newPaths = createFlowPathPair(service, flow, pathsToReuse, potentialPath,
                        flowResources, context.isIgnoreBandwidth());
                log.debug("New primary path has been created: {}", newPaths);
                responseBuilder.forwardPathId(newPaths.getForward().getPathId());
                responseBuilder.reversePathId(newPaths.getReverse().getPathId());
                return newPaths;
            });
            service.getFlowPathRepository().detach(createdPaths.getForward());
            service.getFlowPathRepository().detach(createdPaths.getReverse());

            responseBuilder.flow(tmpFlowCopy);
            responseBuilder.newPaths(createdPaths);
        } else {
            log.info("Found the same primary path for flow {}. Skipped creating of primary path", flowId);
            // TODO hahdle it
        }
        log.warn("HSTIME reroute resource allocation primary (not full, just apply) "
                + (System.currentTimeMillis() - time));
        return responseBuilder.build();
    }

    @Override
    protected void onFailure(SpeakerWorkerService service) {

    }
}
