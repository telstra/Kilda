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
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
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

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        PathComputationStrategy targetStrategy = stateMachine.getTargetPathComputationStrategy();
        if (stateMachine.getNewPrimaryForwardPath() != null && stateMachine.getNewPrimaryReversePath() != null) {
            PathId newForward = stateMachine.getNewPrimaryForwardPath();
            PathId newReverse = stateMachine.getNewPrimaryReversePath();

            log.debug("Completing installation of the flow primary path {} / {}", newForward, newReverse);
            FlowPathStatus targetPathStatus;
            if (stateMachine.isIgnoreBandwidth()
                    || !targetStrategy.equals(stateMachine.getNewPrimaryPathComputationStrategy())) {
                targetPathStatus = FlowPathStatus.DEGRADED;
            } else {
                targetPathStatus = FlowPathStatus.ACTIVE;
            }

            final long time = System.currentTimeMillis();

            transactionManager.doInTransaction(() -> {
                flowPathRepository.updateStatus(newForward, targetPathStatus);
                flowPathRepository.updateStatus(newReverse, targetPathStatus);
            });
            log.warn("HSTIME reroute complete primary flow path installing " + (System.currentTimeMillis() - time));

            stateMachine.saveActionToHistory("Flow paths were installed",
                    format("The flow paths %s / %s were installed", newForward, newReverse));
        }

        if (stateMachine.getNewProtectedForwardPath() != null
                && stateMachine.getNewProtectedReversePath() != null) {
            PathId newForward = stateMachine.getNewProtectedForwardPath();
            PathId newReverse = stateMachine.getNewProtectedReversePath();
            FlowPathStatus targetPathStatus;
            if (stateMachine.isIgnoreBandwidth()
                    || !targetStrategy.equals(stateMachine.getNewProtectedPathComputationStrategy())) {
                targetPathStatus = FlowPathStatus.DEGRADED;
            } else {
                targetPathStatus = FlowPathStatus.ACTIVE;
            }
            log.debug("Completing installation of the flow protected path {} / {}", newForward, newReverse);

            final long time = System.currentTimeMillis();
            transactionManager.doInTransaction(() -> {
                flowPathRepository.updateStatus(newForward, targetPathStatus);
                flowPathRepository.updateStatus(newReverse, targetPathStatus);
            });

            log.warn("HSTIME reroute complete protected flow path installing " + (System.currentTimeMillis() - time));

            stateMachine.saveActionToHistory("Flow paths were installed",
                    format("The flow paths %s / %s were installed", newForward, newReverse));
        }

    }
}
