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
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilderFactory;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerInstallSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

@Slf4j
public class InstallNonIngressRulesAction
        extends FlowProcessingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    private final FlowCommandBuilderFactory commandBuilderFactory;

    public InstallNonIngressRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        commandBuilderFactory = new FlowCommandBuilderFactory(resourcesManager);
    }

    @Override
    protected void perform(State from, State to,
                           Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        String flowId = stateMachine.getFlowId();
        RequestedFlow requestedFlow = stateMachine.getTargetFlow();
        Flow flow = getFlow(flowId);

        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(requestedFlow.getFlowEncapsulationType());

        // primary path
        FlowPath newPrimaryForward = getFlowPath(flow, stateMachine.getNewPrimaryForwardPath());
        FlowPath newPrimaryReverse = getFlowPath(flow, stateMachine.getNewPrimaryReversePath());
        Collection<FlowSegmentRequestFactory> commands = buildCommands(commandBuilder, stateMachine,
                flow, newPrimaryForward, newPrimaryReverse);

        // protected path
        if (stateMachine.getNewProtectedForwardPath() != null && stateMachine.getNewProtectedReversePath() != null) {
            FlowPath newProtectedForward = getFlowPath(flow, stateMachine.getNewProtectedForwardPath());
            FlowPath newProtectedReverse = getFlowPath(flow, stateMachine.getNewProtectedReversePath());
            commands.addAll(buildCommands(commandBuilder, stateMachine, flow,
                    newProtectedForward, newProtectedReverse));
        }

        stateMachine.clearPendingAndRetriedAndFailedCommands();

        if (commands.isEmpty()) {
            stateMachine.saveActionToHistory("No need to install non ingress rules");
            stateMachine.fire(Event.RULES_INSTALLED);
        } else {
            // emitting
            SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                    stateMachine.getCarrier(), commands, stateMachine.getNonIngressCommands());
            stateMachine.getNonIngressCommands().forEach(
                    (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

            stateMachine.saveActionToHistory("Commands for installing non ingress rules have been sent");
        }
    }

    private Collection<FlowSegmentRequestFactory> buildCommands(FlowCommandBuilder commandBuilder,
                                                                FlowUpdateFsm stateMachine, Flow flow,
                                                                FlowPath path, FlowPath oppositePath) {
        CommandContext context = stateMachine.getCommandContext();
        switch (stateMachine.getEndpointUpdate()) {
            case BOTH:
                return new ArrayList<>(commandBuilder.buildEgressOnly(context, flow, path, oppositePath));
            case SOURCE:
                return buildCommandsForSourceUpdate(commandBuilder, stateMachine, flow, path, oppositePath, context);
            case DESTINATION:
                return buildCommandsForDestinationUpdate(commandBuilder, stateMachine, flow, path, oppositePath,
                        context);
            default:
                return new ArrayList<>(commandBuilder.buildAllExceptIngress(context, flow, path, oppositePath));

        }
    }

    private Collection<FlowSegmentRequestFactory> buildCommandsForSourceUpdate(
            FlowCommandBuilder commandBuilder, FlowUpdateFsm stateMachine, Flow flow,
            FlowPath path, FlowPath oppositePath, CommandContext context) {
        switch (stateMachine.getFlowLoopOperation()) {
            case NONE:
                return new ArrayList<>(commandBuilder
                        .buildEgressOnlyOneDirection(context, flow, oppositePath, path));
            case CREATE:
            case DELETE:
            default:
                // No rules installation required
                return Collections.emptyList();
        }
    }

    private Collection<FlowSegmentRequestFactory> buildCommandsForDestinationUpdate(
            FlowCommandBuilder commandBuilder, FlowUpdateFsm stateMachine, Flow flow,
            FlowPath path, FlowPath oppositePath, CommandContext context) {
        switch (stateMachine.getFlowLoopOperation()) {
            case NONE:
                return new ArrayList<>(commandBuilder
                        .buildEgressOnlyOneDirection(context, flow, path, oppositePath));
            case CREATE:
            case DELETE:
            default:
                // No rules installation required
                return Collections.emptyList();
        }
    }
}
