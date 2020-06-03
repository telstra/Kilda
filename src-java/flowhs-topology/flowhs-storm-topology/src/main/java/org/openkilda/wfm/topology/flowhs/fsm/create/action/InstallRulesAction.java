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
import org.openkilda.messaging.MessageContext;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.SpeakerCommandFsm;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.service.SpeakerCommandObserver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

abstract class InstallRulesAction extends FlowProcessingAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    private final SpeakerCommandFsm.Builder speakerCommandFsmBuilder;

    public InstallRulesAction(
            PersistenceManager persistenceManager, SpeakerCommandFsm.Builder speakerCommandFsmBuilder) {
        super(persistenceManager);
        this.speakerCommandFsmBuilder = speakerCommandFsmBuilder;
    }

    protected void emitInstallRequests(FlowCreateFsm stateMachine, List<FlowSegmentRequestFactory> factories) {
        Map<UUID, SpeakerCommandObserver> pendingRequests = stateMachine.getPendingCommands();
        List<FlowSegmentRequestFactory> sentCommands = stateMachine.getSentCommands();
        for (FlowSegmentRequestFactory factory : factories) {
            FlowSegmentRequest request = factory.makeInstallRequest(commandIdGenerator.generate());
            request.setMessageContext(new MessageContext(request.getMessageContext().getCorrelationId(),
                    Instant.now().toEpochMilli()));

            SpeakerCommandObserver commandObserver = new SpeakerCommandObserver(speakerCommandFsmBuilder, request);
            commandObserver.start();

            sentCommands.add(factory);
            // TODO ensure no conflicts
            pendingRequests.put(request.getCommandId(), commandObserver);
        }
    }
}
