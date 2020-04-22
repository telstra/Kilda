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

import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.model.EncapsulationId;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Meter;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;

import org.apache.commons.lang3.math.NumberUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
            rules.add(buildIngressSimpleSwitchRule(flow, flowPath, encapsulationId, flowMeterMinBurstSizeInKbits,
                    flowMeterBurstCoefficient));
        }
        rules.addAll(buildTransitAndEgressSimpleSwitchRules(flow, flowPath, encapsulationId));
        return rules;
    }

    private SimpleSwitchRule buildIngressSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                          EncapsulationId encapsulationId,
                                                          long flowMeterMinBurstSizeInKbits,
                                                          double flowMeterBurstCoefficient) {
        boolean forward = flow.isForward(flowPath);
        int inPort = forward ? flow.getSrcPort() : flow.getDestPort();
        int outPort = forward ? flow.getDestPort() : flow.getSrcPort();
        int inVlan = forward ? flow.getSrcVlan() : flow.getDestVlan();
        int outVlan = forward ? flow.getDestVlan() : flow.getSrcVlan();

        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(flowPath.getSrcSwitch().getSwitchId())
                .cookie(flowPath.getCookie().getValue())
                .inPort(inPort)
                .inVlan(inVlan)
                .meterId(flowPath.getMeterId() != null ? flowPath.getMeterId().getValue() : null)
                .meterRate(flow.getBandwidth())
                .meterBurstSize(Meter.calculateBurstSize(flow.getBandwidth(), flowMeterMinBurstSizeInKbits,
                        flowMeterBurstCoefficient, flowPath.getSrcSwitch().getDescription()))
                .meterFlags(Meter.getMeterKbpsFlags())
                .build();

        if (flow.isOneSwitchFlow()) {
            rule.setOutPort(outPort);
            rule.setOutVlan(outVlan);

        } else {
            PathSegment ingressSegment = flowPath.getSegments().stream()
                    .filter(segment -> segment.getSrcSwitch().getSwitchId()
                            .equals(flowPath.getSrcSwitch().getSwitchId()))
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("PathSegment was not found for ingress flow rule, flowId: %s",
                                    flow.getFlowId())));

            rule.setOutPort(ingressSegment.getSrcPort());
            if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
                rule.setOutVlan(encapsulationId.getEncapsulationId());
            } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
                rule.setTunnelId(encapsulationId.getEncapsulationId());
            }
        }

        return rule;
    }

    private List<SimpleSwitchRule> buildTransitAndEgressSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                          EncapsulationId encapsulationId) {
        if (flow.isOneSwitchFlow()) {
            return Collections.emptyList();
        }

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
        if (!egressSegment.getDestSwitch().getSwitchId().equals(flowPath.getDestSwitch().getSwitchId())) {
            throw new IllegalStateException(
                    String.format("PathSegment was not found for egress flow rule, flowId: %s", flow.getFlowId()));
        }
        rules.add(buildEgressSimpleSwitchRule(flow, flowPath, egressSegment, encapsulationId));

        return rules;
    }

    private SimpleSwitchRule buildTransitSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                          PathSegment srcPathSegment, PathSegment dstPathSegment,
                                                          EncapsulationId encapsulationId) {

        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(srcPathSegment.getDestSwitch().getSwitchId())
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

    private SimpleSwitchRule buildEgressSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                         PathSegment egressSegment,
                                                         EncapsulationId encapsulationId) {
        boolean forward = flow.isForward(flowPath);
        int outPort = forward ? flow.getDestPort() : flow.getSrcPort();
        int outVlan = forward ? flow.getDestVlan() : flow.getSrcVlan();

        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(flowPath.getDestSwitch().getSwitchId())
                .outPort(outPort)
                .outVlan(outVlan)
                .inPort(egressSegment.getDestPort())
                .cookie(flowPath.getCookie().getValue())
                .build();
        if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
            rule.setInVlan(encapsulationId.getEncapsulationId());
        } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
            rule.setTunnelId(encapsulationId.getEncapsulationId());
        }

        return rule;
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
                rule.setOutVlan(Optional.ofNullable(applyActions.getFieldActions())
                        .orElse(new ArrayList<>())
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(action -> VLAN_VID.equals(action.getFieldName()))
                        .map(FlowSetFieldAction::getFieldValue)
                        .map(NumberUtils::toInt)
                        .findFirst()
                        .orElse(NumberUtils.INTEGER_ZERO));
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
}
