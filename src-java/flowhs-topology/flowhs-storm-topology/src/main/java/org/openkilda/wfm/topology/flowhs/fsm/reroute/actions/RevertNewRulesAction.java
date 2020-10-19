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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowRuleRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerInstallSegmentEmitter;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerRemoveSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class RevertNewRulesAction extends BaseFlowRuleRemovalAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {

    public RevertNewRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        final long time = System.currentTimeMillis();
        abandonPendingCommands(stateMachine);

        Flow flow = getFlow(stateMachine.getFlowId());

        FlowEncapsulationType encapsulationType = stateMachine.getNewEncapsulationType() != null
                ? stateMachine.getNewEncapsulationType() : flow.getEncapsulationType();
        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(encapsulationType);

        Collection<FlowSegmentRequestFactory> installCommands = new ArrayList<>();
        // Reinstall old ingress rules that may be overridden by new ingress.
        if (stateMachine.getOldPrimaryForwardPath() != null && stateMachine.getOldPrimaryReversePath() != null) {
            FlowPath oldForward = getFlowPath(flow, stateMachine.getOldPrimaryForwardPath());
            FlowPath oldReverse = getFlowPath(flow, stateMachine.getOldPrimaryReversePath());

            SpeakerRequestBuildContext installContext = buildBaseSpeakerContextForInstall(
                    oldForward.getSrcSwitchId(), oldReverse.getSrcSwitchId());

            installCommands.addAll(commandBuilder.buildIngressOnly(
                    stateMachine.getCommandContext(), flow, oldForward, oldReverse, installContext));
        }

        stateMachine.getIngressCommands().clear();  // need to clean previous requests
        SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), installCommands, stateMachine.getIngressCommands());
        Map<UUID, SwitchId> pendingRequests = stateMachine.getPendingCommands();
        stateMachine.getIngressCommands().forEach((key, value) -> pendingRequests.put(key, value.getSwitchId()));

        // Remove possible installed flow segments
        Collection<FlowSegmentRequestFactory> removeCommands = new ArrayList<>();
        if (stateMachine.getNewPrimaryForwardPath() != null && stateMachine.getNewPrimaryReversePath() != null) {
            FlowPath newForward = getFlowPath(flow, stateMachine.getNewPrimaryForwardPath());
            FlowPath newReverse = getFlowPath(flow, stateMachine.getNewPrimaryReversePath());

            SpeakerRequestBuildContext speakerContext = buildSpeakerContextForRemovalIngressOnly(
                    newForward.getSrcSwitchId(), newReverse.getSrcSwitchId());

            removeCommands.addAll(commandBuilder.buildAll(
                    stateMachine.getCommandContext(), flow, newForward, newReverse, speakerContext));
        }
        if (stateMachine.getNewProtectedForwardPath() != null && stateMachine.getNewProtectedReversePath() != null) {
            FlowPath newForward = getFlowPath(flow, stateMachine.getNewProtectedForwardPath());
            FlowPath newReverse = getFlowPath(flow, stateMachine.getNewProtectedReversePath());
            removeCommands.addAll(commandBuilder.buildAllExceptIngress(
                    stateMachine.getCommandContext(), flow, newForward, newReverse));
        }

        SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), removeCommands, stateMachine.getRemoveCommands());
        stateMachine.getRemoveCommands().forEach((key, value) -> pendingRequests.put(key, value.getSwitchId()));

        stateMachine.getRetriedCommands().clear();
        log.warn("HSTIME reroute SHOULD NOT HAPPEN: revert new rules " + (System.currentTimeMillis() - time));
        stateMachine.saveActionToHistory(
                "Commands for removing new rules and re-installing original ingress rule have been sent");
    }

    private void abandonPendingCommands(FlowRerouteFsm stateMachine) {
        log.debug("Abandoning all pending commands: {}", stateMachine.getPendingCommands());
        stateMachine.getPendingCommands().clear();
    }
}
