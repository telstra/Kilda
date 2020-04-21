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
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
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

        SpeakerRequestBuildContext speakerRequestBuildContext = createSpeakerRequestBuildContextWithServer42Fields(
                newPrimaryForward.getSrcSwitch().getSwitchId(), newPrimaryReverse.getSrcSwitch().getSwitchId());

        Collection<FlowSegmentRequestFactory> commands = new ArrayList<>(
                commandBuilder.buildIngressOnly(
                        stateMachine.getCommandContext(), flow, newPrimaryForward, newPrimaryReverse,
                        speakerRequestBuildContext));

        // Installation of ingress rules for protected paths is skipped. These paths are activated on swap.

        SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), commands, stateMachine.getIngressCommands());
        stateMachine.getIngressCommands().forEach(
                (key, value) -> stateMachine.getPendingCommands().put(key, value.getSwitchId()));
        stateMachine.getRetriedCommands().clear();

        if (commands.isEmpty()) {
            stateMachine.saveActionToHistory("No need to install ingress rules");
            stateMachine.fire(Event.INGRESS_IS_SKIPPED);
        } else {
            stateMachine.saveActionToHistory("Commands for installing ingress rules have been sent");
        }
    }
}
