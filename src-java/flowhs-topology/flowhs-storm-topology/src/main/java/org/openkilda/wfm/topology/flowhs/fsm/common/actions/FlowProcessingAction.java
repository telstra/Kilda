/* Copyright 2020 Telstra Open Source
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

import org.openkilda.adapter.FlowDestAdapter;
import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.adapter.FlowSourceAdapter;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.mappers.FlowMapper;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext.PathContext;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowProcessingFsm;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.AnonymousAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public abstract class FlowProcessingAction<T extends FlowProcessingFsm<T, S, E, C>, S, E, C>
        extends AnonymousAction<T, S, E, C> {

    protected final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    protected final PersistenceManager persistenceManager;
    protected final TransactionManager transactionManager;
    protected final FlowRepository flowRepository;
    protected final FlowPathRepository flowPathRepository;
    protected final SwitchPropertiesRepository switchPropertiesRepository;
    protected final SwitchRepository switchRepository;
    protected final FeatureTogglesRepository featureTogglesRepository;

    public FlowProcessingAction(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
        this.transactionManager = persistenceManager.getTransactionManager();
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.flowRepository = repositoryFactory.createFlowRepository();
        this.flowPathRepository = repositoryFactory.createFlowPathRepository();
        this.switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
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

    protected Set<String> findFlowsIdsByEndpointWithMultiTable(SwitchId switchId, int port) {
        return new HashSet<>(flowRepository.findFlowsIdsByEndpointWithMultiTableSupport(switchId, port));
    }

    protected Set<String> findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(SwitchId switchId, int port) {
        return new HashSet<>(
                flowRepository.findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(switchId, port));
    }

    protected List<Flow> findOuterVlanMatchSharedRuleUsage(FlowEndpoint needle) {
        if (! FlowEndpoint.isVlanIdSet(needle.getOuterVlanId())) {
            return Collections.emptyList();
        }

        List<Flow> results = new ArrayList<>();
        for (Flow flow : flowRepository.findByEndpoint(needle.getSwitchId(), needle.getPortNumber())) {
            for (FlowSideAdapter flowSide : new FlowSideAdapter[] {
                    new FlowSourceAdapter(flow),
                    new FlowDestAdapter(flow)}) {
                FlowEndpoint endpoint = flowSide.getEndpoint();
                if (needle.isSwitchPortEquals(endpoint) && needle.getOuterVlanId() == endpoint.getOuterVlanId()) {
                    boolean multitableEnabled = flow.getPaths().stream()
                            .filter(path -> flow.isActualPathId(path.getPathId()))
                            .filter(path -> !path.isProtected())
                            .filter(path -> path.getSrcSwitchId().equals(endpoint.getSwitchId()))
                            .anyMatch(FlowPath::isSrcWithMultiTable);
                    if (multitableEnabled) {
                        results.add(flow);
                        break;
                    }
                }
            }
        }
        return results;
    }

    protected Set<String> getDiverseWithFlowIds(Flow flow) {
        return flow.getGroupId() == null ? Collections.emptySet() :
                flowRepository.findFlowsIdByGroupId(flow.getGroupId()).stream()
                        .filter(flowId -> !flowId.equals(flow.getFlowId()))
                        .collect(Collectors.toSet());
    }

    protected SwitchProperties getSwitchProperties(SwitchId ingressSwitchId) {
        return switchPropertiesRepository.findBySwitchId(ingressSwitchId)
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Properties for switch %s not found", ingressSwitchId)));
    }

    protected SpeakerRequestBuildContext buildBaseSpeakerContextForInstall(SwitchId srcSwitchId, SwitchId dstSwitchId) {
        return SpeakerRequestBuildContext.builder()
                .forward(buildBasePathContextForInstall(srcSwitchId))
                .reverse(buildBasePathContextForInstall(dstSwitchId))
                .build();
    }

    protected PathContext buildBasePathContextForInstall(SwitchId switchId) {
        SwitchProperties switchProperties = getSwitchProperties(switchId);
        boolean serverFlowRtt = switchProperties.isServer42FlowRtt() && isServer42FlowRttFeatureToggle();
        return PathContext.builder()
                .installServer42InputRule(serverFlowRtt && switchProperties.isMultiTable())
                .installServer42IngressRule(serverFlowRtt)
                .server42Port(switchProperties.getServer42Port())
                .server42MacAddress(switchProperties.getServer42MacAddress())
                .build();
    }

    protected boolean isServer42FlowRttFeatureToggle() {
        return featureTogglesRepository.find().map(FeatureToggles::getServer42FlowRtt).orElse(false);
    }

    protected Message buildResponseMessage(Flow flow, CommandContext commandContext) {
        InfoData flowData = new FlowResponse(FlowMapper.INSTANCE.map(flow, getDiverseWithFlowIds(flow)));
        return new InfoMessage(flowData, commandContext.getCreateTime(),
                commandContext.getCorrelationId());
    }
}
