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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SwapFlowPathsAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowResourcesManager resourcesManager;

    public SwapFlowPathsAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        this.resourcesManager = resourcesManager;
    }

    @TimedExecution("fsm.swap_flow_paths")
    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        swapPrimaryPaths(stateMachine);
        swapProtectedPaths(stateMachine);

        if (stateMachine.getNewEncapsulationType() != null) {
            transactionManager.doInTransaction(() -> {
                Flow flow = getFlow(stateMachine.getFlowId());
                flow.setEncapsulationType(stateMachine.getNewEncapsulationType());
            });
        }
    }

    private void swapPrimaryPaths(FlowRerouteFsm stateMachine) {
        PathId newForward = stateMachine.getNewPrimaryForwardPath();
        PathId newReverse = stateMachine.getNewPrimaryReversePath();
        if (newForward != null && newReverse != null) {
            transactionManager.doInTransaction(() -> {
                Flow flow = getFlow(stateMachine.getFlowId());

                FlowPath oldForward = flow.getForwardPath();
                if (oldForward != null) {
                    stateMachine.setOldPrimaryForwardPath(oldForward.getPathId());
                    stateMachine.setOldPrimaryForwardPathStatus(oldForward.getStatus());
                    oldForward.setStatus(FlowPathStatus.IN_PROGRESS);
                }

                FlowPath oldReverse = flow.getReversePath();
                if (oldReverse != null) {
                    stateMachine.setOldPrimaryReversePath(oldReverse.getPathId());
                    stateMachine.setOldPrimaryReversePathStatus(oldReverse.getStatus());
                    oldReverse.setStatus(FlowPathStatus.IN_PROGRESS);
                }

                if (oldForward != null || oldReverse != null) {
                    FlowResources oldResources = getResources(flow,
                            oldForward != null ? oldForward : oldReverse,
                            oldReverse != null ? oldReverse : oldForward);
                    stateMachine.getOldResources().add(oldResources);
                }

                flow.setForwardPathId(newForward);
                flow.setReversePathId(newReverse);

                log.debug("Swapping the primary paths {}/{} with {}/{}",
                        oldForward != null ? oldForward.getPathId() : null,
                        oldReverse != null ? oldReverse.getPathId() : null,
                        newForward, newReverse);
            });
            saveHistory(stateMachine, stateMachine.getFlowId(), newForward, newReverse);
        }
    }

    private void swapProtectedPaths(FlowRerouteFsm stateMachine) {
        PathId newForward = stateMachine.getNewProtectedForwardPath();
        PathId newReverse = stateMachine.getNewProtectedReversePath();
        if (newForward != null && newReverse != null) {
            transactionManager.doInTransaction(() -> {
                Flow flow = getFlow(stateMachine.getFlowId());
                FlowPath oldForward = flow.getProtectedForwardPath();
                if (oldForward != null) {
                    stateMachine.setOldProtectedForwardPath(oldForward.getPathId());
                    stateMachine.setOldProtectedForwardPathStatus(oldForward.getStatus());
                    oldForward.setStatus(FlowPathStatus.IN_PROGRESS);
                }

                FlowPath oldReverse = flow.getProtectedReversePath();
                if (oldReverse != null) {
                    stateMachine.setOldProtectedReversePath(oldReverse.getPathId());
                    stateMachine.setOldProtectedReversePathStatus(oldReverse.getStatus());
                    oldReverse.setStatus(FlowPathStatus.IN_PROGRESS);
                }

                if (oldForward != null || oldReverse != null) {
                    FlowResources oldResources = getResources(flow,
                            oldForward != null ? oldForward : oldReverse,
                            oldReverse != null ? oldReverse : oldForward);
                    stateMachine.getOldResources().add(oldResources);
                }

                flow.setProtectedForwardPathId(newForward);
                flow.setProtectedReversePathId(newReverse);

                log.debug("Swapping the protected paths {}/{} with {}/{}",
                        oldForward != null ? oldForward.getPathId() : null,
                        oldReverse != null ? oldReverse.getPathId() : null,
                        newForward, newReverse);
            });
            saveHistory(stateMachine, stateMachine.getFlowId(), newForward, newReverse);
        }
    }

    private FlowResources getResources(Flow flow, FlowPath forwardPath, FlowPath reversePath) {
        EncapsulationResources encapsulationResources = resourcesManager.getEncapsulationResources(
                forwardPath.getPathId(), reversePath.getPathId(), flow.getEncapsulationType()).orElse(null);
        return FlowResources.builder()
                .unmaskedCookie(forwardPath.getCookie().getFlowEffectiveId())
                .forward(PathResources.builder()
                        .pathId(forwardPath.getPathId())
                        .meterId(forwardPath.getMeterId())
                        .encapsulationResources(encapsulationResources)
                        .build())
                .reverse(PathResources.builder()
                        .pathId(reversePath.getPathId())
                        .meterId(reversePath.getMeterId())
                        .encapsulationResources(encapsulationResources)
                        .build())
                .build();
    }

    private void saveHistory(FlowRerouteFsm stateMachine, String flowId, PathId forwardPath, PathId reversePath) {
        stateMachine.saveActionToHistory("Flow was updated with new paths",
                format("The flow %s was updated with paths %s / %s", flowId, forwardPath, reversePath));
    }
}
