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

package org.openkilda.wfm.topology.flowhs.fsm.common.actions;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowPathSwappingFsm;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowProcessingFsm;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public abstract class PathSwappingAction<T extends FlowProcessingFsm<T, S, E, C>, S, E, C>
        extends FlowProcessingAction<T, S, E, C> {
    private final FlowResourcesManager resourcesManager;

    public PathSwappingAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        this.resourcesManager = resourcesManager;
    }

    protected void saveOldPrimaryPaths(
            FlowPathSwappingFsm<?, ?, ?, ?> stateMachine, Flow flow, FlowPath forwardPath, FlowPath reversePath,
            FlowEncapsulationType encapsulationType) {

        FlowResources resources = fetchFlowResources(forwardPath, reversePath, encapsulationType);

        log.debug("Save old primary paths resources: {}", resources);
        if (forwardPath != null && resources.getForward() != null) {
            stateMachine.setOldPrimaryForwardPath(makeFlowPathOldSnapshot(
                    stateMachine.getSharedOfFlowManager(), flow, forwardPath.getPathId(), resources.getForward()));
            stateMachine.setOldPrimaryForwardPathStatus(forwardPath.getStatus());
        }
        if (reversePath != null && resources.getReverse() != null) {
            stateMachine.setOldPrimaryReversePath(makeFlowPathOldSnapshot(
                    stateMachine.getSharedOfFlowManager(), flow, reversePath.getPathId(), resources.getReverse()));
            stateMachine.setOldPrimaryReversePathStatus(reversePath.getStatus());
        }

        stateMachine.getOldResources().add(fixFlowResources(resources));
    }

    protected void saveOldProtectedPaths(
            FlowPathSwappingFsm<?, ?, ?, ?> stateMachine, Flow flow, FlowPath forwardPath, FlowPath reversePath,
            FlowEncapsulationType encapsulationType) {

        FlowResources resources = fetchFlowResources(forwardPath, reversePath, encapsulationType);

        log.debug("Save old protected paths resources: {}", resources);
        if (forwardPath != null && resources.getForward() != null) {
            stateMachine.setOldProtectedForwardPath(makeFlowPathOldSnapshot(
                    stateMachine.getSharedOfFlowManager(), flow, forwardPath.getPathId(), resources.getForward()));
            stateMachine.setOldProtectedForwardPathStatus(forwardPath.getStatus());
        }
        if (reversePath != null && resources.getReverse() != null) {
            stateMachine.setOldProtectedReversePath(makeFlowPathOldSnapshot(
                    stateMachine.getSharedOfFlowManager(), flow, reversePath.getPathId(), resources.getReverse()));
            stateMachine.setOldProtectedReversePathStatus(reversePath.getStatus());
        }

        stateMachine.getOldResources().add(fixFlowResources(resources));
    }

    private FlowResources fixFlowResources(FlowResources rawResources) {
        if (rawResources.getForward() != null && rawResources.getReverse() != null) {
            return rawResources;
        }

        FlowResources.PathResources forward = rawResources.getForward();
        FlowResources.PathResources reverse = rawResources.getReverse();
        if (forward == null) {
            forward = reverse;
        }
        if (reverse == null) {
            reverse = forward;
        }
        return FlowResources.builder()
                .forward(forward)
                .reverse(reverse)
                .build();
    }

    private FlowResources fetchFlowResources(
            FlowPath forwardPath, FlowPath reversePath, FlowEncapsulationType encapsulationType) {
        EncapsulationResources forwardEncapsulationResources = fetchPathResources(
                forwardPath, reversePath, encapsulationType)
                .orElse(null);
        EncapsulationResources reverseEncapsulationResources = fetchPathResources(
                reversePath, forwardPath, encapsulationType)
                .orElse(forwardEncapsulationResources);

        if (forwardEncapsulationResources == null) {
            forwardEncapsulationResources = reverseEncapsulationResources;
        }

        return FlowResources.builder()
                .forward(makePathResources(forwardPath, forwardEncapsulationResources).orElse(null))
                .reverse(makePathResources(reversePath, reverseEncapsulationResources).orElse(null))
                .build();
    }

    private Optional<EncapsulationResources> fetchPathResources(
            FlowPath path, FlowPath oppositePath, FlowEncapsulationType encapsulationType) {
        if (path == null) {
            return Optional.empty();
        }

        PathId oppositePathId = oppositePath != null ? oppositePath.getPathId() : null;
        return resourcesManager.getEncapsulationResources(path.getPathId(), oppositePathId, encapsulationType);
    }

    private Optional<FlowResources.PathResources> makePathResources(
            FlowPath path, EncapsulationResources encapsulationResources) {
        if (path == null) {
            return Optional.empty();
        }

        return Optional.of(FlowResources.PathResources.builder()
                .pathId(path.getPathId())
                .meterId(path.getMeterId())
                .encapsulationResources(encapsulationResources)
                .build());
    }
}
