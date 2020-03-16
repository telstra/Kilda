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

package org.openkilda.wfm.topology.flowhs.fsm.delete.actions;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.FlowPathSnapshot;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.share.service.SharedOfFlowManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowRuleRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteContext;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.State;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerRemoveSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class RemoveRulesAction extends BaseFlowRuleRemovalAction<FlowDeleteFsm, State, Event, FlowDeleteContext> {
    private final FlowResourcesManager resourcesManager;

    public RemoveRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
        this.resourcesManager = resourcesManager;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowDeleteContext context, FlowDeleteFsm stateMachine) {
        Flow flow = getFlow(stateMachine.getFlowId());

        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(flow.getEncapsulationType());
        Collection<FlowSegmentRequestFactory> commands = new ArrayList<>();

        SharedOfFlowManager sharedOfFlowManager = makeSharedOfFlowManager(flow);

        Set<PathId> processed = new HashSet<>();
        for (FlowPath path : flow.getPaths()) {
            PathId pathId = path.getPathId();
            if (processed.add(pathId)) {
                FlowPath oppositePath = flow.getOppositePathId(pathId).filter(oppPathId -> !pathId.equals(oppPathId))
                        .flatMap(flow::getPath).orElse(null);
                if (oppositePath != null) {
                    processed.add(oppositePath.getPathId());
                }

                FlowResources pathPairResources;
                if (oppositePath != null) {
                    // FIXME - shared rules
                    pathPairResources = buildResources(flow, path, oppositePath);
                } else {
                    log.warn("No opposite path found for {}, trying to delete as unpaired path", pathId);
                    // FIXME - shared rules
                    pathPairResources = buildResources(flow, path, path);
                }

                stateMachine.getFlowResources().add(pathPairResources);
                commands.addAll(makeSpeakerRequests(
                        commandBuilder, sharedOfFlowManager, stateMachine.getCommandContext(),
                        flow, path, oppositePath, pathPairResources));
            }
        }

        SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), commands, stateMachine.getRemoveCommands());
        stateMachine.getPendingCommands().addAll(stateMachine.getRemoveCommands().keySet());

        stateMachine.saveActionToHistory("Remove commands for rules have been sent");
    }

    private FlowResources buildResources(Flow flow, FlowPath path, FlowPath oppositePath) {
        FlowPath forwardPath;
        FlowPath reversePath;

        if (path.isForward()) {
            forwardPath = path;
            reversePath = oppositePath;
        } else {
            forwardPath = oppositePath;
            reversePath = path;
        }

        EncapsulationResources encapsulationResources;
        if (!flow.isOneSwitchFlow()) {
            encapsulationResources = resourcesManager.getEncapsulationResources(
                    forwardPath.getPathId(), reversePath.getPathId(), flow.getEncapsulationType()).orElse(null);
        } else {
            encapsulationResources = null;
        }

        return FlowResources.builder()
                .unmaskedCookie(FlowSegmentCookieSchema.INSTANCE.getFlowEffectiveId(forwardPath.getCookie()))
                .forward(PathResources.builder()
                        .pathId(forwardPath.getPathId())
                        .meterId(forwardPath.getMeterId())
                        .encapsulationResources(encapsulationResources)
                        .build())
                .reverse(PathResources.builder()
                        .pathId(reversePath.getPathId())
                        .meterId(reversePath.getMeterId())
                        .encapsulationResources(encapsulationResources)
                        .build())
                .build();
    }

    private List<FlowSegmentRequestFactory> makeSpeakerRequests(
            FlowCommandBuilder commandBuilder, SharedOfFlowManager sharedOfFlowManager, CommandContext commandContext,
            Flow flow, FlowPath path, FlowPath oppositePath, FlowResources resources) {
        FlowPathSnapshot pathSnapshot = makeFlowPathOldSnapshot(
                sharedOfFlowManager, flow, path.getPathId(), extractPathResources(resources, path));
        FlowPathSnapshot oppositePathSnapshot = null;
        if (oppositePath != null) {
            oppositePathSnapshot = makeFlowPathOldSnapshot(
                    sharedOfFlowManager, flow, oppositePath.getPathId(), extractPathResources(resources, path));
        }
        return commandBuilder.buildAll(commandContext, flow, pathSnapshot, oppositePathSnapshot);
    }

    private PathResources extractPathResources(FlowResources resources, FlowPath path) {
        if (path.isForward()) {
            return resources.getForward();
        } else {
            return resources.getReverse();
        }
    }

    private boolean isRemoveCustomerPortSharedLldpCatchRule(Flow flow, FlowPath path) {
        boolean isForward = flow.isForward(path);
        return isFlowTheLastUserOfSharedLldpPortRule(flow.getFlowId(), path.getSrcSwitch().getSwitchId(),
                isForward ? flow.getSrcPort() : flow.getDestPort());
    }

    private boolean isRemoveCustomerPortSharedArpCatchRule(Flow flow, FlowPath path) {
        boolean isForward = flow.isForward(path);
        return isFlowTheLastUserOfSharedArpPortRule(flow.getFlowId(), path.getSrcSwitch().getSwitchId(),
                isForward ? flow.getSrcPort() : flow.getDestPort());
    }
}
