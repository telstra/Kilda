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
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
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

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SwapFlowPathsAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowResourcesManager resourcesManager;

    public SwapFlowPathsAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        this.resourcesManager = resourcesManager;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId(), FetchStrategy.DIRECT_RELATIONS);

            if (stateMachine.getNewPrimaryForwardPath() != null && stateMachine.getNewPrimaryReversePath() != null) {
                FlowPath oldForward = getFlowPath(flow, flow.getForwardPathId());
                stateMachine.setOldPrimaryForwardPath(oldForward.getPathId());
                stateMachine.setOldPrimaryForwardPathStatus(oldForward.getStatus());
                flowPathRepository.updateStatus(oldForward.getPathId(), FlowPathStatus.IN_PROGRESS);

                FlowPath oldReverse = getFlowPath(flow, flow.getReversePathId());
                stateMachine.setOldPrimaryReversePath(oldReverse.getPathId());
                stateMachine.setOldPrimaryReversePathStatus(oldReverse.getStatus());
                flowPathRepository.updateStatus(oldReverse.getPathId(), FlowPathStatus.IN_PROGRESS);

                FlowResources oldResources = getResources(flow, oldForward, oldReverse);
                stateMachine.getOldResources().add(oldResources);

                PathId newForward = stateMachine.getNewPrimaryForwardPath();
                flow.setForwardPath(newForward);
                PathId newReverse = stateMachine.getNewPrimaryReversePath();
                flow.setReversePath(newReverse);

                log.debug("Swapping the primary paths {}/{} with {}/{}",
                        oldForward.getPathId(), oldReverse.getPathId(),
                        newForward, newReverse);

                saveHistory(stateMachine, flow.getFlowId(), newForward, newReverse);
            }

            if (stateMachine.getNewProtectedForwardPath() != null
                    && stateMachine.getNewProtectedReversePath() != null) {
                FlowPath oldForward = getFlowPath(flow, flow.getProtectedForwardPathId());
                stateMachine.setOldProtectedForwardPath(oldForward.getPathId());
                stateMachine.setOldProtectedForwardPathStatus(oldForward.getStatus());
                flowPathRepository.updateStatus(oldForward.getPathId(), FlowPathStatus.IN_PROGRESS);

                FlowPath oldReverse = getFlowPath(flow, flow.getProtectedReversePathId());
                stateMachine.setOldProtectedReversePath(oldReverse.getPathId());
                stateMachine.setOldProtectedReversePathStatus(oldReverse.getStatus());
                flowPathRepository.updateStatus(oldReverse.getPathId(), FlowPathStatus.IN_PROGRESS);

                FlowResources oldResources = getResources(flow, oldForward, oldReverse);
                stateMachine.getOldResources().add(oldResources);

                PathId newForward = stateMachine.getNewProtectedForwardPath();
                flow.setProtectedForwardPath(newForward);
                PathId newReverse = stateMachine.getNewProtectedReversePath();
                flow.setProtectedReversePath(newReverse);

                log.debug("Swapping the protected paths {}/{} with {}/{}",
                        oldForward.getPathId(), oldReverse.getPathId(),
                        newForward, newReverse);

                saveHistory(stateMachine, flow.getFlowId(), newForward, newReverse);
            }

            if (stateMachine.getNewEncapsulationType() != null) {
                flow.setEncapsulationType(stateMachine.getNewEncapsulationType());
            }

            flowRepository.createOrUpdate(flow);
        });
    }

    private FlowResources getResources(Flow flow, FlowPath forwardPath, FlowPath reversePath) {
        EncapsulationResources encapsulationResources = resourcesManager.getEncapsulationResources(
                forwardPath.getPathId(), reversePath.getPathId(), flow.getEncapsulationType()).orElse(null);
        return FlowResources.builder()
                .unmaskedCookie(FlowSegmentCookieSchema.INSTANCE.getFlowEffectiveId(forwardPath.getCookie()))
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
