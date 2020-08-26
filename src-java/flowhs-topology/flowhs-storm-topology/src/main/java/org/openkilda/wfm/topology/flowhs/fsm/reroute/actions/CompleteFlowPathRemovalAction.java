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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.RecoverablePersistenceException;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowPathRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import com.google.common.collect.Lists;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.RetryPolicy;
import org.neo4j.driver.v1.exceptions.ClientException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class CompleteFlowPathRemovalAction extends
        BaseFlowPathRemovalAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final int transactionRetriesLimit;

    public CompleteFlowPathRemovalAction(PersistenceManager persistenceManager, int transactionRetriesLimit) {
        super(persistenceManager);
        this.transactionRetriesLimit = transactionRetriesLimit;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        RetryPolicy retryPolicy = new RetryPolicy()
                .retryOn(RecoverablePersistenceException.class)
                .retryOn(ClientException.class)
                .withMaxRetries(transactionRetriesLimit);

        Sample sample = Timer.start();
        try {
            persistenceManager.getTransactionManager().doInTransaction(retryPolicy,
                    () -> removeFlowPaths(stateMachine));
        } finally {
            sample.stop(stateMachine.getMeterRegistry().timer("fsm.complete_flow_path_removal"));
        }
    }

    private void removeFlowPaths(FlowRerouteFsm stateMachine) {
        Flow flow = getFlow(stateMachine.getFlowId());

        FlowPath oldPrimaryForward = flow.getPath(stateMachine.getOldPrimaryForwardPath()).orElse(null);
        FlowPath oldPrimaryReverse = flow.getPath(stateMachine.getOldPrimaryReversePath()).orElse(null);
        FlowPath oldProtectedForward = flow.getPath(stateMachine.getOldProtectedForwardPath()).orElse(null);
        FlowPath oldProtectedReverse = flow.getPath(stateMachine.getOldProtectedReversePath()).orElse(null);

        List<FlowPath> flowPaths = Lists.newArrayList(oldPrimaryForward, oldPrimaryReverse,
                oldProtectedForward, oldProtectedReverse);
        List<FlowPath> rejectedFlowPaths = stateMachine.getRejectedPaths().stream()
                .map(this::getFlowPath)
                .collect(Collectors.toList());
        flowPaths.addAll(rejectedFlowPaths);

        flowPathRepository.lockInvolvedSwitches(flowPaths.stream().filter(Objects::nonNull).toArray(FlowPath[]::new));

        if (oldPrimaryForward != null) {
            if (oldPrimaryReverse != null) {
                log.debug("Completing removal of the flow paths {} / {}", oldPrimaryForward, oldPrimaryReverse);
                FlowPathPair pathsToDelete =
                        FlowPathPair.builder().forward(oldPrimaryForward).reverse(oldPrimaryReverse).build();
                deleteFlowPaths(pathsToDelete);
                saveRemovalActionWithDumpToHistory(stateMachine, flow, pathsToDelete);
            } else {
                log.debug("Completing removal of the flow path {} (no reverse pair)", oldPrimaryForward);
                deleteFlowPath(oldPrimaryForward);
                saveRemovalActionWithDumpToHistory(stateMachine, flow, oldPrimaryForward);
            }
        } else if (oldPrimaryReverse != null) {
            log.debug("Completing removal of the flow path {} (no forward pair)", oldPrimaryReverse);
            deleteFlowPath(oldPrimaryReverse);
            saveRemovalActionWithDumpToHistory(stateMachine, flow, oldPrimaryReverse);
        }

        if (oldProtectedForward != null) {
            if (oldProtectedReverse != null) {
                log.debug("Completing removal of the flow paths {} / {}", oldProtectedForward, oldProtectedReverse);
                FlowPathPair pathsToDelete =
                        FlowPathPair.builder().forward(oldProtectedForward).reverse(oldProtectedReverse).build();
                deleteFlowPaths(pathsToDelete);
                saveRemovalActionWithDumpToHistory(stateMachine, flow, pathsToDelete);
            } else {
                log.debug("Completing removal of the flow path {} (no reverse pair)", oldProtectedForward);
                deleteFlowPath(oldProtectedForward);
                saveRemovalActionWithDumpToHistory(stateMachine, flow, oldProtectedForward);
            }
        } else if (oldProtectedReverse != null) {
            log.debug("Completing removal of the flow path {} (no forward pair)", oldProtectedReverse);
            deleteFlowPath(oldProtectedReverse);
            saveRemovalActionWithDumpToHistory(stateMachine, flow, oldProtectedReverse);
        }

        rejectedFlowPaths.forEach(flowPath -> {
            log.debug("Removing the rejected path {}", flowPath);
            deleteFlowPath(flowPath);

            saveRemovalActionWithDumpToHistory(stateMachine, flow, flowPath);
        });
    }
}

