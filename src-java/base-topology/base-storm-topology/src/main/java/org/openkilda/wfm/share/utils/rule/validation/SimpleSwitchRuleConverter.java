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

package org.openkilda.wfm.share.utils.rule.validation;

import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.model.EncapsulationId;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Meter;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.utils.rule.validation.SimpleSwitchRule.SimpleSwitchRuleBuilder;

import org.apache.commons.lang3.math.NumberUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class SimpleSwitchRuleConverter {

    private static final String VLAN_VID = "vlan_vid";
    private static final String IN_PORT = "in_port";

    /**
     * Convert {@link FlowPath} to list of {@link SimpleSwitchRule}.
     */
    public List<SimpleSwitchRule> convertFlowPathToSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                     EncapsulationId encapsulationId,
                                                                     long flowMeterMinBurstSizeInKbits,
                                                                     double flowMeterBurstCoefficient) {
        List<SimpleSwitchRule> rules = new ArrayList<>();
        if (!flowPath.isProtected()) {
            rules.addAll(buildIngressSimpleSwitchRules(flow, flowPath, encapsulationId, flowMeterMinBurstSizeInKbits,
                    flowMeterBurstCoefficient));
        }
        if (! flow.isOneSwitchFlow()) {
            rules.addAll(buildTransitAndEgressSimpleSwitchRules(flow, flowPath, encapsulationId));
        }
        return rules;
    }

    private List<SimpleSwitchRule> buildIngressSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                 EncapsulationId encapsulationId,
                                                                 long flowMeterMinBurstSizeInKbits,
                                                                 double flowMeterBurstCoefficient) {
        List<SimpleSwitchRule> rules = new ArrayList<>();
        boolean forward = flow.isForward(flowPath);
        int inPort = forward ? flow.getSrcPort() : flow.getDestPort();
        int outPort = forward ? flow.getDestPort() : flow.getSrcPort();

        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(flowPath.getSrcSwitchId())
                .cookie(flowPath.getCookie().getValue())
                .inPort(inPort)
                .meterId(flowPath.getMeterId() != null ? flowPath.getMeterId().getValue() : null)
                .meterRate(flow.getBandwidth())
                .meterBurstSize(Meter.calculateBurstSize(flow.getBandwidth(), flowMeterMinBurstSizeInKbits,
                        flowMeterBurstCoefficient, flowPath.getSrcSwitch().getDescription()))
                .meterFlags(Meter.getMeterKbpsFlags())
                .build();

        FlowSideAdapter ingress = FlowSideAdapter.makeIngressAdapter(flow, flowPath);
        FlowEndpoint endpoint = ingress.getEndpoint();
        if (flowPath.isSrcWithMultiTable()) {
            // in multi-table mode actual ingress rule will match port+inner_vlan+metadata(outer_vlan)
            if (FlowEndpoint.isVlanIdSet(endpoint.getInnerVlanId())) {
                rule.setInVlan(endpoint.getInnerVlanId());
            }
        } else {
            rule.setInVlan(endpoint.getOuterVlanId());
        }

        if (flow.isOneSwitchFlow()) {
            FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();
            rule.setOutPort(outPort);
            rule.setOutVlan(calcVlanSetSequence(ingress, flowPath, egressEndpoint.getVlanStack()));
        } else {
            PathSegment ingressSegment = flowPath.getSegments().stream()
                    .filter(segment -> segment.getSrcSwitchId()
                            .equals(flowPath.getSrcSwitchId()))
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("PathSegment was not found for ingress flow rule, flowId: %s",
                                    flow.getFlowId())));

            rule.setOutPort(ingressSegment.getSrcPort());
            if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
                rule.setOutVlan(calcVlanSetSequence(
                        ingress, flowPath, Collections.singletonList(encapsulationId.getEncapsulationId())));
            } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
                rule.setTunnelId(encapsulationId.getEncapsulationId());
            }
        }
        rules.add(rule);
        if (ingress.isLooped() && !flowPath.isProtected()) {
            rules.add(buildIngressLoopSimpleSwitchRule(rule, flowPath, ingress));
        }

        return rules;
    }

    private SimpleSwitchRule buildIngressLoopSimpleSwitchRule(SimpleSwitchRule rule, FlowPath flowPath,
                                                              FlowSideAdapter ingress) {
        SimpleSwitchRuleBuilder builder = SimpleSwitchRule.builder()
                .switchId(rule.getSwitchId())
                .cookie(flowPath.getCookie().toBuilder().looped(true).build().getValue())
                .inPort(rule.getInPort())
                .inVlan(rule.getInVlan())
                .outPort(rule.getInPort());
        if (ingress.getEndpoint().getInnerVlanId() != 0) {
            builder.outVlan(Collections.singletonList(ingress.getEndpoint().getOuterVlanId()));
        }
        return builder.build();
    }

    private List<SimpleSwitchRule> buildTransitAndEgressSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                          EncapsulationId encapsulationId) {
        List<PathSegment> orderedSegments = flowPath.getSegments().stream()
                .sorted(Comparator.comparingInt(PathSegment::getSeqId))
                .collect(Collectors.toList());

        List<SimpleSwitchRule> rules = new ArrayList<>();

        for (int i = 1; i < orderedSegments.size(); i++) {
            PathSegment srcPathSegment = orderedSegments.get(i - 1);
            PathSegment dstPathSegment = orderedSegments.get(i);
            rules.add(buildTransitSimpleSwitchRule(flow, flowPath, srcPathSegment, dstPathSegment, encapsulationId));
        }

        PathSegment egressSegment = orderedSegments.get(orderedSegments.size() - 1);
        if (!egressSegment.getDestSwitchId().equals(flowPath.getDestSwitchId())) {
            throw new IllegalStateException(
                    String.format("PathSegment was not found for egress flow rule, flowId: %s", flow.getFlowId()));
        }
        rules.addAll(buildEgressSimpleSwitchRules(flow, flowPath, egressSegment, encapsulationId));

        return rules;
    }

    private SimpleSwitchRule buildTransitSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                          PathSegment srcPathSegment, PathSegment dstPathSegment,
                                                          EncapsulationId encapsulationId) {

        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(srcPathSegment.getDestSwitchId())
                .inPort(srcPathSegment.getDestPort())
                .outPort(dstPathSegment.getSrcPort())
                .cookie(flowPath.getCookie().getValue())
                .build();
        if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
            rule.setInVlan(encapsulationId.getEncapsulationId());
        } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
            rule.setTunnelId(encapsulationId.getEncapsulationId());
        }

        return rule;
    }

    private List<SimpleSwitchRule> buildEgressSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                PathSegment egressSegment,
                                                                EncapsulationId encapsulationId) {
        List<SimpleSwitchRule> rules = new ArrayList<>();

        FlowSideAdapter egressAdapter = FlowSideAdapter.makeEgressAdapter(flow, flowPath);
        FlowEndpoint endpoint = egressAdapter.getEndpoint();
        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(flowPath.getDestSwitchId())
                .outPort(endpoint.getPortNumber())
                .inPort(egressSegment.getDestPort())
                .cookie(flowPath.getCookie().getValue())
                .build();

        if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
            rule.setInVlan(encapsulationId.getEncapsulationId());
            rule.setOutVlan(calcVlanSetSequence(
                    Collections.singletonList(encapsulationId.getEncapsulationId()),
                    endpoint.getVlanStack()));
        } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
            rule.setTunnelId(encapsulationId.getEncapsulationId());
            rule.setOutVlan(calcVlanSetSequence(Collections.emptyList(), endpoint.getVlanStack()));
        }
        if (egressAdapter.isLooped() && !flowPath.isProtected()) {
            rules.add(buildTransitLoopRuleForEgressSwitch(rule, flowPath));
        }

        rules.add(rule);
        return rules;
    }

    private SimpleSwitchRule buildTransitLoopRuleForEgressSwitch(SimpleSwitchRule rule, FlowPath flowPath) {
        SimpleSwitchRuleBuilder builder = SimpleSwitchRule.builder()
                .switchId(rule.getSwitchId())
                .cookie(flowPath.getCookie().toBuilder().looped(true).build().getValue())
                .inPort(rule.getInPort())
                .inVlan(rule.getInVlan())
                .tunnelId(rule.getTunnelId())
                .outPort(rule.getInPort());
        return builder.build();
    }

    /**
     * Convert {@link SwitchFlowEntries} to list of {@link SimpleSwitchRule}.
     */
    public List<SimpleSwitchRule> convertSwitchFlowEntriesToSimpleSwitchRules(SwitchFlowEntries rules,
                                                                              SwitchMeterEntries meters) {
        if (rules == null || rules.getFlowEntries() == null) {
            return Collections.emptyList();
        }

        List<SimpleSwitchRule> simpleRules = new ArrayList<>();
        for (FlowEntry flowEntry : rules.getFlowEntries()) {
            simpleRules.add(buildSimpleSwitchRule(rules.getSwitchId(), flowEntry, meters));
        }
        return simpleRules;
    }

    private SimpleSwitchRule buildSimpleSwitchRule(SwitchId switchId, FlowEntry flowEntry, SwitchMeterEntries meters) {
        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(switchId)
                .cookie(flowEntry.getCookie())
                .pktCount(flowEntry.getPacketCount())
                .byteCount(flowEntry.getByteCount())
                .version(flowEntry.getVersion())
                .build();

        if (flowEntry.getMatch() != null) {
            rule.setInPort(NumberUtils.toInt(flowEntry.getMatch().getInPort()));
            rule.setInVlan(NumberUtils.toInt(flowEntry.getMatch().getVlanVid()));
            rule.setTunnelId(Optional.ofNullable(flowEntry.getMatch().getTunnelId())
                    .map(Integer::decode)
                    .orElse(NumberUtils.INTEGER_ZERO));
        }

        if (flowEntry.getInstructions() != null) {
            if (flowEntry.getInstructions().getApplyActions() != null) {
                FlowApplyActions applyActions = flowEntry.getInstructions().getApplyActions();
                List<FlowSetFieldAction> setFields = Optional.ofNullable(applyActions.getSetFieldActions())
                        .orElse(new ArrayList<>());
                rule.setOutVlan(setFields.stream()
                        .filter(Objects::nonNull)
                        .filter(action -> VLAN_VID.equals(action.getFieldName()))
                        .map(FlowSetFieldAction::getFieldValue)
                        .map(NumberUtils::toInt)
                        .collect(Collectors.toList()));
                String outPort = applyActions.getFlowOutput();
                if (IN_PORT.equals(outPort) && flowEntry.getMatch() != null) {
                    outPort = flowEntry.getMatch().getInPort();
                }
                rule.setOutPort(NumberUtils.toInt(outPort));

                if (rule.getTunnelId() == NumberUtils.INTEGER_ZERO) {
                    rule.setTunnelId(Optional.ofNullable(applyActions.getPushVxlan())
                            .map(Integer::parseInt)
                            .orElse(NumberUtils.INTEGER_ZERO));
                }
            }

            Optional.ofNullable(flowEntry.getInstructions().getGoToMeter())
                    .ifPresent(meterId -> {
                        rule.setMeterId(meterId);
                        if (meters != null && meters.getMeterEntries() != null) {
                            meters.getMeterEntries().stream()
                                    .filter(entry -> meterId.equals(entry.getMeterId()))
                                    .findFirst()
                                    .ifPresent(entry -> {
                                        rule.setMeterRate(entry.getRate());
                                        rule.setMeterBurstSize(entry.getBurstSize());
                                        rule.setMeterFlags(entry.getFlags());
                                    });
                        }
                    });
        }

        return rule;
    }

    private static List<Integer> calcVlanSetSequence(FlowSideAdapter ingress, FlowPath flowPath,
                                                     List<Integer> desiredVlanStack) {
        List<Integer> current;
        if (flowPath.isSrcWithMultiTable()) {
            // outer vlan is removed by first shared rule
            current = FlowEndpoint.makeVlanStack(ingress.getEndpoint().getInnerVlanId());
        } else {
            current = ingress.getEndpoint().getVlanStack();
        }
        return calcVlanSetSequence(current, desiredVlanStack);
    }

    /**
     * Steal logic from {@link org.openkilda.floodlight.utils.OfAdapter#makeVlanReplaceActions}.
     */
    private static List<Integer> calcVlanSetSequence(
            List<Integer> currentVlanStack, List<Integer> desiredVlanStack) {
        Iterator<Integer> currentIter = currentVlanStack.iterator();
        Iterator<Integer> desiredIter = desiredVlanStack.iterator();

        final List<Integer> actions = new ArrayList<>();
        while (currentIter.hasNext() && desiredIter.hasNext()) {
            Integer current = currentIter.next();
            Integer desired = desiredIter.next();
            if (current == null || desired == null) {
                throw new IllegalArgumentException(
                        "Null elements are not allowed inside currentVlanStack and desiredVlanStack arguments");
            }

            if (!current.equals(desired)) {
                // rewrite existing VLAN stack "head"
                actions.add(desired);
                break;
            }
        }

        while (desiredIter.hasNext()) {
            actions.add(desiredIter.next());
        }
        return actions;
    }
}
