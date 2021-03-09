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
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerVerifySegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class EmitNonIngressRulesVerifyRequestsAction
        extends HistoryRecordingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    @Override
    public void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        Map<UUID, FlowSegmentRequestFactory> requestsStorage = stateMachine.getNonIngressCommands();
        List<FlowSegmentRequestFactory> requestFactories = new ArrayList<>(requestsStorage.values());
        requestsStorage.clear();

        stateMachine.clearPendingAndRetriedAndFailedCommands();

        if (requestFactories.isEmpty()) {
            stateMachine.saveActionToHistory("No need to validate non ingress rules");

            stateMachine.fire(Event.RULES_VALIDATED);
        } else {
            SpeakerVerifySegmentEmitter.INSTANCE.emitBatch(
                    stateMachine.getCarrier(), requestFactories, requestsStorage);
            requestsStorage.forEach((key, value) -> stateMachine.addPendingCommand(key, value.getSwitchId()));

            stateMachine.saveActionToHistory("Started validation of installed non ingress rules");
        }
    }
}
