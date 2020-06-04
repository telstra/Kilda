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
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext.PathContext;
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

    protected boolean isFlowTheLastUserOfServer42InputSharedRule(String flowId, SwitchId switchId, int port) {
        Set<String> flowIds = findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(switchId, port);
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
        boolean srcSwitchChanged = !oldFlow.getSrcSwitch().equals(newFlow.getSrcSwitch());

        return (srcPortChanged || srcSwitchChanged)
                && findFlowsIdsByEndpointWithMultiTable(oldFlow.getSrcSwitch(), oldFlow.getSrcPort()).isEmpty();
    }

    protected boolean removeReverseCustomerPortSharedCatchRule(RequestedFlow oldFlow, RequestedFlow newFlow) {
        boolean dstPortChanged = oldFlow.getDestPort() != newFlow.getDestPort();
        boolean dstSwitchChanged = !oldFlow.getDestSwitch().equals(newFlow.getDestSwitch());

        return (dstPortChanged || dstSwitchChanged)
                && findFlowsIdsByEndpointWithMultiTable(oldFlow.getDestSwitch(), oldFlow.getDestPort()).isEmpty();
    }

    protected boolean removeForwardSharedServer42InputRule(
            RequestedFlow oldFlow, RequestedFlow newFlow, boolean server42Rtt) {
        return server42Rtt && oldFlow.getSrcPort() != newFlow.getSrcPort() // src port changed
                && findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(
                        oldFlow.getSrcSwitch(), oldFlow.getSrcPort()).isEmpty();
    }

    protected boolean removeReverseSharedServer42InputRule(
            RequestedFlow oldFlow, RequestedFlow newFlow, boolean server42Rtt) {
        return server42Rtt && oldFlow.getDestPort() != newFlow.getDestPort() // dst port changed
                && findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(
                        oldFlow.getDestSwitch(), oldFlow.getDestPort()).isEmpty();
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

    protected boolean removeOuterVlanMatchSharedRule(String flowId, FlowEndpoint current, FlowEndpoint goal) {
        if (current.isSwitchPortEquals(goal)
                && current.getOuterVlanId() == goal.getOuterVlanId()) {
            return false;
        }
        return findOuterVlanMatchSharedRuleUsage(current).stream()
                .allMatch(entry -> flowId.equals(entry.getFlowId()));
    }

    protected boolean removeServer42OuterVlanMatchSharedRule(String flowId, FlowEndpoint current, FlowEndpoint goal) {
        if (current.getSwitchId().equals(goal.getSwitchId()) && current.getOuterVlanId() == goal.getOuterVlanId()) {
            return false;
        }
        return findServer42OuterVlanMatchSharedRuleUsage(current).stream()
                .allMatch(flowId::equals);
    }

    protected SpeakerRequestBuildContext buildSpeakerContextForRemovalIngressAndShared(
            RequestedFlow oldFlow, RequestedFlow newFlow) {
        return buildSpeakerContextForRemovalIngressAndShared(oldFlow, newFlow, true);
    }

    protected SpeakerRequestBuildContext buildSpeakerContextForRemovalIngressAndShared(
            RequestedFlow oldFlow, RequestedFlow newFlow, boolean removeMeters) {
        SwitchProperties srcSwitchProperties = getSwitchProperties(oldFlow.getSrcSwitch());
        boolean server42FlowRttToggle = isServer42FlowRttFeatureToggle();

        FlowEndpoint oldIngress = new FlowEndpoint(
                oldFlow.getSrcSwitch(), oldFlow.getSrcPort(), oldFlow.getSrcVlan(), oldFlow.getSrcInnerVlan());
        FlowEndpoint oldEgress = new FlowEndpoint(
                oldFlow.getDestSwitch(), oldFlow.getDestPort(), oldFlow.getDestVlan(), oldFlow.getDestInnerVlan());

        FlowEndpoint newIngress = new FlowEndpoint(
                newFlow.getSrcSwitch(), newFlow.getSrcPort(), newFlow.getSrcVlan(), newFlow.getSrcInnerVlan());
        FlowEndpoint newEgress = new FlowEndpoint(
                newFlow.getDestSwitch(), newFlow.getDestPort(), newFlow.getDestVlan(), newFlow.getDestInnerVlan());

        PathContext forwardPathContext = PathContext.builder()
                .removeCustomerPortRule(removeForwardCustomerPortSharedCatchRule(oldFlow, newFlow))
                .removeCustomerPortLldpRule(removeForwardSharedLldpRule(oldFlow, newFlow))
                .removeCustomerPortArpRule(removeForwardSharedArpRule(oldFlow, newFlow))
                .removeOuterVlanMatchSharedRule(
                        removeOuterVlanMatchSharedRule(oldFlow.getFlowId(), oldIngress, newIngress))
                .removeServer42InputRule(removeForwardSharedServer42InputRule(
                        oldFlow, newFlow, srcSwitchProperties.isServer42FlowRtt() && server42FlowRttToggle))
                .removeServer42IngressRule(srcSwitchProperties.isServer42FlowRtt() && server42FlowRttToggle)
                .updateMeter(removeMeters)
                .removeServer42OuterVlanMatchSharedRule(removeServer42OuterVlanMatchSharedRule(
                        oldFlow.getFlowId(), oldIngress, newIngress))
                .server42Port(srcSwitchProperties.getServer42Port())
                .server42MacAddress(srcSwitchProperties.getServer42MacAddress())
                .build();

        SwitchProperties dstSwitchProperties = getSwitchProperties(oldFlow.getDestSwitch());

        PathContext reversePathContext = PathContext.builder()
                .removeCustomerPortRule(removeReverseCustomerPortSharedCatchRule(oldFlow, newFlow))
                .removeCustomerPortLldpRule(removeReverseSharedLldpRule(oldFlow, newFlow))
                .removeCustomerPortArpRule(removeReverseSharedArpRule(oldFlow, newFlow))
                .removeOuterVlanMatchSharedRule(
                        removeOuterVlanMatchSharedRule(oldFlow.getFlowId(), oldEgress, newEgress))
                .removeServer42InputRule(removeReverseSharedServer42InputRule(
                        oldFlow, newFlow, dstSwitchProperties.isServer42FlowRtt() && server42FlowRttToggle))
                .removeServer42IngressRule(dstSwitchProperties.isServer42FlowRtt() && server42FlowRttToggle)
                .updateMeter(removeMeters)
                .removeServer42OuterVlanMatchSharedRule(removeServer42OuterVlanMatchSharedRule(
                        oldFlow.getFlowId(), oldEgress, newEgress))
                .server42Port(dstSwitchProperties.getServer42Port())
                .server42MacAddress(dstSwitchProperties.getServer42MacAddress())
                .build();

        return SpeakerRequestBuildContext.builder()
                .forward(forwardPathContext)
                .reverse(reversePathContext)
                .build();
    }

    protected SpeakerRequestBuildContext buildSpeakerContextForRemovalIngressOnly(
            SwitchId srcSwitchId, SwitchId dstSwitchId) {
        return SpeakerRequestBuildContext.builder()
                .forward(buildPathContextForRemovalIngressOnly(srcSwitchId))
                .reverse(buildPathContextForRemovalIngressOnly(dstSwitchId))
                .build();
    }

    protected PathContext buildPathContextForRemovalIngressOnly(SwitchId switchId) {
        SwitchProperties switchProperties = getSwitchProperties(switchId);
        return PathContext.builder()
                .removeServer42IngressRule(switchProperties.isServer42FlowRtt() && isServer42FlowRttFeatureToggle())
                .server42Port(switchProperties.getServer42Port())
                .server42MacAddress(switchProperties.getServer42MacAddress())
                .build();
    }
}
