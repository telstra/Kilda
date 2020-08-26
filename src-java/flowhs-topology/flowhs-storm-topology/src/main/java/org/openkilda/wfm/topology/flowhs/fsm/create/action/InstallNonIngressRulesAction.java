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

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;

import io.micrometer.core.instrument.LongTaskTimer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class InstallNonIngressRulesAction extends InstallRulesAction {
    public InstallNonIngressRulesAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        List<FlowSegmentRequestFactory> requestFactories = stateMachine.getNonIngressCommands();
        if (requestFactories.isEmpty()) {
            stateMachine.saveActionToHistory("No need to install non ingress rules");
        } else {
            stateMachine.setNoningressInstallationTimer(
                    LongTaskTimer.builder("fsm.install_noningress_rule.active_execution")
                            .register(stateMachine.getMeterRegistry())
                            .start());

            emitInstallRequests(stateMachine, requestFactories);
            stateMachine.saveActionToHistory("Commands for installing non ingress rules have been sent");
        }
    }
}
