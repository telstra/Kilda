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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions.error;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.info.reroute.error.SpeakerRequestError;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.AnonymousAction;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SetValidateRuleErrorAction extends AnonymousAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {

    @Override
    public void execute(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        Set<SwitchId> switches = Stream.concat(stateMachine.getPendingCommands().values().stream(),
                stateMachine.getFailedValidationResponses().values().stream()
                        .map(SpeakerFlowSegmentResponse::getSwitchId))
                .collect(Collectors.toSet());
        stateMachine.setRerouteError(new SpeakerRequestError("Failed to validate rules", switches));
        log.debug("Abandoning all pending commands: {}", stateMachine.getPendingCommands());
        stateMachine.clearPendingCommands();
    }
}
