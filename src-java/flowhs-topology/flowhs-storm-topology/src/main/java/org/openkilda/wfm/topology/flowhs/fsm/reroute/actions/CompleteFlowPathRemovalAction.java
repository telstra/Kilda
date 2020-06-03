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
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowPathRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CompleteFlowPathRemovalAction extends
        BaseFlowPathRemovalAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public CompleteFlowPathRemovalAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @TimedExecution("fsm.complete_flow_path_remove")
    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        Flow flow = getFlow(stateMachine.getFlowId());
        removeOldPrimaryFlowPaths(flow, stateMachine);
        removeOldProtectedFlowPaths(flow, stateMachine);
        removeRejectedFlowPaths(flow, stateMachine);
    }

    private void removeOldPrimaryFlowPaths(Flow flow, FlowRerouteFsm stateMachine) {
        PathId oldPrimaryForwardPathId = stateMachine.getOldPrimaryForwardPath();
        PathId oldPrimaryReversePathId = stateMachine.getOldPrimaryReversePath();

        if (oldPrimaryForwardPathId != null || oldPrimaryReversePathId != null) {
            FlowPath oldPrimaryForward = flowPathRepository.remove(oldPrimaryForwardPathId).orElse(null);
            FlowPath oldPrimaryReverse = flowPathRepository.remove(oldPrimaryReversePathId).orElse(null);

            FlowPathPair removedPaths = null;
            if (oldPrimaryForward != null) {
                if (oldPrimaryReverse != null) {
                    log.debug("Removed the flow paths {} / {}", oldPrimaryForward, oldPrimaryReverse);
                    removedPaths = new FlowPathPair(oldPrimaryForward, oldPrimaryReverse);
                    updateIslsForFlowPath(removedPaths.getForward(), removedPaths.getReverse());
                } else {
                    log.debug("Removed the flow path {} (no reverse pair)", oldPrimaryForward);
                    // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                    removedPaths = new FlowPathPair(oldPrimaryForward, oldPrimaryForward);
                    updateIslsForFlowPath(removedPaths.getForward());
                }
            } else if (oldPrimaryReverse != null) {
                log.debug("Removed the flow path {} (no forward pair)", oldPrimaryReverse);
                // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                removedPaths = new FlowPathPair(oldPrimaryReverse, oldPrimaryReverse);
                updateIslsForFlowPath(removedPaths.getReverse());
            }
            if (removedPaths != null) {
                saveRemovalActionWithDumpToHistory(stateMachine, flow, removedPaths);
            }
        }
    }

    private void removeOldProtectedFlowPaths(Flow flow, FlowRerouteFsm stateMachine) {
        PathId oldProtectedForwardPathId = stateMachine.getOldProtectedForwardPath();
        PathId oldProtectedReversePathId = stateMachine.getOldProtectedReversePath();

        if (oldProtectedForwardPathId != null || oldProtectedReversePathId != null) {
            FlowPath oldProtectedForward = flowPathRepository.remove(oldProtectedForwardPathId).orElse(null);
            FlowPath oldProtectedReverse = flowPathRepository.remove(oldProtectedReversePathId).orElse(null);

            FlowPathPair removedPaths = null;
            if (oldProtectedForward != null) {
                if (oldProtectedReverse != null) {
                    log.debug("Removed the flow paths {} / {}", oldProtectedForward, oldProtectedReverse);
                    removedPaths = new FlowPathPair(oldProtectedForward, oldProtectedReverse);
                    updateIslsForFlowPath(removedPaths.getForward(), removedPaths.getReverse());
                } else {
                    log.debug("Removed the flow path {} (no reverse pair)", oldProtectedForward);
                    // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                    removedPaths = new FlowPathPair(oldProtectedForward, oldProtectedForward);
                    updateIslsForFlowPath(removedPaths.getForward());
                }
            } else if (oldProtectedReverse != null) {
                log.debug("Removed the flow path {} (no forward pair)", oldProtectedReverse);
                // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                removedPaths = new FlowPathPair(oldProtectedReverse, oldProtectedReverse);
                updateIslsForFlowPath(removedPaths.getReverse());
            }
            if (removedPaths != null) {
                saveRemovalActionWithDumpToHistory(stateMachine, flow, removedPaths);
            }
        }
    }

    @TimedExecution("fsm.complete_flow_path_remove_rejected")
    private void removeRejectedFlowPaths(Flow flow, FlowRerouteFsm stateMachine) {
        stateMachine.getRejectedPaths().stream()
                .forEach(pathId ->
                        flowPathRepository.remove(pathId)
                                .ifPresent(flowPath -> {
                                    updateIslsForFlowPath(flowPath);
                                    saveRemovalActionWithDumpToHistory(stateMachine, flow, flowPath);
                                }));
    }
}
