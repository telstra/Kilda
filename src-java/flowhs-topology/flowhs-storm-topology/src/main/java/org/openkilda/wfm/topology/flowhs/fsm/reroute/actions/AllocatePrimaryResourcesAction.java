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

import org.openkilda.messaging.info.reroute.error.NoPathFoundError;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseResourceAllocationAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class AllocatePrimaryResourcesAction extends
        BaseResourceAllocationAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public AllocatePrimaryResourcesAction(PersistenceManager persistenceManager, int transactionRetriesLimit,
                                          int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                                          PathComputer pathComputer, FlowResourcesManager resourcesManager,
                                          FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager, transactionRetriesLimit, pathAllocationRetriesLimit, pathAllocationRetryDelay,
                pathComputer, resourcesManager, dashboardLogger);
    }

    @Override
    protected boolean isAllocationRequired(FlowRerouteFsm stateMachine) {
        return stateMachine.isReroutePrimary();
    }

    @Override
    protected void allocate(FlowRerouteFsm stateMachine)
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        String flowId = stateMachine.getFlowId();
        Flow flow = getFlow(flowId);

        if (stateMachine.getNewEncapsulationType() != null) {
            // This is for PCE to use proper (updated) encapsulation type.
            flow.setEncapsulationType(stateMachine.getNewEncapsulationType());
        }



        log.debug("Finding a new primary path for flow {}", flowId);
        boolean originalIgnoreBandwidth = flow.isIgnoreBandwidth();
        flow.setIgnoreBandwidth(stateMachine.isIgnoreBandwidth() || originalIgnoreBandwidth);
        PathPair potentialPath = pathComputer.getPath(flow, flow.getFlowPathIds());
        flow.setIgnoreBandwidth(originalIgnoreBandwidth);
        FlowPathPair oldPaths = FlowPathPair.builder()
                .forward(flow.getForwardPath())
                .reverse(flow.getReversePath())
                .build();
        boolean newPathFound = isNotSamePath(potentialPath, oldPaths);
        if (newPathFound || stateMachine.isRecreateIfSamePath()) {
            if (!newPathFound) {
                log.debug("Found the same primary path for flow {}. Proceed with recreating it", flowId);
            }

            log.debug("Allocating resources for a new primary path of flow {}", flowId);
            FlowResources flowResources = resourcesManager.allocateFlowResources(flow);
            log.debug("Resources have been allocated: {}", flowResources);
            stateMachine.setNewPrimaryResources(flowResources);

            List<FlowPath> pathsToReuse = Lists.newArrayList(flow.getForwardPath(), flow.getReversePath());
            FlowPathPair newPaths = createFlowPathPair(flow, pathsToReuse, potentialPath, flowResources,
                    stateMachine.isIgnoreBandwidth());
            log.debug("New primary path has been created: {}", newPaths);
            stateMachine.setNewPrimaryForwardPath(newPaths.getForward().getPathId());
            stateMachine.setNewPrimaryReversePath(newPaths.getReverse().getPathId());

            saveAllocationActionWithDumpsToHistory(stateMachine, flow, "primary", newPaths);
        } else {
            stateMachine.saveActionToHistory("Found the same primary path. Skipped creating of it");
        }
    }

    @Override
    protected void onFailure(FlowRerouteFsm stateMachine) {
        stateMachine.setNewPrimaryResources(null);
        stateMachine.setNewPrimaryForwardPath(null);
        stateMachine.setNewPrimaryReversePath(null);
        if (!stateMachine.isIgnoreBandwidth()) {
            stateMachine.setRerouteError(new NoPathFoundError());
        }
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not reroute flow";
    }
}
