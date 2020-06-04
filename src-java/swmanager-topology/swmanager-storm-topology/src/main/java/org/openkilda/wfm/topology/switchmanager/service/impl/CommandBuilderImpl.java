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
import static org.openkilda.model.cookie.Cookie.SERVER_42_OUTPUT_VLAN_COOKIE;
import static org.openkilda.model.cookie.Cookie.SERVER_42_OUTPUT_VXLAN_COOKIE;

import org.openkilda.messaging.command.flow.BaseFlow;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.InstallServer42Flow;
import org.openkilda.messaging.command.flow.InstallServer42Flow.InstallServer42FlowBuilder;
import org.openkilda.messaging.command.flow.InstallSharedFlow;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.rule.FlowMatchField;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.cookie.Cookie;
import org.openkilda.model.cookie.CookieBase.CookieType;
import org.openkilda.model.cookie.FlowSharedSegmentCookie;
import org.openkilda.model.cookie.FlowSharedSegmentCookie.SharedSegmentType;
import org.openkilda.model.cookie.PortColourCookie;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
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
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class CommandBuilderImpl implements CommandBuilder {
    private final NoArgGenerator transactionIdGenerator = Generators.timeBasedGenerator();

    private FlowRepository flowRepository;
    private FlowPathRepository flowPathRepository;
    private SwitchPropertiesRepository switchPropertiesRepository;
    private FlowCommandFactory flowCommandFactory = new FlowCommandFactory();
    private FlowResourcesManager flowResourcesManager;

    public CommandBuilderImpl(PersistenceManager persistenceManager, FlowResourcesConfig flowResourcesConfig) {
        this.flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        this.switchPropertiesRepository = persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository();
        this.flowResourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
    }

    @Override
    public List<BaseFlow> buildCommandsToSyncMissingRules(SwitchId switchId, List<Long> switchRules) {

        List<BaseFlow> commands = new ArrayList<>(buildInstallDefaultRuleCommands(switchId, switchRules));
        commands.addAll(buildInstallFlowSharedRuleCommands(switchId, switchRules));

        flowPathRepository.findBySegmentDestSwitch(switchId)
                .forEach(flowPath -> {
                    if (switchRules.contains(flowPath.getCookie().getValue())) {
                        PathSegment segment = flowPath.getSegments().stream()
                                .filter(pathSegment -> pathSegment.getDestSwitchId().equals(switchId))
                                .findAny()
                                .orElseThrow(() -> new IllegalStateException(
                                        format("PathSegment not found, path %s, switch %s", flowPath, switchId)));
                        log.info("Rule {} is to be (re)installed on switch {}", flowPath.getCookie(), switchId);
                        commands.addAll(buildInstallCommandFromSegment(flowPath, segment));
                    }
                });

        SwitchProperties switchProperties = getSwitchProperties(switchId);
        flowPathRepository.findByEndpointSwitch(switchId)
                .forEach(flowPath -> {
                    if (switchRules.contains(flowPath.getCookie().getValue())) {
                        Flow flow = getFlow(flowPath);
                        if (flowPath.isOneSwitchFlow()) {
                            log.info("One-switch flow {} is to be (re)installed on switch {}",
                                    flowPath.getCookie(), switchId);
                            commands.add(flowCommandFactory.makeOneSwitchRule(flow, flowPath));
                        } else if (flowPath.getSrcSwitchId().equals(switchId)) {
                            log.info("Ingress flow {} is to be (re)installed on switch {}",
                                    flowPath.getCookie(), switchId);
                            if (flowPath.getSegments().isEmpty()) {
                                log.warn("Output port was not found for ingress flow rule");
                            } else {
                                PathSegment foundIngressSegment = flowPath.getSegments().get(0);
                                EncapsulationResources encapsulationResources = getEncapsulationResources(
                                        flowPath, flow);
                                commands.add(flowCommandFactory.buildInstallIngressFlow(flow, flowPath,
                                        foundIngressSegment.getSrcPort(), encapsulationResources,
                                        foundIngressSegment.isSrcWithMultiTable()));
                            }
                        }
                    }

                    long server42Cookie = flowPath.getCookie().toBuilder()
                            .type(CookieType.SERVER_42_INGRESS)
                            .build()
                            .getValue();
                    if (switchRules.contains(server42Cookie) && !flowPath.isOneSwitchFlow()
                            && flowPath.getSrcSwitchId().equals(switchId)) {
                        log.info("Ingress server 42 flow {} is to be (re)installed on switch {}",
                                server42Cookie, switchId);

                        if (flowPath.getSegments().isEmpty()) {
                            log.warn("Output port was not found for server 42 ingress flow rule {}", server42Cookie);
                        } else {
                            Flow flow = getFlow(flowPath);
                            PathSegment foundIngressSegment = flowPath.getSegments().get(0);
                            EncapsulationResources encapsulationResources = getEncapsulationResources(flowPath, flow);
                            commands.add(flowCommandFactory.buildInstallServer42IngressFlow(
                                    flow, flowPath, foundIngressSegment.getSrcPort(),
                                    switchProperties.getServer42Port(), switchProperties.getServer42MacAddress(),
                                    encapsulationResources, foundIngressSegment.isSrcWithMultiTable()));
                        }
                    }

                    long loopCookie = flowPath.getCookie().toBuilder().looped(true).build().getValue();
                    if (switchRules.contains(loopCookie)) {
                        log.info("Loop rule with cookie {} is to be reinstalled on switch {}", loopCookie, switchId);
                        Flow flow = getFlow(flowPath);
                        EncapsulationResources encapsulationResources = getEncapsulationResources(
                                flowPath, flow);
                        if (flowPath.getSrcSwitch().getSwitchId().equals(switchId)) {
                            boolean srcWithMultiTable = flowPath.getSegments().get(0).isSrcWithMultiTable();
                            commands.add(flowCommandFactory.buildInstallIngressLoopFlow(flow, flowPath,
                                    encapsulationResources, srcWithMultiTable));
                        } else {
                            PathSegment lastSegment = flowPath.getSegments().get(flowPath.getSegments().size() - 1);
                            boolean destWithMultiTable = lastSegment.isDestWithMultiTable();
                            commands.add(flowCommandFactory.buildInstallTransitLoopFlow(flow, flowPath,
                                    lastSegment.getDestPort(), encapsulationResources, destWithMultiTable));
                        }

                    }
                });

        return commands;
    }

    private Flow getFlow(FlowPath flowPath) {
        return flowRepository.findById(flowPath.getFlow().getFlowId())
                                    .orElseThrow(() ->
                                            new IllegalStateException(format("Abandon FlowPath found: %s", flowPath)));
    }

    private EncapsulationResources getEncapsulationResources(FlowPath flowPath, Flow flow) {
        return flowResourcesManager.getEncapsulationResources(flowPath.getPathId(),
                flow.getOppositePathId(flowPath.getPathId())
                        .orElseThrow(() -> new IllegalStateException(
                                format("Flow %s does not have reverse path for %s",
                                        flow.getFlowId(), flowPath.getPathId()))),
                flow.getEncapsulationType())
                .orElseThrow(() -> new IllegalStateException(
                        format("Encapsulation resources are not found for path %s", flowPath)));
    }

    /**
     * Some default rules require additional properties to be installed. This method filters such rules.
     */
    private static boolean isDefaultRuleWithSpecialRequirements(long cookie) {
        return cookie == SERVER_42_OUTPUT_VLAN_COOKIE
                || cookie == SERVER_42_OUTPUT_VXLAN_COOKIE
                || new PortColourCookie(cookie).getType() == CookieType.SERVER_42_INPUT;
    }

    /**
     * Some default rules require additional properties to be installed. This method creates commands for such rules.
     */
    private List<BaseInstallFlow> buildInstallSpecialDefaultRuleCommands(SwitchId switchId, List<Long> switchRules) {
        SwitchProperties properties = getSwitchProperties(switchId);

        List<BaseInstallFlow> commands = new ArrayList<>();
        for (Long cookie : switchRules) {
            InstallServer42FlowBuilder command = InstallServer42Flow.builder()
                    .transactionId(transactionIdGenerator.generate())
                    .cookie(cookie)
                    .switchId(switchId)
                    .multiTable(properties.isMultiTable())
                    .inputPort(0)
                    .outputPort(0)
                    .server42Vlan(properties.getServer42Vlan())
                    .server42MacAddress(properties.getServer42MacAddress());

            if (cookie == SERVER_42_OUTPUT_VLAN_COOKIE) {
                commands.add(command
                        .id("SWMANAGER_SERVER_42_OUTPUT_VLAN_RULE_INSTALL")
                        .outputPort(properties.getServer42Port())
                        .build());
            } else if (cookie == SERVER_42_OUTPUT_VXLAN_COOKIE) {
                commands.add(command
                        .id("SWMANAGER_SERVER_42_OUTPUT_VXLAN_RULE_INSTALL")
                        .outputPort(properties.getServer42Port())
                        .build());
            } else if (new PortColourCookie(cookie).getType() == CookieType.SERVER_42_INPUT) {
                commands.add(command
                        .id("SWMANAGER_SERVER_42_INPUT_RULE_INSTALL")
                        .inputPort(properties.getServer42Port())
                        .build());
            } else {
                log.warn("Got request for installation of unknown rule {} on switch {}", cookie, switchId);
            }
        }
        return commands;
    }

    private SwitchProperties getSwitchProperties(SwitchId switchId) {
        return switchPropertiesRepository.findBySwitchId(switchId).orElseThrow(
                () -> new IllegalStateException(format("Switch properties not found for switch %s", switchId)));
    }

    private List<BaseInstallFlow> buildInstallDefaultRuleCommands(SwitchId switchId, List<Long> switchRules) {

        List<BaseInstallFlow> commands = new ArrayList<>(
                buildInstallSpecialDefaultRuleCommands(
                        switchId, switchRules.stream()
                        .filter(CommandBuilderImpl::isDefaultRuleWithSpecialRequirements)
                        .collect(Collectors.toList())));

        switchRules.stream()
                .filter(Cookie::isDefaultRule)
                .filter(cookie -> !isDefaultRuleWithSpecialRequirements(cookie))
                .map(cookie -> new BaseInstallFlow(transactionIdGenerator.generate(), "SWMANAGER_DEFAULT_RULE_INSTALL",
                        cookie, switchId, 0, 0, false))
                .forEach(commands::add);

        return commands;
    }

    private List<BaseFlow> buildInstallFlowSharedRuleCommands(SwitchId switchId, List<Long> switchRules) {
        List<BaseFlow> results = new ArrayList<>();
        for (long rawCookie : switchRules) {
            FlowSharedSegmentCookie cookie = new FlowSharedSegmentCookie(rawCookie);
            if (cookie.getType() != CookieType.SHARED_OF_FLOW) {
                continue;
            }

            if (cookie.getSegmentType() == SharedSegmentType.QINQ_OUTER_VLAN) {
                results.add(new InstallSharedFlow(
                        transactionIdGenerator.generate(), "SWMANAGER_SHARED_FLOW_INSTALL", rawCookie, switchId));
            } else if (cookie.getSegmentType() == SharedSegmentType.SERVER42_QINQ_OUTER_VLAN) {
                results.add(InstallServer42Flow.builder()
                        .id("SWMANAGER_SERVER42_SHARED_FLOW_INSTALL")
                        .transactionId(transactionIdGenerator.generate())
                        .cookie(cookie.getValue())
                        .switchId(switchId)
                        .multiTable(true)
                        .inputPort(cookie.getPortNumber())
                        .outputPort(0)
                        .build());
            }
        }

        return results;
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
        if (segment.getSrcSwitchId().equals(segment.getDestSwitchId())) {
            log.warn("One-switch flow segment {} is provided", flowPath.getCookie());
            return new ArrayList<>();
        }

        Optional<Flow> foundFlow = flowRepository.findById(flowPath.getFlow().getFlowId());
        if (!foundFlow.isPresent()) {
            log.warn("Flow with id {} was not found", flowPath.getFlow().getFlowId());
            return new ArrayList<>();
        }
        Flow flow = foundFlow.get();

        EncapsulationResources encapsulationResources = getEncapsulationResources(flowPath, flow);

        if (segment.getDestSwitchId().equals(flowPath.getDestSwitchId())) {
            return Collections.singletonList(
                    flowCommandFactory.buildInstallEgressFlow(flowPath, segment.getDestPort(), encapsulationResources,
                            segment.isDestWithMultiTable()));
        } else {
            int segmentIdx = flowPath.getSegments().indexOf(segment);
            if (segmentIdx < 0 || segmentIdx + 1 == flowPath.getSegments().size()) {
                log.warn("Paired segment for switch {} and cookie {} has not been found",
                        segment.getDestSwitchId(), flowPath.getCookie());
                return new ArrayList<>();
            }

            PathSegment foundPairedFlowSegment = flowPath.getSegments().get(segmentIdx + 1);

            return Collections.singletonList(flowCommandFactory.buildInstallTransitFlow(
                    flowPath, segment.getDestSwitchId(), segment.getDestPort(),
                    foundPairedFlowSegment.getSrcPort(), encapsulationResources,
                    segment.isDestWithMultiTable()));
        }
    }
}
