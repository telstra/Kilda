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
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
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
import org.openkilda.wfm.topology.flowhs.utils.SpeakerRemoveSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;

@Slf4j
public class RemoveOldRulesAction extends BaseFlowRuleRemovalAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {

    public RemoveOldRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        FlowEncapsulationType oldEncapsulationType = stateMachine.getOriginalFlow().getFlowEncapsulationType();
        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(oldEncapsulationType);

        Flow flow = RequestedFlowMapper.INSTANCE.toFlow(stateMachine.getOriginalFlow());
        FlowPath oldPrimaryForward = getFlowPath(stateMachine.getOldPrimaryForwardPath());
        oldPrimaryForward.setFlow(flow);
        FlowPath oldPrimaryReverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
        oldPrimaryReverse.setFlow(flow);
        Collection<FlowSegmentRequestFactory> commands = new ArrayList<>(commandBuilder.buildAll(
                stateMachine.getCommandContext(), flow, oldPrimaryForward, oldPrimaryReverse,
                getSpeakerRequestBuildContext(stateMachine)));

        if (stateMachine.getOldProtectedForwardPath() != null && stateMachine.getOldProtectedReversePath() != null) {
            FlowPath oldForward = getFlowPath(stateMachine.getOldProtectedForwardPath());
            FlowPath oldReverse = getFlowPath(stateMachine.getOldProtectedReversePath());
            commands.addAll(commandBuilder.buildAllExceptIngress(
                    stateMachine.getCommandContext(), flow, oldForward, oldReverse));
        }

        SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), commands, stateMachine.getRemoveCommands());
        stateMachine.getRemoveCommands().forEach(
                (key, value) -> stateMachine.getPendingCommands().put(key, value.getSwitchId()));
        stateMachine.getRetriedCommands().clear();

        stateMachine.saveActionToHistory("Remove commands for old rules have been sent");
    }

    private SpeakerRequestBuildContext getSpeakerRequestBuildContext(FlowUpdateFsm stateMachine) {
        RequestedFlow originalFlow = stateMachine.getOriginalFlow();
        RequestedFlow targetFlow = stateMachine.getTargetFlow();

        SpeakerRequestBuildContext context = SpeakerRequestBuildContext.builder()
                .removeCustomerPortRule(removeForwardCustomerPortSharedCatchRule(originalFlow, targetFlow))
                .removeOppositeCustomerPortRule(removeReverseCustomerPortSharedCatchRule(originalFlow, targetFlow))
                .removeCustomerPortLldpRule(removeForwardSharedLldpRule(originalFlow, targetFlow))
                .removeOppositeCustomerPortLldpRule(removeReverseSharedLldpRule(originalFlow, targetFlow))
                .removeCustomerPortArpRule(removeForwardSharedArpRule(originalFlow, targetFlow))
                .removeOppositeCustomerPortArpRule(removeReverseSharedArpRule(originalFlow, targetFlow))
                .build();
        return fillServer42Fields(context, originalFlow.getSrcSwitch(), originalFlow.getDestSwitch());
    }
}
