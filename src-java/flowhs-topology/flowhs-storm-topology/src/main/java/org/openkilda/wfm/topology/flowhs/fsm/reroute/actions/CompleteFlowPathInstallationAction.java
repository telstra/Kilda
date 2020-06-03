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

import static java.lang.String.format;

import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CompleteFlowPathInstallationAction extends
        FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public CompleteFlowPathInstallationAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @TimedExecution("fsm.complete_flow_path_install")
    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        if (stateMachine.getNewPrimaryForwardPath() != null && stateMachine.getNewPrimaryReversePath() != null) {
            PathId newForward = stateMachine.getNewPrimaryForwardPath();
            PathId newReverse = stateMachine.getNewPrimaryReversePath();

            log.debug("Completing installation of the flow primary path {} / {}", newForward, newReverse);
            FlowPathStatus primaryPathStatus;
            if (stateMachine.isIgnoreBandwidth() || stateMachine.isBackUpPrimaryPathComputationWayUsed()) {
                primaryPathStatus = FlowPathStatus.DEGRADED;
            } else {
                primaryPathStatus = FlowPathStatus.ACTIVE;
            }

            transactionManager.doInTransaction(() -> {
                flowPathRepository.updateStatus(newForward, primaryPathStatus);
                flowPathRepository.updateStatus(newReverse, primaryPathStatus);
            });

            stateMachine.saveActionToHistory("Flow paths were installed",
                    format("The flow paths %s / %s were installed", newForward, newReverse));
        }

        if (stateMachine.getNewProtectedForwardPath() != null
                && stateMachine.getNewProtectedReversePath() != null) {
            PathId newForward = stateMachine.getNewProtectedForwardPath();
            PathId newReverse = stateMachine.getNewProtectedReversePath();
            FlowPathStatus protectedPathStatus;
            if (stateMachine.isIgnoreBandwidth() || stateMachine.isBackUpProtectedPathComputationWayUsed()) {
                protectedPathStatus = FlowPathStatus.DEGRADED;
            } else {
                protectedPathStatus = FlowPathStatus.ACTIVE;
            }
            log.debug("Completing installation of the flow protected path {} / {}", newForward, newReverse);

            transactionManager.doInTransaction(() -> {
                flowPathRepository.updateStatus(newForward, protectedPathStatus);
                flowPathRepository.updateStatus(newReverse, protectedPathStatus);
            });

            stateMachine.saveActionToHistory("Flow paths were installed",
                    format("The flow paths %s / %s were installed", newForward, newReverse));
        }
    }
}
