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
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.CompleteFlowPathRemovalResponse;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.CompleteFlowPathRemovalResponse.CompleteFlowPathRemovalResponseBuilder;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.UUID;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public class CompleteFlowPathRemovalCommand extends DbCommand implements ICommand<SpeakerWorkerService> {
    String flowId;

    PathId oldPrimaryForwardPathId;
    PathId oldPrimaryReversePathId;
    PathId oldProtectedForwardPathId;
    PathId oldProtectedReversePathId;
    Collection<PathId> rejectedPaths;

    public CompleteFlowPathRemovalCommand(
            @NonNull MessageContext messageContext, @NonNull UUID commandId, String flowId,
            PathId oldPrimaryForwardPathId, PathId oldPrimaryReversePathId, PathId oldProtectedForwardPathId,
            PathId oldProtectedReversePathId, Collection<PathId> rejectedPaths) {
        super(messageContext, commandId);
        this.flowId = flowId;
        this.oldPrimaryForwardPathId = oldPrimaryForwardPathId;
        this.oldPrimaryReversePathId = oldPrimaryReversePathId;
        this.oldProtectedForwardPathId = oldProtectedForwardPathId;
        this.oldProtectedReversePathId = oldProtectedReversePathId;
        this.rejectedPaths = rejectedPaths;
    }

    @Override
    public void apply(SpeakerWorkerService service) {
        final long time = System.currentTimeMillis();
        CompleteFlowPathRemovalResponseBuilder responseBuilder = CompleteFlowPathRemovalResponse.builder()
                .messageContext(messageContext)
                .commandId(commandId);

        Flow flow = service.getFlow(flowId);
        removeOldPrimaryFlowPaths(service, responseBuilder, flow);
        removeOldProtectedFlowPaths(service, responseBuilder, flow);
        removeRejectedFlowPaths(service, responseBuilder, flow);

        service.getFlowRepository().detach(flow);
        responseBuilder.flow(flow);

        service.sendResponse(responseBuilder.build());

        log.warn("HSTIME reroute TOTAL complete flow path removing " + (System.currentTimeMillis() - time));
    }

    private void removeOldPrimaryFlowPaths(
            SpeakerWorkerService service, CompleteFlowPathRemovalResponseBuilder responseBuilder, Flow flow) {
        if (oldPrimaryForwardPathId != null || oldPrimaryReversePathId != null) {
            final long time = System.currentTimeMillis();
            FlowPath oldPrimaryForward = service.getFlowPathRepository().remove(oldPrimaryForwardPathId).orElse(null);
            FlowPath oldPrimaryReverse = service.getFlowPathRepository().remove(oldPrimaryReversePathId).orElse(null);
            log.warn("HSTIME reroute complete primary flow path removing " + (System.currentTimeMillis() - time));

            FlowPathPair removedPaths = null;
            if (oldPrimaryForward != null) {
                if (oldPrimaryReverse != null) {
                    log.debug("Removed the flow paths {} / {}", oldPrimaryForward, oldPrimaryReverse);
                    removedPaths = new FlowPathPair(oldPrimaryForward, oldPrimaryReverse);
                    updateIslsForFlowPath(service, removedPaths.getForward(), removedPaths.getReverse());
                } else {
                    log.debug("Removed the flow path {} (no reverse pair)", oldPrimaryForward);
                    // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                    removedPaths = new FlowPathPair(oldPrimaryForward, oldPrimaryForward);
                    updateIslsForFlowPath(service, removedPaths.getForward());
                }
            } else if (oldPrimaryReverse != null) {
                log.debug("Removed the flow path {} (no forward pair)", oldPrimaryReverse);
                // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                removedPaths = new FlowPathPair(oldPrimaryReverse, oldPrimaryReverse);
                updateIslsForFlowPath(service, removedPaths.getReverse());
            }
            if (removedPaths != null) {
                service.getFlowPathRepository().detach(removedPaths.getForward());
                service.getFlowPathRepository().detach(removedPaths.getReverse());
                responseBuilder.removedPrimaryPaths(removedPaths);
            }
        }
    }

    private void removeOldProtectedFlowPaths(
            SpeakerWorkerService service, CompleteFlowPathRemovalResponseBuilder responseBuilder, Flow flow) {
        if (oldProtectedForwardPathId != null || oldProtectedReversePathId != null) {
            final long time = System.currentTimeMillis();
            FlowPath oldProtectedForward = service.getFlowPathRepository()
                    .remove(oldProtectedForwardPathId).orElse(null);
            FlowPath oldProtectedReverse = service.getFlowPathRepository()
                    .remove(oldProtectedReversePathId).orElse(null);
            log.warn("HSTIME reroute complete protected flow path removing " + (System.currentTimeMillis() - time));

            FlowPathPair removedPaths = null;
            if (oldProtectedForward != null) {
                if (oldProtectedReverse != null) {
                    log.debug("Removed the flow paths {} / {}", oldProtectedForward, oldProtectedReverse);
                    removedPaths = new FlowPathPair(oldProtectedForward, oldProtectedReverse);
                    updateIslsForFlowPath(service, removedPaths.getForward(), removedPaths.getReverse());
                } else {
                    log.debug("Removed the flow path {} (no reverse pair)", oldProtectedForward);
                    // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                    removedPaths = new FlowPathPair(oldProtectedForward, oldProtectedForward);
                    updateIslsForFlowPath(service, removedPaths.getForward());
                }
            } else if (oldProtectedReverse != null) {
                log.debug("Removed the flow path {} (no forward pair)", oldProtectedReverse);
                // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                removedPaths = new FlowPathPair(oldProtectedReverse, oldProtectedReverse);
                updateIslsForFlowPath(service, removedPaths.getReverse());
            }
            if (removedPaths != null) {
                service.getFlowPathRepository().detach(removedPaths.getForward());
                service.getFlowPathRepository().detach(removedPaths.getReverse());
                responseBuilder.removedPrimaryPaths(removedPaths);
            }
        }
    }

    private void removeRejectedFlowPaths(
            SpeakerWorkerService service, CompleteFlowPathRemovalResponseBuilder responseBuilder, Flow flow) {
        final long time = System.currentTimeMillis();
        rejectedPaths
                .forEach(pathId ->
                        service.getFlowPathRepository().remove(pathId)
                                .ifPresent(flowPath -> {
                                    updateIslsForFlowPath(service, flowPath);
                                    service.getFlowPathRepository().detach(flowPath);
                                    responseBuilder.removedRejectedPath(flowPath);
                                }));
        log.warn("HSTIME reroute complete rejected flow path removing " + (System.currentTimeMillis() - time));
    }

    private void updateIslsForFlowPath(SpeakerWorkerService service, FlowPath... paths) {
        for (FlowPath path : paths) {
            path.getSegments().forEach(pathSegment ->
                    service.getTransactionManager().doInTransaction(() -> {
                        updateAvailableBandwidth(service, pathSegment.getSrcSwitchId(), pathSegment.getSrcPort(),
                                pathSegment.getDestSwitchId(), pathSegment.getDestPort());
                    }));
        }
    }

    private void updateAvailableBandwidth(SpeakerWorkerService service, SwitchId srcSwitch, int srcPort,
                                          SwitchId dstSwitch, int dstPort) {
        long usedBandwidth = service.getFlowPathRepository().getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);
        log.debug("Updating ISL {}_{} - {}_{} with used bandwidth {}", srcSwitch, srcPort, dstSwitch, dstPort,
                usedBandwidth);
        service.getIslRepository().updateAvailableBandwidth(srcSwitch, srcPort, dstSwitch, dstPort, usedBandwidth);
    }
}
