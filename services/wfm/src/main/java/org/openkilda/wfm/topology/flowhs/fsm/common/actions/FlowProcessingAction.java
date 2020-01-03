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

import static java.lang.String.format;

import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.SharedOfFlow;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.FetchStrategy;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.model.FlowPathSnapshot;
import org.openkilda.wfm.share.model.SharedOfFlowStatus;
import org.openkilda.wfm.share.service.SharedOfFlowManager;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowProcessingFsm;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.AnonymousAction;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public abstract class FlowProcessingAction<T extends FlowProcessingFsm<T, S, E, C>, S, E, C>
        extends AnonymousAction<T, S, E, C> {

    protected final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    protected final PersistenceManager persistenceManager;
    protected final FlowRepository flowRepository;
    protected final FlowPathRepository flowPathRepository;
    protected final SwitchPropertiesRepository switchPropertiesRepository;

    public FlowProcessingAction(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.flowRepository = repositoryFactory.createFlowRepository();
        this.flowPathRepository = repositoryFactory.createFlowPathRepository();
        this.switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
    }

    @Override
    public final void execute(S from, S to, E event, C context, T stateMachine) {
        try {
            perform(from, to, event, context, stateMachine);
        } catch (Exception ex) {
            String errorMessage = format("%s failed: %s", getClass().getSimpleName(), ex.getMessage());
            stateMachine.saveErrorToHistory(errorMessage, ex);
            stateMachine.fireError(errorMessage);
        }
    }

    protected abstract void perform(S from, S to, E event, C context, T stateMachine);

    protected Flow getFlow(String flowId) {
        return flowRepository.findById(flowId)
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Flow %s not found", flowId)));
    }

    protected Flow getFlow(String flowId, FetchStrategy fetchStrategy) {
        return flowRepository.findById(flowId, fetchStrategy)
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Flow %s not found", flowId)));
    }

    protected FlowPathSnapshot getFlowPath(FlowPathSnapshot pathSnapshot) {
        return pathSnapshot.toBuilder()
                .path(getFlowPath(pathSnapshot.getPath().getPathId()))
                .build();
    }

    protected FlowPathSnapshot getFlowPath(Flow flow, FlowPathSnapshot pathSnapshot) {
        return pathSnapshot.toBuilder()
                .path(getFlowPath(flow, pathSnapshot.getPath().getPathId()))
                .build();
    }

    protected FlowPath getFlowPath(Flow flow, PathId pathId) {
        return flow.getPath(pathId)
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Flow path %s not found", pathId)));
    }

    protected FlowPath getFlowPath(PathId pathId) {
        return flowPathRepository.findById(pathId)
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Flow path %s not found", pathId)));
    }

    protected SharedOfFlowManager makeSharedOfFlowManager(Flow flow) {
        List<PathId> pathToIgnore = Stream.of(flow.getForwardPathId(), flow.getReversePathId(),
                flow.getProtectedForwardPathId(), flow.getProtectedReversePathId())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new SharedOfFlowManager(persistenceManager, pathToIgnore);
    }

    protected FlowPathSnapshot makeFlowPathNewSnapshot(
            SharedOfFlowManager sharedOfFlowManager, Flow flow, FlowPath path, FlowResources.PathResources resources)
            throws ResourceAllocationException {
        FlowPathSnapshot.FlowPathSnapshotBuilder pathSnapshot = FlowPathSnapshot.builder(path).resources(resources);
        createSharedOfFlowsReferences(sharedOfFlowManager, pathSnapshot, flow, path);
        return pathSnapshot.build();
    }

    protected FlowPathSnapshot makeFlowPathOldSnapshot(
            SharedOfFlowManager sharedOfFlowManager, Flow flow, PathId pathId, PathResources resources) {
        FlowPath path = getFlowPath(pathId);  // need to ensure that all relations are loaded
        FlowPathSnapshot.FlowPathSnapshotBuilder pathSnapshot = FlowPathSnapshot.builder(path)
                .resources(resources);
        removeSharedOfFlowsReferences(sharedOfFlowManager, pathSnapshot, path);
        checkIsSharedCustomerPortForwardingFlowCanBeRemoved(pathSnapshot, flow, path);
        return pathSnapshot.build();
    }

    protected void createSharedOfFlowsReferences(
            SharedOfFlowManager sharedOfFlowManager, FlowPathSnapshot.FlowPathSnapshotBuilder pathSnapshot,
            Flow flow, FlowPath path) throws ResourceAllocationException {
        FlowSideAdapter ingressSide = FlowSideAdapter.makeIngressAdapter(flow, path);
        createSharedOfFlowsReferences(sharedOfFlowManager, pathSnapshot, ingressSide, path);
    }

    protected void createSharedOfFlowsReferences(
            SharedOfFlowManager sharedOfFlowManager, FlowPathSnapshot.FlowPathSnapshotBuilder pathSnapshot,
            FlowSideAdapter flowSide, FlowPath path) throws ResourceAllocationException {
        FlowEndpoint endpoint = flowSide.getEndpoint();
        if (flowSide.isMultiTableSegment()
                && isSwitchInMultiTableMode(endpoint.getSwitchId())
                && FlowEndpoint.isVlanIdSet(endpoint.getOuterVlanId())) {
            SharedOfFlowStatus sharedStatus = sharedOfFlowManager.bindIngressFlowSegmentOuterVlanMatchFlow(
                    endpoint, path);
            pathSnapshot.sharedIngressSegmentOuterVlanMatchStatus(sharedStatus);
        }
    }

    private void removeSharedOfFlowsReferences(
            SharedOfFlowManager sharedOfFlowManager, FlowPathSnapshot.FlowPathSnapshotBuilder pathSnapshot,
            FlowPath path) {
        Set<SharedOfFlow> pathSharedFlowReferences = new HashSet<>(path.getSharedOfFlows());
        for (SharedOfFlow reference : pathSharedFlowReferences) {
            SharedOfFlowStatus status = sharedOfFlowManager.removeBinding(reference, path);
            log.debug(
                    "Evaluate status of shared OF flow for path {} for reference {} - {}",
                    path.getPathId(), reference, status);
            if (reference.getType() == SharedOfFlow.SharedOfFlowType.INGRESS_OUTER_VLAN_MATCH) {
                pathSnapshot.sharedIngressSegmentOuterVlanMatchStatus(status);
            } else {
                log.error("Unknown shared OF Flow type {} - {}", reference.getType(), reference);
            }
        }
    }

    private boolean isSwitchInMultiTableMode(SwitchId switchId) {
        SwitchProperties props = switchPropertiesRepository.findBySwitchId(switchId)
                .orElseThrow(() -> new IllegalStateException(String.format(
                        "Unable to locate switch properties record for %s", switchId)));
        return props.isMultiTable();
    }

    protected void checkIsSharedCustomerPortForwardingFlowCanBeRemoved(
            FlowPathSnapshot.FlowPathSnapshotBuilder pathSnapshot, Flow flow, FlowPath path) {
        FlowSideAdapter flowSide = FlowSideAdapter.makeIngressAdapter(flow, path);
        FlowEndpoint endpoint = flowSide.getEndpoint();

        Collection<Flow> occupiedByFlows = flowRepository.findByEndpointWithMultiTableSupport(
                endpoint.getSwitchId(), endpoint.getPortNumber());
        long refsCount = occupiedByFlows.stream()
                .map(Flow::getFlowId)
                .filter(entry -> !flow.getFlowId().equals(entry))
                .count();
        pathSnapshot.removeCustomerPortSharedCatchRule(refsCount == 0);
    }
}
