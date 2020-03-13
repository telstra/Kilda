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

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilderFactory;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class RemoveOldRulesAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowCommandBuilderFactory commandBuilderFactory;

    public RemoveOldRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        commandBuilderFactory = new FlowCommandBuilderFactory(resourcesManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        FlowEncapsulationType encapsulationType = stateMachine.getOriginalEncapsulationType();
        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(encapsulationType);

        Collection<FlowSegmentRequestFactory> factories = new ArrayList<>();

        Flow flow = RequestedFlowMapper.INSTANCE.toFlow(stateMachine.getOriginalFlow());

        if (stateMachine.getOldPrimaryForwardPath() != null && stateMachine.getOldPrimaryReversePath() != null) {
            FlowPath oldForward = getFlowPath(stateMachine.getOldPrimaryForwardPath());
            oldForward.setFlow(flow);
            FlowPath oldReverse = getFlowPath(stateMachine.getOldPrimaryReversePath());
            oldReverse.setFlow(flow);
            factories.addAll(commandBuilder.buildAll(
                    stateMachine.getCommandContext(), flow, oldForward, oldReverse));
        }

        if (stateMachine.getOldProtectedForwardPath() != null && stateMachine.getOldProtectedReversePath() != null) {
            FlowPath oldForward = getFlowPath(stateMachine.getOldProtectedForwardPath());
            oldForward.setFlow(flow);
            FlowPath oldReverse = getFlowPath(stateMachine.getOldProtectedReversePath());
            oldReverse.setFlow(flow);
            factories.addAll(commandBuilder.buildAllExceptIngress(
                    stateMachine.getCommandContext(), flow, oldForward, oldReverse));
        }

        Map<UUID, FlowSegmentRequestFactory> requestsStorage = stateMachine.getRemoveCommands();
        for (FlowSegmentRequestFactory factory : factories) {
            FlowSegmentRequest request = factory.makeRemoveRequest(commandIdGenerator.generate());
            // TODO ensure no conflicts
            requestsStorage.put(request.getCommandId(), factory);
            stateMachine.getCarrier().sendSpeakerRequest(request);
        }

        stateMachine.getPendingCommands().addAll(new HashSet<>(requestsStorage.keySet()));
        stateMachine.getRetriedCommands().clear();

        stateMachine.saveActionToHistory("Remove commands for old rules have been sent");
    }
}
