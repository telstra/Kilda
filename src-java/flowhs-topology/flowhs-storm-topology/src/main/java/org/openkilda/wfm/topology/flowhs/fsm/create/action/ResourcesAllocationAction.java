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

package org.openkilda.wfm.topology.flowhs.fsm.create.action;

import org.openkilda.messaging.MessageContext;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.create.command.ResourcesAllocationCommand;
import org.openkilda.wfm.topology.flowhs.service.FlowCreateHubCarrier;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ResourcesAllocationAction extends FlowProcessingAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    FlowCreateHubCarrier carrier;

    public ResourcesAllocationAction(PersistenceManager persistenceManager, FlowCreateHubCarrier carrier) {
        super(persistenceManager);
        this.carrier = carrier;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        UUID commandId = commandIdGenerator.generate();
        MessageContext messageContext = new MessageContext(commandId.toString(),
                stateMachine.getCommandContext().getCorrelationId());
        ResourcesAllocationCommand command = new ResourcesAllocationCommand(messageContext,
                commandIdGenerator.generate(), stateMachine.getCommandContext(), context, stateMachine.getFlowId());
        carrier.sendSpeakerDbCommand(command);
        stateMachine.saveActionToHistory("Command for resources allocating has been sent");
    }
}
