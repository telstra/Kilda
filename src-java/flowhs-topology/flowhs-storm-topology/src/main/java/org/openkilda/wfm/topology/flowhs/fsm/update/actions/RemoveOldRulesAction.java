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

package org.openkilda.wfm.topology.flowhs.fsm.update.actions;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.IngressFlowLoopSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.TransitFlowLoopSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext.PathContext;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.PathSwappingRuleRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerRemoveSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class RemoveOldRulesAction extends
        PathSwappingRuleRemovalAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {

    public RemoveOldRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        FlowEncapsulationType oldEncapsulationType = stateMachine.getOriginalFlow().getFlowEncapsulationType();
        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(oldEncapsulationType);

        Collection<FlowSegmentRequestFactory> factories = new ArrayList<>();

        Flow originalFlow = getOriginalFlowWithPaths(stateMachine, stateMachine.getOriginalFlow());

        if (stateMachine.getEndpointUpdate().isPartialUpdate()) {
            SpeakerRequestBuildContext speakerContext = getSpeakerRequestBuildContext(stateMachine, false);
            FlowPath forward = getFlowPath(stateMachine.getOldPrimaryForwardPath());
            FlowPath reverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
            switch (stateMachine.getEndpointUpdate()) {
                case SOURCE:
                    factories.addAll(buildCommandsForSourceUpdate(commandBuilder, stateMachine, originalFlow,
                            forward, reverse, speakerContext));
                    break;
                case DESTINATION:
                    factories.addAll(buildCommandsForDestinationUpdate(commandBuilder, stateMachine, originalFlow,
                            forward, reverse, speakerContext));
                    break;
                case BOTH:
                default:
                    switch (stateMachine.getFlowLoopOperation()) {
                        case DELETE:
                            factories.addAll(commandBuilder.buildIngressOnly(stateMachine.getCommandContext(),
                                    originalFlow, forward, reverse, speakerContext).stream()
                                    .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory)
                                    .collect(Collectors.toList()));
                            break;
                        case CREATE:
                            // No rules removing required
                            break;
                        case NONE:
                        default:
                            factories.addAll(commandBuilder.buildIngressOnly(stateMachine.getCommandContext(),
                                    originalFlow, forward, reverse, speakerContext));
                            break;
                    }
                    break;
            }
        } else {
            SpeakerRequestBuildContext speakerContext = getSpeakerRequestBuildContext(stateMachine, true);
            if (stateMachine.getOldPrimaryForwardPath() != null) {
                FlowPath oldForward = getFlowPath(stateMachine.getOldPrimaryForwardPath());

                if (stateMachine.getOldPrimaryReversePath() != null) {
                    FlowPath oldReverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
                    factories.addAll(commandBuilder.buildAll(
                            stateMachine.getCommandContext(), originalFlow, oldForward, oldReverse,
                            speakerContext));
                } else {
                    factories.addAll(commandBuilder.buildAll(
                            stateMachine.getCommandContext(), originalFlow, oldForward, speakerContext));

                }
            } else if (stateMachine.getOldPrimaryReversePath() != null) {
                FlowPath oldReverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
                // swap contexts
                speakerContext.setForward(speakerContext.getReverse());
                speakerContext.setReverse(PathContext.builder().build());

                factories.addAll(commandBuilder.buildAll(
                        stateMachine.getCommandContext(), originalFlow, oldReverse, speakerContext));
            }

            if (stateMachine.getOldProtectedForwardPath() != null) {
                FlowPath oldForward = getFlowPath(stateMachine.getOldProtectedForwardPath());

                if (stateMachine.getOldProtectedReversePath() != null) {
                    FlowPath oldReverse = getFlowPath(stateMachine.getOldProtectedReversePath());
                    factories.addAll(commandBuilder.buildAllExceptIngress(
                            stateMachine.getCommandContext(), originalFlow, oldForward, oldReverse));
                } else {
                    factories.addAll(commandBuilder.buildAllExceptIngress(
                            stateMachine.getCommandContext(), originalFlow, oldForward));
                }
            } else if (stateMachine.getOldProtectedReversePath() != null) {
                FlowPath oldReverse = getFlowPath(stateMachine.getOldProtectedReversePath());
                factories.addAll(commandBuilder.buildAllExceptIngress(
                        stateMachine.getCommandContext(), originalFlow, oldReverse));
            }
        }

        stateMachine.clearPendingAndRetriedAndFailedCommands();

        if (factories.isEmpty()) {
            stateMachine.saveActionToHistory("No need to remove old rules");

            stateMachine.fire(Event.RULES_REMOVED);
        } else {
            SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                    stateMachine.getCarrier(), factories, stateMachine.getRemoveCommands());
            stateMachine.getRemoveCommands().forEach(
                    (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

            stateMachine.saveActionToHistory("Remove commands for old rules have been sent");
        }
    }

    private List<FlowSegmentRequestFactory> buildCommandsForSourceUpdate(
            FlowCommandBuilder commandBuilder, FlowUpdateFsm stateMachine, Flow flow,
            FlowPath forward, FlowPath reverse, SpeakerRequestBuildContext speakerContext) {
        switch (stateMachine.getFlowLoopOperation()) {
            case NONE:
                return commandBuilder.buildIngressOnlyOneDirection(
                        stateMachine.getCommandContext(),
                        flow, forward, reverse, speakerContext.getForward());
            case DELETE:
                return commandBuilder.buildAll(stateMachine.getCommandContext(),
                        flow, forward, reverse, speakerContext).stream()
                        .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory
                                || f instanceof TransitFlowLoopSegmentRequestFactory)
                        .collect(Collectors.toList());
            case CREATE:
            default:
                // No rules removing required
                return Collections.emptyList();
        }
    }

    private List<FlowSegmentRequestFactory> buildCommandsForDestinationUpdate(
            FlowCommandBuilder commandBuilder, FlowUpdateFsm stateMachine, Flow flow,
            FlowPath forward, FlowPath reverse, SpeakerRequestBuildContext speakerContext) {
        switch (stateMachine.getFlowLoopOperation()) {
            case NONE:
                return commandBuilder.buildIngressOnlyOneDirection(
                        stateMachine.getCommandContext(),
                        flow, reverse, forward, speakerContext.getReverse());
            case DELETE:
                return commandBuilder.buildAll(stateMachine.getCommandContext(),
                        flow, forward, reverse, speakerContext).stream()
                        .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory
                                || f instanceof TransitFlowLoopSegmentRequestFactory)
                        .collect(Collectors.toList());
            case CREATE:
            default:
                // No rules removing required
                return Collections.emptyList();
        }
    }

    private SpeakerRequestBuildContext getSpeakerRequestBuildContext(FlowUpdateFsm stateMachine, boolean removeMeters) {
        RequestedFlow originalFlow = stateMachine.getOriginalFlow();
        RequestedFlow targetFlow = stateMachine.getTargetFlow();

        return buildSpeakerContextForRemovalIngressAndShared(originalFlow, targetFlow, removeMeters);
    }
}
