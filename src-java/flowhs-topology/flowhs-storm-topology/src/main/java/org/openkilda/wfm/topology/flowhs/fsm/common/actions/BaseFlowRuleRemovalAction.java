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

import org.openkilda.model.Flow;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowProcessingFsm;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilderFactory;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A base for action classes that remove or revert flow rules.
 */
@Slf4j
public abstract class BaseFlowRuleRemovalAction<T extends FlowProcessingFsm<T, S, E, C>, S, E, C> extends
        FlowProcessingAction<T, S, E, C> {
    protected final FlowCommandBuilderFactory commandBuilderFactory;

    public BaseFlowRuleRemovalAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        this.commandBuilderFactory = new FlowCommandBuilderFactory(resourcesManager);
    }

    protected boolean isRemoveCustomerPortSharedCatchRule(String flowId, SwitchId ingressSwitchId, int ingressPort) {
        Set<String> flowIds = findFlowsIdsByEndpointWithMultiTable(ingressSwitchId, ingressPort);
        return flowIds.size() == 1 && flowIds.iterator().next().equals(flowId);
    }

    protected boolean isFlowTheLastUserOfSharedLldpPortRule(
            String flowId, SwitchId ingressSwitchId, int ingressPort) {
        List<Flow> flows = getFlowsOnSwitchEndpointExcludeCurrentFlow(flowId, ingressSwitchId, ingressPort);

        if (getSwitchProperties(ingressSwitchId).isSwitchLldp()) {
            return flows.isEmpty();
        }

        List<Flow> flowsWithLldp = flows.stream()
                .filter(f -> f.getSrcPort() == ingressPort && f.getDetectConnectedDevices().isSrcLldp()
                        || f.getDestPort() == ingressPort && f.getDetectConnectedDevices().isDstLldp())
                .collect(Collectors.toList());

        return flowsWithLldp.isEmpty();
    }

    protected boolean isFlowTheLastUserOfSharedArpPortRule(
            String flowId, SwitchId ingressSwitchId, int ingressPort) {
        List<Flow> flows = getFlowsOnSwitchEndpointExcludeCurrentFlow(flowId, ingressSwitchId, ingressPort);

        if (getSwitchProperties(ingressSwitchId).isSwitchArp()) {
            return flows.isEmpty();
        }

        return flows.stream()
                .noneMatch(f -> f.getSrcPort() == ingressPort && f.getDetectConnectedDevices().isSrcArp()
                        || f.getDestPort() == ingressPort && f.getDetectConnectedDevices().isDstArp());
    }

    private List<Flow> getFlowsOnSwitchEndpointExcludeCurrentFlow(
            String flowId, SwitchId ingressSwitchId, int ingressPort) {
        return flowRepository.findByEndpoint(ingressSwitchId, ingressPort).stream()
                .filter(f -> !f.getFlowId().equals(flowId))
                .collect(Collectors.toList());
    }

    protected boolean removeForwardCustomerPortSharedCatchRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean srcPortChanged = oldFlow.getSrcPort() != newFlow.getSrcPort();

        return srcPortChanged
                && findFlowsIdsByEndpointWithMultiTable(oldFlow.getSrcSwitch(), oldFlow.getSrcPort()).isEmpty();
    }

    protected boolean removeReverseCustomerPortSharedCatchRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean dstPortChanged = oldFlow.getDestPort() != newFlow.getDestPort();

        return dstPortChanged
                && findFlowsIdsByEndpointWithMultiTable(oldFlow.getDestSwitch(), oldFlow.getDestPort()).isEmpty();
    }

    protected boolean removeForwardSharedLldpRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean srcLldpWasSwitchedOff = oldFlow.getDetectConnectedDevices().isSrcLldp()
                && !newFlow.getDetectConnectedDevices().isSrcLldp();

        return srcLldpWasSwitchedOff && isFlowTheLastUserOfSharedLldpPortRule(
                oldFlow.getFlowId(), oldFlow.getSrcSwitch(), oldFlow.getSrcPort());
    }

    protected boolean removeReverseSharedLldpRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean dstLldpWasSwitchedOff = oldFlow.getDetectConnectedDevices().isDstLldp()
                && !newFlow.getDetectConnectedDevices().isDstLldp();

        return dstLldpWasSwitchedOff && isFlowTheLastUserOfSharedLldpPortRule(
                oldFlow.getFlowId(), oldFlow.getDestSwitch(), oldFlow.getDestPort());
    }

    protected boolean removeForwardSharedArpRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean srcArpWasSwitchedOff = oldFlow.getDetectConnectedDevices().isSrcArp()
                && !newFlow.getDetectConnectedDevices().isSrcArp();

        return srcArpWasSwitchedOff && isFlowTheLastUserOfSharedArpPortRule(
                oldFlow.getFlowId(), oldFlow.getSrcSwitch(), oldFlow.getSrcPort());
    }

    protected boolean removeReverseSharedArpRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean dstArpWasSwitchedOff = oldFlow.getDetectConnectedDevices().isDstArp()
                && !newFlow.getDetectConnectedDevices().isDstArp();

        return dstArpWasSwitchedOff && isFlowTheLastUserOfSharedArpPortRule(
                oldFlow.getFlowId(), oldFlow.getDestSwitch(), oldFlow.getDestPort());
    }
}
