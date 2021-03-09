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

package org.openkilda.wfm.topology.flowhs.fsm.delete.actions;

import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext.PathContext;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

        Set<PathId> protectedPaths = Stream.of(flow.getProtectedForwardPathId(), flow.getProtectedReversePathId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<PathId> processed = new HashSet<>();
        for (FlowPath path : flow.getPaths()) {
            PathId pathId = path.getPathId();
            if (processed.add(pathId)) {
                FlowPath oppositePath = flow.getOppositePathId(pathId).filter(oppPathId -> !pathId.equals(oppPathId))
                        .flatMap(flow::getPath).orElse(null);
                if (oppositePath != null) {
                    processed.add(oppositePath.getPathId());
                }

                if (oppositePath != null) {
                    stateMachine.getFlowResources().add(buildResources(flow, path, oppositePath));

                    if (protectedPaths.contains(pathId)) {
                        commands.addAll(commandBuilder.buildAllExceptIngress(
                                stateMachine.getCommandContext(), flow, path, oppositePath));
                    } else {
                        SpeakerRequestBuildContext speakerRequestBuildContext = SpeakerRequestBuildContext.builder()
                                .forward(buildPathContext(flow, path))
                                .reverse(buildPathContext(flow, oppositePath))
                                .deleteOperation(true)
                                .build();
                        commands.addAll(commandBuilder.buildAll(stateMachine.getCommandContext(), flow,
                                path, oppositePath, speakerRequestBuildContext));
                    }
                } else {
                    log.warn("No opposite path found for {}, trying to delete as unpaired path", pathId);

                    stateMachine.getFlowResources().add(buildResources(flow, path, path));

                    if (protectedPaths.contains(pathId)) {
                        commands.addAll(commandBuilder.buildAllExceptIngress(
                                stateMachine.getCommandContext(), flow, path, null));
                    } else {
                        SpeakerRequestBuildContext speakerRequestBuildContext = SpeakerRequestBuildContext.builder()
                                .forward(buildPathContext(flow, path))
                                .reverse(PathContext.builder().build())
                                .deleteOperation(true)
                                .build();
                        commands.addAll(commandBuilder.buildAll(
                                stateMachine.getCommandContext(), flow, path, null, speakerRequestBuildContext));
                    }
                }
            }
        }

        stateMachine.clearPendingAndRetriedCommands();

        if (commands.isEmpty()) {
            stateMachine.saveActionToHistory("No need to remove rules");

            stateMachine.fire(Event.RULES_REMOVED);
        } else {
            SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                    stateMachine.getCarrier(), commands, stateMachine.getRemoveCommands());
            stateMachine.getPendingCommands().addAll(stateMachine.getRemoveCommands().keySet());

            stateMachine.saveActionToHistory("Remove commands for rules have been sent");
        }
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
                .unmaskedCookie(forwardPath.getCookie().getFlowEffectiveId())
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

    private boolean isRemoveCustomerPortSharedCatchRule(Flow flow, FlowPath path) {
        boolean isForward = flow.isForward(path);
        return isRemoveCustomerPortSharedCatchRule(flow.getFlowId(), path.getSrcSwitchId(),
                isForward ? flow.getSrcPort() : flow.getDestPort());
    }

    private boolean isRemoveServer42InputSharedRule(Flow flow, FlowPath path, boolean server42Rtt) {
        boolean isForward = flow.isForward(path);
        return server42Rtt && isFlowTheLastUserOfServer42InputSharedRule(
                flow.getFlowId(), path.getSrcSwitchId(),
                isForward ? flow.getSrcPort() : flow.getDestPort());
    }

    private boolean isRemoveCustomerPortSharedLldpCatchRule(Flow flow, FlowPath path) {
        boolean isForward = flow.isForward(path);
        return isFlowTheLastUserOfSharedLldpPortRule(flow.getFlowId(), path.getSrcSwitchId(),
                isForward ? flow.getSrcPort() : flow.getDestPort());
    }

    private boolean isRemoveCustomerPortSharedArpCatchRule(Flow flow, FlowPath path) {
        boolean isForward = flow.isForward(path);
        return isFlowTheLastUserOfSharedArpPortRule(flow.getFlowId(), path.getSrcSwitchId(),
                isForward ? flow.getSrcPort() : flow.getDestPort());
    }

    private boolean isRemoveOuterVlanMatchShareRule(Flow flow, FlowPath path) {
        FlowSideAdapter ingress = FlowSideAdapter.makeIngressAdapter(flow, path);
        return findOuterVlanMatchSharedRuleUsage(ingress.getEndpoint()).stream()
                .allMatch(entry -> flow.getFlowId().equals(entry.getFlowId()));
    }

    private PathContext buildPathContext(Flow flow, FlowPath path) {
        SwitchProperties properties = getSwitchProperties(path.getSrcSwitchId());
        boolean server42FlowRtt = isServer42FlowRttFeatureToggle() && properties.isServer42FlowRtt();

        return PathContext.builder()
                .removeCustomerPortRule(isRemoveCustomerPortSharedCatchRule(flow, path))
                .removeCustomerPortLldpRule(isRemoveCustomerPortSharedLldpCatchRule(flow, path))
                .removeCustomerPortArpRule(isRemoveCustomerPortSharedArpCatchRule(flow, path))
                .removeOuterVlanMatchSharedRule(isRemoveOuterVlanMatchShareRule(flow, path))
                .removeServer42InputRule(isRemoveServer42InputSharedRule(flow, path, server42FlowRtt))
                .removeServer42IngressRule(server42FlowRtt)
                .server42Port(properties.getServer42Port())
                .server42MacAddress(properties.getServer42MacAddress())
                .build();
    }
}
