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

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.service.SpeakerCommandObserver;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
abstract class OnReceivedResponseAction extends FlowProcessingAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    OnReceivedResponseAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        SpeakerFlowSegmentResponse response = context.getSpeakerFlowResponse();

        if (response.getTransferTime() > 0) {
            stateMachine.getMeterRegistry().timer("floodlight.command.transfer")
                    .record(response.getTransferTime(), TimeUnit.NANOSECONDS);
        }

        if (response.getWaitTime() > 0) {
            stateMachine.getMeterRegistry().timer("floodlight.command.wait")
                    .record(response.getWaitTime(), TimeUnit.NANOSECONDS);
        }

        if (response.getExecutionTime() > 0) {
            stateMachine.getMeterRegistry().timer("floodlight.command.execution")
                    .record(response.getExecutionTime(), TimeUnit.NANOSECONDS);
        }

        if (!stateMachine.isPendingCommand(response.getCommandId())) {
            log.warn("Received response for non-pending command: {}", response.getCommandId());
            return;
        }

        SpeakerCommandObserver commandObserver = stateMachine.getPendingCommands().get(response.getCommandId());
        commandObserver.handleResponse(response);

        if (commandObserver.isFinished()) {
            stateMachine.getPendingCommands().remove(response.getCommandId());
            handleResponse(stateMachine, response);

            completeStage(stateMachine, context);
        }
    }

    protected abstract void handleResponse(FlowCreateFsm stateMachine, SpeakerFlowSegmentResponse response);

    protected void completeStage(FlowCreateFsm stateMachine, FlowCreateContext context) {
        if (!stateMachine.getPendingCommands().isEmpty()) {
            return;
        }

        onComplete(stateMachine, context);
    }

    protected void onComplete(FlowCreateFsm stateMachine, FlowCreateContext context) {
        stateMachine.fireNext(context);
    }
}
