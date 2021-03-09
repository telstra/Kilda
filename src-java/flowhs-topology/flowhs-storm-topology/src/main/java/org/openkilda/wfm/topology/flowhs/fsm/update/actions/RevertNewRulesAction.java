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

import static org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.FlowLoopOperation.NONE;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.IngressFlowLoopSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.TransitFlowLoopSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowRuleRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerInstallSegmentEmitter;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerRemoveSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

@Slf4j
public class RevertNewRulesAction extends BaseFlowRuleRemovalAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {

    public RevertNewRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        String flowId = stateMachine.getFlowId();
        Flow flow = getFlow(flowId);

        log.debug("Abandoning all pending commands: {}", stateMachine.getPendingCommands());
        stateMachine.clearPendingAndRetriedAndFailedCommands();

        FlowEncapsulationType encapsulationType = stateMachine.getTargetFlow().getFlowEncapsulationType();
        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(encapsulationType);

        Collection<FlowSegmentRequestFactory> installCommands = new ArrayList<>();
        // Reinstall old ingress rules that may be overridden by new ingress.
        if (stateMachine.getOldPrimaryForwardPath() != null && stateMachine.getOldPrimaryReversePath() != null) {
            FlowPath oldForward = getFlowPath(flow, stateMachine.getOldPrimaryForwardPath());
            FlowPath oldReverse = getFlowPath(flow, stateMachine.getOldPrimaryReversePath());

            SpeakerRequestBuildContext speakerContext = buildBaseSpeakerContextForInstall(
                    oldForward.getSrcSwitchId(), oldReverse.getSrcSwitchId());
            installCommands.addAll(commandBuilder.buildIngressOnly(
                    stateMachine.getCommandContext(), flow, oldForward, oldReverse, speakerContext));
        }

        stateMachine.getIngressCommands().clear();  // need to clean previous requests
        SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), installCommands, stateMachine.getIngressCommands());
        stateMachine.getIngressCommands().forEach(
                (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

        CommandContext commandContext = stateMachine.getCommandContext();
        // Remove possible installed segments
        Collection<FlowSegmentRequestFactory> revertCommands = new ArrayList<>();
        if (stateMachine.getNewPrimaryForwardPath() != null && stateMachine.getNewPrimaryReversePath() != null) {
            FlowPath newForward = getFlowPath(flow, stateMachine.getNewPrimaryForwardPath());
            FlowPath newReverse = getFlowPath(flow, stateMachine.getNewPrimaryReversePath());
            if (stateMachine.getEndpointUpdate().isPartialUpdate()) {
                SpeakerRequestBuildContext speakerRequestBuildContext = getSpeakerRequestBuildContextForRemoval(
                        stateMachine, false);
                Flow oldFlow = RequestedFlowMapper.INSTANCE.toFlow(stateMachine.getOriginalFlow());
                switch (stateMachine.getEndpointUpdate()) {
                    case SOURCE:
                        switch (stateMachine.getFlowLoopOperation()) {
                            case NONE:
                                revertCommands.addAll(commandBuilder.buildIngressOnlyOneDirection(commandContext,
                                        flow, newForward, newReverse, speakerRequestBuildContext.getForward()));
                                revertCommands.addAll(commandBuilder.buildEgressOnlyOneDirection(commandContext,
                                        oldFlow, newReverse, newForward));
                                break;
                            case CREATE:
                                revertCommands.addAll(commandBuilder.buildAll(commandContext, flow,
                                        newForward, newReverse, speakerRequestBuildContext).stream()
                                        .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory
                                                || f instanceof TransitFlowLoopSegmentRequestFactory)
                                        .collect(Collectors.toList()));
                                break;
                            case DELETE:
                            default:
                                // No need to revert rules
                                break;
                        }
                        break;
                    case DESTINATION:
                        switch (stateMachine.getFlowLoopOperation()) {
                            case NONE:
                                revertCommands.addAll(commandBuilder.buildIngressOnlyOneDirection(commandContext,
                                        flow, newReverse, newForward, speakerRequestBuildContext.getReverse()));
                                revertCommands.addAll(commandBuilder.buildEgressOnlyOneDirection(commandContext,
                                        oldFlow, newForward, newReverse));
                                break;
                            case CREATE:
                                revertCommands.addAll(commandBuilder.buildAll(commandContext, flow,
                                        newForward, newReverse, speakerRequestBuildContext).stream()
                                        .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory
                                                || f instanceof TransitFlowLoopSegmentRequestFactory)
                                        .collect(Collectors.toList()));
                                break;
                            case DELETE:
                            default:
                                // No need to revert rules
                                break;
                        }
                        break;
                    default:
                        revertCommands.addAll(commandBuilder.buildIngressOnly(commandContext, flow, newForward,
                                newReverse, speakerRequestBuildContext));
                        revertCommands.addAll(commandBuilder.buildEgressOnly(commandContext, oldFlow, newForward,
                                newReverse));
                        break;
                }
            } else {

                revertCommands.addAll(commandBuilder.buildAll(
                        stateMachine.getCommandContext(), flow, newForward, newReverse,
                        getSpeakerRequestBuildContextForRemoval(stateMachine, true)));
            }
        }
        if (stateMachine.getNewProtectedForwardPath() != null && stateMachine.getNewProtectedReversePath() != null) {
            FlowPath newForward = getFlowPath(flow, stateMachine.getNewProtectedForwardPath());
            FlowPath newReverse = getFlowPath(flow, stateMachine.getNewProtectedReversePath());
            Flow oldFlow = RequestedFlowMapper.INSTANCE.toFlow(stateMachine.getOriginalFlow());

            if (stateMachine.getEndpointUpdate().isPartialUpdate()) {
                switch (stateMachine.getEndpointUpdate()) {
                    case SOURCE:
                        if (stateMachine.getFlowLoopOperation() == NONE) {
                            revertCommands.addAll(commandBuilder.buildEgressOnlyOneDirection(commandContext,
                                    oldFlow, newReverse, newForward));
                        }
                        break;
                    case DESTINATION:
                        if (stateMachine.getFlowLoopOperation() == NONE) {
                            revertCommands.addAll(commandBuilder.buildEgressOnlyOneDirection(commandContext,
                                    oldFlow, newForward, newReverse));
                        }
                        break;
                    default:
                        revertCommands.addAll(commandBuilder.buildEgressOnly(commandContext, oldFlow, newForward,
                                newReverse));
                        break;
                }
            } else {
                revertCommands.addAll(commandBuilder.buildAllExceptIngress(
                        stateMachine.getCommandContext(), flow, newForward, newReverse));
            }
        }

        stateMachine.getRemoveCommands().clear();
        SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), revertCommands, stateMachine.getRemoveCommands());
        stateMachine.getRemoveCommands().forEach(
                (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

        if (stateMachine.getPendingCommands().isEmpty()) {
            stateMachine.saveActionToHistory("No need to remove new rules or re-install original ingress rule");
            stateMachine.fire(Event.RULES_REMOVED);
        } else {
            stateMachine.saveActionToHistory(
                    "Commands for removing new rules and re-installing original ingress rule have been sent");
        }
    }

    private SpeakerRequestBuildContext getSpeakerRequestBuildContextForRemoval(FlowUpdateFsm stateMachine,
                                                                               boolean removeMeters) {
        RequestedFlow originalFlow = stateMachine.getOriginalFlow();
        RequestedFlow targetFlow = stateMachine.getTargetFlow();

        return buildSpeakerContextForRemovalIngressAndShared(targetFlow, originalFlow, removeMeters);
    }
}
