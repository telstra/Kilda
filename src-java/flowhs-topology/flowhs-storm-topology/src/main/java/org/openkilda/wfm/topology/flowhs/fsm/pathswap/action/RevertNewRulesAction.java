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

package org.openkilda.wfm.topology.flowhs.fsm.pathswap.action;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowRuleRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapContext;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.State;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerInstallSegmentEmitter;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerRemoveSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;

@Slf4j
public class RevertNewRulesAction
        extends BaseFlowRuleRemovalAction<FlowPathSwapFsm, State, Event, FlowPathSwapContext> {

    public RevertNewRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager, resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowPathSwapContext context,
                           FlowPathSwapFsm stateMachine) {
        String flowId = stateMachine.getFlowId();
        Flow flow = getFlow(flowId);

        log.debug("Abandoning all pending commands: {}", stateMachine.getPendingCommands());
        stateMachine.clearPendingAndRetriedAndFailedCommands();

        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(flow.getEncapsulationType());

        Collection<FlowSegmentRequestFactory> installCommands = new ArrayList<>();
        // Reinstall old ingress rules that may be overridden by new ingress.
        SpeakerRequestBuildContext installContext = buildBaseSpeakerContextForInstall(
                flow.getSrcSwitchId(), flow.getDestSwitchId());
        installCommands.addAll(commandBuilder.buildIngressOnly(
                stateMachine.getCommandContext(), flow, flow.getForwardPath(), flow.getReversePath(), installContext));

        stateMachine.getIngressCommands().clear();  // need to clean previous requests
        SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), installCommands, stateMachine.getIngressCommands());
        stateMachine.getIngressCommands().forEach(
                (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

        // Remove possible installed segments
        Collection<FlowSegmentRequestFactory> removeCommands = new ArrayList<>();
        SpeakerRequestBuildContext removeContext = buildSpeakerContextForRemovalIngressOnly(
                flow.getSrcSwitchId(), flow.getDestSwitchId());

        removeCommands.addAll(commandBuilder.buildIngressOnly(
                stateMachine.getCommandContext(), flow, flow.getProtectedForwardPath(),
                flow.getProtectedReversePath(), removeContext));

        stateMachine.getRemoveCommands().clear();
        SpeakerRemoveSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), removeCommands, stateMachine.getRemoveCommands());
        stateMachine.getRemoveCommands().forEach(
                (key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

        stateMachine.saveActionToHistory(
                "Commands for removing new rules and re-installing original ingress rule have been sent");
    }
}
