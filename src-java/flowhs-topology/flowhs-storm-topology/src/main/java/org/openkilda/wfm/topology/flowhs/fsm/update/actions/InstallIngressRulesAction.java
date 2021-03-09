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
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.FlowLoopOperation;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilderFactory;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerInstallSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

@Slf4j
public class InstallIngressRulesAction extends FlowProcessingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    private final FlowCommandBuilderFactory commandBuilderFactory;

    public InstallIngressRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        commandBuilderFactory = new FlowCommandBuilderFactory(resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        String flowId = stateMachine.getFlowId();
        RequestedFlow requestedFlow = stateMachine.getTargetFlow();
        Flow flow = getFlow(flowId);

        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(requestedFlow.getFlowEncapsulationType());

        FlowPath newPrimaryForward = getFlowPath(flow, stateMachine.getNewPrimaryForwardPath());
        FlowPath newPrimaryReverse = getFlowPath(flow, stateMachine.getNewPrimaryReversePath());

        SpeakerRequestBuildContext speakerContext = buildBaseSpeakerContextForInstall(
                newPrimaryForward.getSrcSwitchId(), newPrimaryReverse.getSrcSwitchId());
        Collection<FlowSegmentRequestFactory> commands = new ArrayList<>();
        switch (stateMachine.getEndpointUpdate()) {
            case SOURCE:
                speakerContext.getForward().setUpdateMeter(false);
                commands.addAll(getCommandsForSourceUpdate(commandBuilder, stateMachine, flow,
                        newPrimaryForward, newPrimaryReverse, speakerContext));
                break;
            case DESTINATION:
                speakerContext.getReverse().setUpdateMeter(false);
                commands.addAll(getCommandsForDestinationUpdate(commandBuilder, stateMachine, flow,
                        newPrimaryForward, newPrimaryReverse, speakerContext));
                break;
            case BOTH:
                speakerContext.getForward().setUpdateMeter(false);
                speakerContext.getReverse().setUpdateMeter(false);
                if (stateMachine.getFlowLoopOperation() == FlowLoopOperation.NONE) {
                    commands.addAll(commandBuilder.buildIngressOnly(stateMachine.getCommandContext(), flow,
                            newPrimaryForward, newPrimaryReverse, speakerContext));
                } else {
                    commands.addAll(commandBuilder.buildIngressOnly(stateMachine.getCommandContext(), flow,
                            newPrimaryForward, newPrimaryReverse, speakerContext).stream()
                            .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory)
                            .collect(Collectors.toList()));
                }
                break;
            default:
                commands.addAll(commandBuilder.buildIngressOnly(
                        stateMachine.getCommandContext(), flow, newPrimaryForward, newPrimaryReverse, speakerContext));
                break;
        }

        // Installation of ingress rules for protected paths is skipped. These paths are activated on swap.

        stateMachine.clearPendingAndRetriedAndFailedCommands();

        if (commands.isEmpty()) {
            stateMachine.saveActionToHistory("No need to install ingress rules");
            stateMachine.fire(Event.INGRESS_IS_SKIPPED);
        } else {
            SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                    stateMachine.getCarrier(), commands, stateMachine.getIngressCommands());
            stateMachine.getIngressCommands().forEach(
                    (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

            stateMachine.saveActionToHistory("Commands for installing ingress rules have been sent");
        }
    }

    private Collection<FlowSegmentRequestFactory> getCommandsForSourceUpdate(
            FlowCommandBuilder commandBuilder, FlowUpdateFsm stateMachine, Flow flow,
            FlowPath newPrimaryForward, FlowPath newPrimaryReverse, SpeakerRequestBuildContext speakerContext) {
        switch (stateMachine.getFlowLoopOperation()) {
            case NONE:
                return commandBuilder.buildIngressOnlyOneDirection(
                        stateMachine.getCommandContext(), flow, newPrimaryForward, newPrimaryReverse,
                        speakerContext.getForward());
            case CREATE:
                return commandBuilder.buildIngressOnlyOneDirection(
                        stateMachine.getCommandContext(), flow, newPrimaryForward, newPrimaryReverse,
                        speakerContext.getForward()).stream()
                        .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory
                                || f instanceof TransitFlowLoopSegmentRequestFactory)
                        .collect(Collectors.toList());
            case DELETE:
            default:
                // No rules installation required
                return Collections.emptyList();
        }
    }

    private Collection<FlowSegmentRequestFactory> getCommandsForDestinationUpdate(
            FlowCommandBuilder commandBuilder, FlowUpdateFsm stateMachine, Flow flow,
            FlowPath newPrimaryForward, FlowPath newPrimaryReverse, SpeakerRequestBuildContext speakerContext) {
        switch (stateMachine.getFlowLoopOperation()) {
            case NONE:
                return commandBuilder.buildIngressOnlyOneDirection(
                        stateMachine.getCommandContext(), flow, newPrimaryReverse, newPrimaryForward,
                        speakerContext.getReverse());
            case CREATE:
                return commandBuilder.buildIngressOnlyOneDirection(
                        stateMachine.getCommandContext(), flow, newPrimaryReverse, newPrimaryForward,
                        speakerContext.getReverse()).stream()
                        .filter(f -> f instanceof IngressFlowLoopSegmentRequestFactory
                                || f instanceof TransitFlowLoopSegmentRequestFactory)
                        .collect(Collectors.toList());
            case DELETE:
            default:
                // No rules installation required
                return Collections.emptyList();
        }
    }
}
