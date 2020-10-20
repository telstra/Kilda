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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import org.openkilda.messaging.MessageContext;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.command.AllocateProtectedResourcesCommand;
import org.openkilda.wfm.topology.flowhs.service.FlowRerouteHubCarrier;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class AllocateProtectedResourcesAction extends
        FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    FlowRerouteHubCarrier carrier;

    public AllocateProtectedResourcesAction(PersistenceManager persistenceManager, FlowRerouteHubCarrier carrier) {
        super(persistenceManager);
        this.carrier = carrier;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        if (!stateMachine.isRerouteProtected()) {
            stateMachine.fire(Event.RESOURCES_ALLOCATED);
            return;
        }

        UUID commandId = commandIdGenerator.generate();
        MessageContext messageContext = new MessageContext(commandId.toString(),
                stateMachine.getCommandContext().getCorrelationId());
        AllocateProtectedResourcesCommand command = new AllocateProtectedResourcesCommand(messageContext, commandId,
                stateMachine.getCommandContext(), context, stateMachine.getFlowId(),
                stateMachine.getNewEncapsulationType(), stateMachine.isRecreateIfSamePath(),
                stateMachine.getRejectedPaths(), stateMachine.getNewPrimaryForwardPath(),
                stateMachine.getNewPrimaryReversePath());
        carrier.sendSpeakerDbCommand(command);
        stateMachine.saveActionToHistory("Command for protected resources allocating has been sent");
    }
}
