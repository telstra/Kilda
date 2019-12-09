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

package org.openkilda.wfm.topology.switchmanager.service.impl;

import static java.lang.String.format;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.command.switches.InstallExclusionForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.InstallTelescopeRuleForSwitchManagerRequest;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.rule.FlowMatchField;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowApplication;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Metadata;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.ApplicationRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.wfm.error.SwitchPropertiesNotFoundException;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.service.FlowCommandFactory;
import org.openkilda.wfm.topology.switchmanager.service.CommandBuilder;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.math.NumberUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class CommandBuilderImpl implements CommandBuilder {
    private final NoArgGenerator transactionIdGenerator = Generators.timeBasedGenerator();

    private FlowRepository flowRepository;
    private FlowPathRepository flowPathRepository;
    private FlowCommandFactory flowCommandFactory = new FlowCommandFactory();
    private FlowResourcesManager flowResourcesManager;
    private SwitchPropertiesRepository switchPropertiesRepository;
    private ApplicationRepository applicationRepository;

    public CommandBuilderImpl(PersistenceManager persistenceManager, FlowResourcesConfig flowResourcesConfig) {
        this.flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        this.flowResourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        this.switchPropertiesRepository = persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository();
        this.applicationRepository = persistenceManager.getRepositoryFactory().createApplicationRepository();
    }

    @Override
    public List<CommandData> buildCommandsToSyncMissingRules(SwitchId switchId, List<Long> switchRules)
            throws SwitchPropertiesNotFoundException {

        SwitchProperties switchProperties = switchPropertiesRepository.findBySwitchId(switchId)
                .orElseThrow(() -> new SwitchPropertiesNotFoundException(switchId));

        List<CommandData> commands = new ArrayList<>(buildInstallDefaultRuleCommands(switchId, switchRules));

        flowPathRepository.findBySegmentDestSwitch(switchId)
                .forEach(flowPath -> {
                    if (switchRules.contains(flowPath.getCookie().getValue())) {
                        PathSegment segment = flowPath.getSegments().stream()
                                .filter(pathSegment -> pathSegment.getDestSwitch().getSwitchId().equals(switchId))
                                .findAny()
                                .orElseThrow(() -> new IllegalStateException(
                                        format("PathSegment not found, path %s, switch %s", flowPath, switchId)));
                        log.info("Rule {} is to be (re)installed on switch {}", flowPath.getCookie(), switchId);
                        commands.addAll(buildInstallCommandFromSegment(flowPath, segment));
                    }
                });

        flowPathRepository.findByEndpointSwitch(switchId)
                .forEach(flowPath -> {
                    Flow flow = flowPath.getFlow();
                    EncapsulationResources encapsulationResources = null;
                    if (!flow.isOneSwitchFlow() || (flowPath.getApplications() != null
                            && flowPath.getApplications().contains(FlowApplication.TELESCOPE))) {
                        encapsulationResources = getEncapsulationResources(flow, flowPath);
                    }
                    if (switchRules.contains(flowPath.getCookie().getValue())) {

                        if (flowPath.isOneSwitchFlow()) {
                            log.info("One-switch flow {} is to be (re)installed on switch {}",
                                    flowPath.getCookie(), switchId);
                            commands.add(flowCommandFactory.makeOneSwitchRule(flow, flowPath, encapsulationResources));

                        } else if (flowPath.getSrcSwitch().getSwitchId().equals(switchId)) {
                            log.info("Ingress flow {} is to be (re)installed on switch {}",
                                    flowPath.getCookie(), switchId);
                            if (flowPath.getSegments().isEmpty()) {
                                log.warn("Output port was not found for ingress flow rule");
                            } else {
                                PathSegment foundIngressSegment = flowPath.getSegments().get(0);
                                commands.add(flowCommandFactory.buildInstallIngressFlow(flow, flowPath,
                                        foundIngressSegment.getSrcPort(), encapsulationResources,
                                        foundIngressSegment.isSrcWithMultiTable()));
                            }
                        }
                    }

                    boolean isForward = flow.isForward(flowPath);
                    long telescopeCookie = buildTelescopeCookie(flowPath, isForward);
                    if (flowPath.getApplications() != null
                            && flowPath.getApplications().contains(FlowApplication.TELESCOPE)
                            && switchRules.contains(telescopeCookie)) {

                        Objects.requireNonNull(switchProperties.getInboundTelescopePort(),
                                format("Telescope port for switch '%s' is not set", switchId));

                        int encapsulationId = encapsulationResources.getTransitEncapsulationId();
                        int telescopeVlan = isForward ? switchProperties.getTelescopeIngressVlan() :
                                switchProperties.getTelescopeEgressVlan();
                        int telescopePort = isForward ? switchProperties.getInboundTelescopePort() :
                                switchProperties.getOutboundTelescopePort();

                        commands.add(InstallTelescopeRuleForSwitchManagerRequest.switchManagerRequestBuilder()
                                .switchId(switchId)
                                .metadata(Metadata.builder().encapsulationId(encapsulationId).forward(isForward)
                                        .build())
                                .telescopeCookie(telescopeCookie)
                                .telescopePort(telescopePort)
                                .telescopeVlan(telescopeVlan)
                                .build());
                    }
                });

        applicationRepository.findBySwitchId(switchId)
                .forEach(appRule -> {
                    if (switchRules.contains(appRule.getCookie().getValue())) {
                        commands.add(InstallExclusionForSwitchManagerRequest.switchManagerRequestBuilder()
                                .cookie(appRule.getCookie().getValue())
                                .switchId(switchId)
                                .metadata(appRule.getMetadata())
                                .srcIp(appRule.getSrcIp())
                                .srcPort(appRule.getSrcPort())
                                .dstIp(appRule.getDstIp())
                                .dstPort(appRule.getDstPort())
                                .ethType(appRule.getEthType())
                                .proto(appRule.getProto())
                                .expirationTimeout(appRule.getExpirationTimeout())
                                .build());
                    }
                });

        return commands;
    }

    private long buildTelescopeCookie(FlowPath flowPath, boolean isForward) {
        Cookie cookie = flowPath.getCookie();
        return Cookie.buildTelescopeCookie(cookie.getUnmaskedValue(), isForward).getValue();
    }

    private EncapsulationResources getEncapsulationResources(Flow flow, FlowPath flowPath) {
        return flowResourcesManager.getEncapsulationResources(flowPath.getPathId(),
                flow.getOppositePathId(flowPath.getPathId())
                        .orElseThrow(() -> new IllegalStateException(format("Flow %s does not have reverse path for %s",
                                flow.getFlowId(), flowPath.getPathId()))),
                flow.getEncapsulationType())
                .orElseThrow(() -> new IllegalStateException(
                        format("Encapsulation resources are not found for path %s", flowPath)));
    }

    private List<BaseInstallFlow> buildInstallDefaultRuleCommands(SwitchId switchId, List<Long> switchRules) {
        List<BaseInstallFlow> commands = new ArrayList<>();
        switchRules.stream()
                .filter(Cookie::isDefaultRule)
                .map(cookie -> new BaseInstallFlow(transactionIdGenerator.generate(), "SWMANAGER_DEFAULT_RULE_INSTALL",
                        cookie, switchId, 0, 0, false))
                .forEach(commands::add);

        return commands;
    }

    @Override
    public List<RemoveFlow> buildCommandsToRemoveExcessRules(SwitchId switchId,
                                                             List<FlowEntry> flows,
                                                             List<Long> excessRulesCookies) {
        return flows.stream()
                .filter(flow -> excessRulesCookies.contains(flow.getCookie()))
                .map(entry -> buildRemoveFlowWithoutMeterFromFlowEntry(switchId, entry))
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    RemoveFlow buildRemoveFlowWithoutMeterFromFlowEntry(SwitchId switchId, FlowEntry entry) {
        Optional<FlowMatchField> entryMatch = Optional.ofNullable(entry.getMatch());
        Optional<FlowInstructions> instructions = Optional.ofNullable(entry.getInstructions());
        Optional<FlowApplyActions> applyActions = instructions.map(FlowInstructions::getApplyActions);

        Integer inPort = entryMatch.map(FlowMatchField::getInPort).map(Integer::valueOf).orElse(null);

        FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;
        Integer encapsulationId = null;
        Integer vlan = entryMatch.map(FlowMatchField::getVlanVid).map(Integer::valueOf).orElse(null);
        if (vlan != null) {
            encapsulationId = vlan;
        } else {
            Integer tunnelId = entryMatch.map(FlowMatchField::getTunnelId).map(Integer::valueOf).orElse(null);
            if (tunnelId == null) {
                tunnelId = applyActions.map(FlowApplyActions::getPushVxlan).map(Integer::valueOf).orElse(null);
            }

            if (tunnelId != null) {
                encapsulationId = tunnelId;
                encapsulationType = FlowEncapsulationType.VXLAN;
            }
        }

        Optional<FlowApplyActions> actions = Optional.ofNullable(entry.getInstructions())
                .map(FlowInstructions::getApplyActions);

        Integer outPort = actions
                .map(FlowApplyActions::getFlowOutput)
                .filter(NumberUtils::isNumber)
                .map(Integer::valueOf)
                .orElse(null);

        SwitchId ingressSwitchId = entryMatch.map(FlowMatchField::getEthSrc).map(SwitchId::new).orElse(null);

        DeleteRulesCriteria criteria = new DeleteRulesCriteria(entry.getCookie(), inPort, encapsulationId,
                0, outPort, encapsulationType, ingressSwitchId);

        return RemoveFlow.builder()
                .transactionId(transactionIdGenerator.generate())
                .flowId("SWMANAGER_BATCH_REMOVE")
                .cookie(entry.getCookie())
                .switchId(switchId)
                .criteria(criteria)
                .build();
    }

    private List<BaseInstallFlow> buildInstallCommandFromSegment(FlowPath flowPath, PathSegment segment) {
        if (segment.getSrcSwitch().getSwitchId().equals(segment.getDestSwitch().getSwitchId())) {
            log.warn("One-switch flow segment {} is provided", flowPath.getCookie());
            return new ArrayList<>();
        }

        Optional<Flow> foundFlow = flowRepository.findById(flowPath.getFlow().getFlowId());
        if (!foundFlow.isPresent()) {
            log.warn("Flow with id {} was not found", flowPath.getFlow().getFlowId());
            return new ArrayList<>();
        }
        Flow flow = foundFlow.get();

        EncapsulationResources encapsulationResources = getEncapsulationResources(flow, flowPath);

        if (segment.getDestSwitch().getSwitchId().equals(flowPath.getDestSwitch().getSwitchId())) {
            return Collections.singletonList(
                    flowCommandFactory.buildInstallEgressFlow(flowPath, segment.getDestPort(), encapsulationResources,
                            segment.isDestWithMultiTable()));
        } else {
            int segmentIdx = flowPath.getSegments().indexOf(segment);
            if (segmentIdx < 0 || segmentIdx + 1 == flowPath.getSegments().size()) {
                log.warn("Paired segment for switch {} and cookie {} has not been found",
                        segment.getDestSwitch().getSwitchId(), flowPath.getCookie());
                return new ArrayList<>();
            }

            PathSegment foundPairedFlowSegment = flowPath.getSegments().get(segmentIdx + 1);

            return Collections.singletonList(flowCommandFactory.buildInstallTransitFlow(
                    flowPath, segment.getDestSwitch().getSwitchId(), segment.getDestPort(),
                    foundPairedFlowSegment.getSrcPort(), encapsulationResources,
                    segment.isDestWithMultiTable()));
        }
    }
}
