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

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class ResourcesDeallocationAction extends FlowProcessingAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    private final TransactionManager transactionManager;
    private final FlowResourcesManager resourcesManager;
    private final IslRepository islRepository;

    public ResourcesDeallocationAction(FlowResourcesManager resourcesManager, PersistenceManager persistenceManager) {
        super(persistenceManager);

        this.transactionManager = persistenceManager.getTransactionManager();
        this.resourcesManager = resourcesManager;
        this.islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
    }

    @Override
    protected void perform(State from, State to, Event event, FlowCreateContext context, FlowCreateFsm stateMachine) {
        Flow flow;
        try {
            flow = getFlow(stateMachine.getFlowId());
        } catch (FlowProcessingException e) {
            stateMachine.saveActionToHistory("Skip resources deallocation",
                    format("Skip resources deallocation. Flow %s has already been deleted: %s",
                            stateMachine.getFlowId(), e.getMessage()));
            return;
        }

        Collection<FlowResources> flowResources = stateMachine.getFlowResources();
        transactionManager.doInTransaction(() -> {
            for (FlowResources resources : flowResources) {
                resourcesManager.deallocatePathResources(resources);
                FlowPath forward = getFlowPath(resources.getForward().getPathId());
                FlowPath reverse = getFlowPath(resources.getReverse().getPathId());
                Stream.of(forward, reverse)
                        .peek(flowPathRepository::remove)
                        .map(FlowPath::getSegments)
                        .flatMap(List::stream)
                        .forEach(segment -> updateIslAvailableBandwidth(stateMachine.getFlowId(), segment));

                flow.resetPaths();
            }
        });

        stateMachine.saveActionToHistory("The resources have been deallocated");
    }

    private void updateIslAvailableBandwidth(String flowId, PathSegment pathSegment) {
        SwitchId srcSwitch = pathSegment.getSrcSwitchId();
        SwitchId destSwitch = pathSegment.getDestSwitchId();
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(
                srcSwitch, pathSegment.getSrcPort(), destSwitch, pathSegment.getDestPort());

        islRepository.findByEndpoints(srcSwitch, pathSegment.getSrcPort(), destSwitch, pathSegment.getDestPort())
                .ifPresent(isl -> {
                    isl.setAvailableBandwidth(isl.getMaxBandwidth() - usedBandwidth);
                    log.debug("Released used bandwidth from flow {} on the link {}", flowId, isl);
                });
    }
}
