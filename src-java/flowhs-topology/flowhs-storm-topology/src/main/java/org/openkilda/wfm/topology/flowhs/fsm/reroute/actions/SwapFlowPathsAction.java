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
import org.openkilda.persistence.FetchStrategy;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class SwapFlowPathsAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowResourcesManager resourcesManager;

    public SwapFlowPathsAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        this.resourcesManager = resourcesManager;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        Sample sample = Timer.start();
        try {
            persistenceManager.getTransactionManager().doInTransaction(() -> {
                Flow flow = getFlow(stateMachine.getFlowId(), FetchStrategy.DIRECT_RELATIONS);

                if (stateMachine.getNewPrimaryForwardPath() != null
                        && stateMachine.getNewPrimaryReversePath() != null) {
                    Optional<FlowPath> oldForward = flow.getPath(flow.getForwardPathId());
                    oldForward.ifPresent(path -> {
                        stateMachine.setOldPrimaryForwardPath(path.getPathId());
                        stateMachine.setOldPrimaryForwardPathStatus(path.getStatus());
                        flowPathRepository.updateStatus(path.getPathId(), FlowPathStatus.IN_PROGRESS);
                    });

                    Optional<FlowPath> oldReverse = flow.getPath(flow.getReversePathId());
                    oldReverse.ifPresent(path -> {
                        stateMachine.setOldPrimaryReversePath(path.getPathId());
                        stateMachine.setOldPrimaryReversePathStatus(path.getStatus());
                        flowPathRepository.updateStatus(path.getPathId(), FlowPathStatus.IN_PROGRESS);
                    });

                    if (oldForward.isPresent() || oldReverse.isPresent()) {
                        FlowResources oldResources = getResources(flow, oldForward.orElseGet(oldReverse::get),
                                oldReverse.orElseGet(oldForward::get));
                        stateMachine.getOldResources().add(oldResources);
                    }

                    PathId newForward = stateMachine.getNewPrimaryForwardPath();
                    flow.setForwardPath(newForward);
                    PathId newReverse = stateMachine.getNewPrimaryReversePath();
                    flow.setReversePath(newReverse);

                    log.debug("Swapping the primary paths {}/{} with {}/{}",
                            oldForward.map(FlowPath::getPathId).orElse(null),
                            oldReverse.map(FlowPath::getPathId).orElse(null),
                            newForward, newReverse);

                    saveHistory(stateMachine, flow.getFlowId(), newForward, newReverse);
                }

                if (stateMachine.getNewProtectedForwardPath() != null
                        && stateMachine.getNewProtectedReversePath() != null) {
                    Optional<FlowPath> oldForward = flow.getPath(flow.getProtectedForwardPathId());
                    oldForward.ifPresent(path -> {
                        stateMachine.setOldProtectedForwardPath(path.getPathId());
                        stateMachine.setOldProtectedForwardPathStatus(path.getStatus());
                        flowPathRepository.updateStatus(path.getPathId(), FlowPathStatus.IN_PROGRESS);
                    });

                    Optional<FlowPath> oldReverse = flow.getPath(flow.getProtectedReversePathId());
                    oldReverse.ifPresent(path -> {
                        stateMachine.setOldProtectedReversePath(path.getPathId());
                        stateMachine.setOldProtectedReversePathStatus(path.getStatus());
                        flowPathRepository.updateStatus(path.getPathId(), FlowPathStatus.IN_PROGRESS);
                    });

                    if (oldForward.isPresent() || oldReverse.isPresent()) {
                        FlowResources oldResources = getResources(flow, oldForward.orElseGet(oldReverse::get),
                                oldReverse.orElseGet(oldForward::get));
                        stateMachine.getOldResources().add(oldResources);
                    }

                    PathId newForward = stateMachine.getNewProtectedForwardPath();
                    flow.setProtectedForwardPath(newForward);
                    PathId newReverse = stateMachine.getNewProtectedReversePath();
                    flow.setProtectedReversePath(newReverse);

                    log.debug("Swapping the protected paths {}/{} with {}/{}",
                            oldForward.map(FlowPath::getPathId).orElse(null),
                            oldReverse.map(FlowPath::getPathId).orElse(null),
                            newForward, newReverse);

                    saveHistory(stateMachine, flow.getFlowId(), newForward, newReverse);
                }

                if (stateMachine.getNewEncapsulationType() != null) {
                    flow.setEncapsulationType(stateMachine.getNewEncapsulationType());
                }

                flowRepository.createOrUpdate(flow);
            });
        } finally {
            sample.stop(stateMachine.getMeterRegistry().timer("fsm.swap_paths"));
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
