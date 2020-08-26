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

package org.openkilda.wfm.topology.flowhs.fsm.create.action;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RollbackInstalledRulesAction extends FlowProcessingAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    public RollbackInstalledRulesAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        stateMachine.getPendingCommands().clear();
        stateMachine.getRetriedCommands().clear();
        stateMachine.getFailedCommands().clear();

        for (FlowSegmentRequestFactory factory : stateMachine.getSentCommands()) {
            FlowSegmentRequest request = factory.makeRemoveRequest(commandIdGenerator.generate());

            stateMachine.getPendingCommands().put(request.getCommandId(), factory);
            stateMachine.getCarrier().sendSpeakerRequest(request);
        }

        stateMachine.saveActionToHistory(String.format(
                "Commands to rollback installed rules have been sent. Total amount: %s",
                stateMachine.getPendingCommands().size()));
    }
}
