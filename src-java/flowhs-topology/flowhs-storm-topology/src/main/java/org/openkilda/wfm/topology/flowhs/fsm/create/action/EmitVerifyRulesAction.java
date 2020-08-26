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
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;

import java.util.Collection;

abstract class EmitVerifyRulesAction
        extends HistoryRecordingAction<FlowCreateFsm, FlowCreateFsm.State, FlowCreateFsm.Event, FlowCreateContext> {

    private final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    protected void emitVerifyRequests(
            FlowCreateFsm stateMachine, Collection<FlowSegmentRequestFactory> requestFactories) {
        stateMachine.getPendingCommands().clear();
        stateMachine.getRetriedCommands().clear();
        stateMachine.getFailedCommands().clear();

        for (FlowSegmentRequestFactory factory : requestFactories) {
            FlowSegmentRequest request = factory.makeVerifyRequest(commandIdGenerator.generate());

            stateMachine.getPendingCommands().put(request.getCommandId(), factory);
            stateMachine.getCarrier().sendSpeakerRequest(request);
        }
    }
}
