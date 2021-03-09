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

import static java.lang.String.format;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapContext;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.State;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class HandleNotCompletedCommandsAction extends
        HistoryRecordingAction<FlowPathSwapFsm, State, Event, FlowPathSwapContext> {
    @Override
    public void perform(State from, State to, Event event, FlowPathSwapContext context, FlowPathSwapFsm stateMachine) {
        for (UUID commandId : stateMachine.getPendingCommands().keySet()) {
            FlowSegmentRequestFactory removeCommand = stateMachine.getRemoveCommands().get(commandId);
            if (removeCommand != null) {
                stateMachine.saveErrorToHistory("Command is not finished yet",
                        format("Completing the path swap operation although the remove command may not be "
                                        + "finished yet: commandId %s, switch %s, cookie %s", commandId,
                                removeCommand.getSwitchId(), removeCommand.getCookie()));
            } else {
                FlowSegmentRequestFactory ingressRule = stateMachine.getIngressCommands().get(commandId);
                stateMachine.saveErrorToHistory("Command is not finished yet",
                        format("Completing the path swap operation although the install command may not be "
                                        + "finished yet: commandId %s, switch %s, rule %s", commandId,
                                ingressRule.getSwitchId(), ingressRule));
            }
        }
        log.debug("Abandoning all pending commands: {}", stateMachine.getPendingCommands());
        stateMachine.clearPendingCommands();
    }
}
