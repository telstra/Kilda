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

import org.openkilda.floodlight.flow.request.GetInstalledRule;
import org.openkilda.floodlight.flow.request.InstallTransitRule;
import org.openkilda.wfm.topology.flowhs.fsm.common.SpeakerCommandFsm;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.service.SpeakerCommandObserver;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class DumpNonIngressRulesAction extends HistoryRecordingAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    private final SpeakerCommandFsm.Builder speakerCommandFsmBuilder;

    public DumpNonIngressRulesAction(SpeakerCommandFsm.Builder speakerCommandFsmBuilder) {
        this.speakerCommandFsmBuilder = speakerCommandFsmBuilder;
    }

    @Override
    public void perform(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        Map<UUID, InstallTransitRule> nonIngressCommands = stateMachine.getNonIngressCommands();
        if (nonIngressCommands.isEmpty()) {
            stateMachine.saveActionToHistory("No need to validate non ingress rules");
        } else {
            List<GetInstalledRule> dumpFlowRules = nonIngressCommands.values()
                    .stream()
                    .map(command -> new GetInstalledRule(command.getMessageContext(), command.getCommandId(),
                            command.getFlowId(), command.getSwitchId(), command.getCookie(), false))
                    .collect(Collectors.toList());

            dumpFlowRules.forEach(command -> {
                SpeakerCommandObserver commandObserver = new SpeakerCommandObserver(speakerCommandFsmBuilder, command);
                commandObserver.start();
                stateMachine.getPendingCommands().put(command.getCommandId(), commandObserver);
            });

            stateMachine.saveActionToHistory("Started validation of installed non ingress rules");
        }
    }
}
