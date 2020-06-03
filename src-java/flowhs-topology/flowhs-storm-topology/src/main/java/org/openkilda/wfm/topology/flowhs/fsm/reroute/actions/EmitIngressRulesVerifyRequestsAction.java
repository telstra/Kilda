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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.messaging.MessageContext;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.HistoryRecordingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import io.micrometer.core.instrument.LongTaskTimer;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class EmitIngressRulesVerifyRequestsAction
        extends HistoryRecordingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    @TimedExecution("fsm.emit_ingress_rules")
    @Override
    public void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        MeterRegistryHolder.getRegistry().ifPresent(registry -> stateMachine.setIngressValidationTimer(
                LongTaskTimer.builder("fsm.validate_ingress_rule.active_execution")
                        .register(registry)
                        .start()));

        Map<UUID, FlowSegmentRequestFactory> requestsStorage = stateMachine.getIngressCommands();
        List<FlowSegmentRequestFactory> requestFactories = new ArrayList<>(requestsStorage.values());
        requestsStorage.clear();
        for (FlowSegmentRequestFactory factory : requestFactories) {
            FlowSegmentRequest request = factory.makeVerifyRequest(commandIdGenerator.generate());
            request.setMessageContext(new MessageContext(request.getMessageContext().getCorrelationId(),
                    Instant.now().toEpochMilli()));

            // TODO ensure no conflicts
            requestsStorage.put(request.getCommandId(), factory);
            stateMachine.getCarrier().sendSpeakerRequest(request);
        }
        requestsStorage.forEach((key, value) -> stateMachine.getPendingCommands().put(key, value.getSwitchId()));

        stateMachine.saveActionToHistory("Started validation of installed ingress rules");
    }
}
