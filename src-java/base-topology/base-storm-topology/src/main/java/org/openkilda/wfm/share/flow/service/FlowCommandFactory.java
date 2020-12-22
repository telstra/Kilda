/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.share.flow.service;

import static java.lang.String.format;

import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.DeleteMeterRequest;
import org.openkilda.messaging.command.flow.InstallEgressFlow;
import org.openkilda.messaging.command.flow.InstallIngressFlow;
import org.openkilda.messaging.command.flow.InstallIngressLoopFlow;
import org.openkilda.messaging.command.flow.InstallOneSwitchFlow;
import org.openkilda.messaging.command.flow.InstallServer42IngressFlow;
import org.openkilda.messaging.command.flow.InstallTransitFlow;
import org.openkilda.messaging.command.flow.InstallTransitLoopFlow;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.flow.RuleType;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.MacAddress;
import org.openkilda.model.MeterId;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.CookieBase.CookieType;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FlowCommandFactory {
    // The default timeBasedGenerator() utilizes SecureRandom for the location part and time+sequence for the time part.
    private final NoArgGenerator transactionIdGenerator = Generators.timeBasedGenerator();

    /**
     * Generates install LLDP, transit and egress rules commands for a flow.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @return list of commands
     */
    public List<BaseInstallFlow> createInstallLldpTransitAndEgressRulesForFlow(
            FlowPath flowPath, EncapsulationResources encapsulationResources) {
        Flow flow = flowPath.getFlow();

        List<BaseInstallFlow> commands = new ArrayList<>();

        if (flow.isOneSwitchFlow()) {
            return commands;
        }
        List<PathSegment> segments = flowPath.getSegments();
        requireSegments(segments);

        for (int i = 1; i < segments.size(); i++) {
            PathSegment src = segments.get(i - 1);
            PathSegment dst = segments.get(i);

            commands.add(buildInstallTransitFlow(flowPath, src.getDestSwitchId(), src.getDestPort(),
                    dst.getSrcPort(), encapsulationResources, src.isDestWithMultiTable()));
        }

        PathSegment egressSegment = segments.get(segments.size() - 1);
        if (!egressSegment.getDestSwitchId().equals(flowPath.getDestSwitchId())) {
            throw new IllegalStateException(
                    format("FlowSegment was not found for egress flow rule, flowId: %s", flow.getFlowId()));
        }
        commands.add(buildInstallEgressFlow(flowPath, egressSegment.getDestPort(), encapsulationResources,
                egressSegment.isDestWithMultiTable()));
        return commands;
    }

    /**
     * Generates install ingress / one switch rules commands for a flow.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @return list of commands
     */
    public BaseInstallFlow createInstallIngressRulesForFlow(FlowPath flowPath,
                                                            EncapsulationResources encapsulationResources) {
        Flow flow = flowPath.getFlow();

        if (flow.isOneSwitchFlow()) {
            return makeOneSwitchRule(flow, flowPath);
        }
        List<PathSegment> segments = flowPath.getSegments();
        requireSegments(segments);

        PathSegment ingressSegment = segments.get(0);
        if (!ingressSegment.getSrcSwitchId().equals(flowPath.getSrcSwitchId())) {
            throw new IllegalStateException(
                    format("FlowSegment was not found for ingress flow rule, flowId: %s", flow.getFlowId()));
        }

        return buildInstallIngressFlow(flow, flowPath, ingressSegment.getSrcPort(), encapsulationResources,
                ingressSegment.isSrcWithMultiTable());
    }

    /**
     * Generates remove LLDP, transit and egress rules commands for a flow.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @return list of commands
     */
    public List<RemoveFlow> createRemoveLldpTransitAndEgressRulesForFlow(
            FlowPath flowPath, EncapsulationResources encapsulationResources) {
        Flow flow = flowPath.getFlow();
        List<RemoveFlow> commands = new ArrayList<>();

        if (flow.isOneSwitchFlow()) {
            // Removing of single switch rules is done with no output port in criteria.
            return commands;
        }
        List<PathSegment> segments = flowPath.getSegments();
        requireSegments(segments);

        for (int i = 1; i < segments.size(); i++) {
            PathSegment src = segments.get(i - 1);
            PathSegment dst = segments.get(i);

            commands.add(buildRemoveTransitFlow(flowPath, src.getDestSwitchId(), src.getDestPort(),
                    dst.getSrcPort(), encapsulationResources, src.isDestWithMultiTable()));
        }

        PathSegment egressSegment = segments.get(segments.size() - 1);
        if (!egressSegment.getDestSwitchId().equals(flowPath.getDestSwitchId())) {
            throw new IllegalStateException(
                    format("FlowSegment was not found for egress flow rule, flowId: %s", flow.getFlowId()));
        }
        commands.add(buildRemoveEgressFlow(flow, flowPath, egressSegment.getDestPort(), encapsulationResources,
                egressSegment.isDestWithMultiTable()));
        return commands;
    }

    /**
     * Generates remove ingress rules commands for a flow.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @return list of commands
     */
    public RemoveFlow createRemoveIngressRulesForFlow(
            FlowPath flowPath, boolean cleanUpIngress, boolean cleanUpIngressLldp, boolean cleanUpIngressArp) {
        Flow flow = flowPath.getFlow();
        if (flow.isOneSwitchFlow()) {
            // Removing of single switch rules is done with no output port in criteria.
            return buildRemoveIngressFlow(flow, flowPath, null,
                    flowPath.isSrcWithMultiTable(), cleanUpIngress, cleanUpIngressLldp, cleanUpIngressArp);
        }
        List<PathSegment> segments = flowPath.getSegments();
        requireSegments(segments);

        PathSegment ingressSegment = segments.get(0);
        if (!ingressSegment.getSrcSwitchId().equals(flowPath.getSrcSwitchId())) {
            throw new IllegalStateException(
                    format("FlowSegment was not found for ingress flow rule, flowId: %s", flow.getFlowId()));
        }

        return buildRemoveIngressFlow(flow, flowPath, ingressSegment.getSrcPort(),
                ingressSegment.isSrcWithMultiTable(), cleanUpIngress, cleanUpIngressLldp, cleanUpIngressArp);
    }

    /**
     * Generates delete meter command.
     *
     * @param flowPath  flow path to delete meter on
     * @return delete meter command
     */
    public DeleteMeterRequest createDeleteMeter(FlowPath flowPath) {
        if (flowPath.getMeterId() == null) {
            throw new IllegalArgumentException("Trying delete null meter");
        }
        return new DeleteMeterRequest(flowPath.getSrcSwitchId(), flowPath.getMeterId().getValue());
    }

    private void requireSegments(List<PathSegment> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Neither one switch flow nor path segments provided");
        }
    }

    /**
     * Generate install egress flow command.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @param inputPortNo the number of input port.
     * @param encapsulationResources the encapsulation resources.
     * @param multiTable use multi table.
     * @return install egress flow command
     */
    public InstallEgressFlow buildInstallEgressFlow(FlowPath flowPath, int inputPortNo,
                                                    EncapsulationResources encapsulationResources,
                                                    boolean multiTable) {
        Flow flow = flowPath.getFlow();
        FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
        FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();

        return new InstallEgressFlow(transactionIdGenerator.generate(), flow.getFlowId(),
                flowPath.getCookie().getValue(), egressEndpoint.getSwitchId(), inputPortNo,
                egressEndpoint.getPortNumber(),
                encapsulationResources.getTransitEncapsulationId(), encapsulationResources.getEncapsulationType(),
                egressEndpoint.getOuterVlanId(), egressEndpoint.getInnerVlanId(), getOutputVlanType(flow, flowPath),
                multiTable, ingressEndpoint);
    }

    /**
     * Generate install transit flow loop command.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @param inputPortNo the number of input port.
     * @param encapsulationResources the encapsulation resources.
     * @param multiTable use multi table.
     * @return install egress flow command
     */
    public InstallEgressFlow buildInstallTransitLoopFlow(Flow flow, FlowPath flowPath, int inputPortNo,
                                                         EncapsulationResources encapsulationResources,
                                                         boolean multiTable) {
        FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
        FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();

        return new InstallTransitLoopFlow(transactionIdGenerator.generate(), flow.getFlowId(),
                flowPath.getCookie().toBuilder().looped(true).build().getValue(),
                egressEndpoint.getSwitchId(), inputPortNo, egressEndpoint.getPortNumber(),
                encapsulationResources.getTransitEncapsulationId(), encapsulationResources.getEncapsulationType(),
                egressEndpoint.getOuterVlanId(), egressEndpoint.getInnerVlanId(), getOutputVlanType(flow, flowPath),
                multiTable, ingressEndpoint);
    }

    private RemoveFlow buildRemoveEgressFlow(Flow flow, FlowPath flowPath, int inputPortNo,
                                             EncapsulationResources encapsulationResources, boolean multiTable) {
        boolean isForward = flow.isForward(flowPath);
        SwitchId switchId = isForward ? flow.getDestSwitchId() : flow.getSrcSwitchId();
        int outPort = isForward ? flow.getDestPort() : flow.getSrcPort();

        long cookie = flowPath.getCookie().getValue();
        DeleteRulesCriteria criteria = new DeleteRulesCriteria(cookie, inputPortNo,
                encapsulationResources.getTransitEncapsulationId(),
                0, outPort, encapsulationResources.getEncapsulationType(),
                flowPath.getDestSwitchId());
        return RemoveFlow.builder()
                .transactionId(transactionIdGenerator.generate())
                .flowId(flow.getFlowId())
                .cookie(cookie)
                .switchId(switchId)
                .criteria(criteria)
                .multiTable(multiTable)
                .ruleType(RuleType.EGRESS)
                .build();
    }

    /**
     * Generate install transit flow command.
     *
     * @param flowPath flow path with segments to be used for building of install rules.
     * @param switchId the switch id.
     * @param inputPortNo the number of input port.
     * @param outputPortNo the number of output port.
     * @param encapsulationResources the encapsulation resources.
     * @param multiTable use multi table.
     * @return install transit flow command
     */
    public InstallTransitFlow buildInstallTransitFlow(FlowPath flowPath, SwitchId switchId,
                                                      int inputPortNo, int outputPortNo,
                                                      EncapsulationResources encapsulationResources,
                                                      boolean multiTable) {
        return new InstallTransitFlow(transactionIdGenerator.generate(), flowPath.getFlow().getFlowId(),
                flowPath.getCookie().getValue(), switchId, inputPortNo, outputPortNo,
                encapsulationResources.getTransitEncapsulationId(), encapsulationResources.getEncapsulationType(),
                multiTable);
    }

    private RemoveFlow buildRemoveTransitFlow(FlowPath flowPath, SwitchId switchId,
                                              int inputPortNo, int outputPortNo,
                                              EncapsulationResources encapsulationResources,
                                              boolean multiTable) {
        long cookie = flowPath.getCookie().getValue();
        DeleteRulesCriteria criteria = new DeleteRulesCriteria(cookie,
                inputPortNo, encapsulationResources.getTransitEncapsulationId(), 0, outputPortNo,
                encapsulationResources.getEncapsulationType(), null);
        return RemoveFlow.builder()
                .transactionId(transactionIdGenerator.generate())
                .flowId(flowPath.getFlow().getFlowId())
                .cookie(cookie)
                .switchId(switchId)
                .criteria(criteria)
                .multiTable(multiTable)
                .ruleType(RuleType.TRANSIT)
                .build();
    }

    /**
     * Generate install ingress flow command.
     *
     * @param flow the flow.
     * @param flowPath flow path with segments to be used for building of install rules.
     * @param outputPortNo the number of output port.
     * @param encapsulationResources the encapsulation resources.
     * @param multiTable  \
     * @return install ingress flow command
     */
    public InstallIngressFlow buildInstallIngressFlow(Flow flow, FlowPath flowPath, int outputPortNo,
                                                      EncapsulationResources encapsulationResources,
                                                      boolean multiTable) {
        boolean enableLldp = needToInstallOrRemoveLldpFlow(flowPath);
        boolean enableArp = needToInstallOrRemoveArpFlow(flowPath);

        Long meterId = Optional.ofNullable(flowPath.getMeterId()).map(MeterId::getValue).orElse(null);

        FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
        FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();

        return new InstallIngressFlow(transactionIdGenerator.generate(), flow.getFlowId(),
                flowPath.getCookie().getValue(),
                ingressEndpoint.getSwitchId(), ingressEndpoint.getPortNumber(),
                outputPortNo, ingressEndpoint.getOuterVlanId(), ingressEndpoint.getInnerVlanId(),
                encapsulationResources.getTransitEncapsulationId(), encapsulationResources.getEncapsulationType(),
                getOutputVlanType(flow, flowPath), flow.getBandwidth(), meterId,
                egressEndpoint.getSwitchId(), multiTable, enableLldp, enableArp);
    }

    /**
     * Generate install ingress flow loop command.
     *
     * @param flow the flow.
     * @param flowPath flow path with segments to be used for building of install rules.
     * @param encapsulationResources the encapsulation resources.
     * @param multiTable  \
     * @return install ingress flow command
     */
    public InstallIngressLoopFlow buildInstallIngressLoopFlow(Flow flow, FlowPath flowPath,
                                                              EncapsulationResources encapsulationResources,
                                                              boolean multiTable) {
        FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
        FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();

        return new InstallIngressLoopFlow(transactionIdGenerator.generate(), flow.getFlowId(),
                flowPath.getCookie().toBuilder().looped(true).build().getValue(),
                ingressEndpoint.getSwitchId(), ingressEndpoint.getPortNumber(), 0,
                encapsulationResources.getTransitEncapsulationId(), encapsulationResources.getEncapsulationType(),
                egressEndpoint.getOuterVlanId(), egressEndpoint.getInnerVlanId(), getOutputVlanType(flow, flowPath),
                multiTable, ingressEndpoint);
    }

    /**
     * Generate install server 42 ingress flow command.
     *
     * @param flow the flow.
     * @param flowPath flow path with segments to be used for building of install rules.
     * @param outputPort the number of output port.
     * @param resources the encapsulation resources.
     * @return install server 42 ingress flow command
     */
    public InstallServer42IngressFlow buildInstallServer42IngressFlow(
            Flow flow, FlowPath flowPath, int outputPort, int server42Port, MacAddress server42MacAddress,
            EncapsulationResources resources, boolean multiTable) {
        boolean isForward = flow.isForward(flowPath);
        SwitchId switchId = isForward ? flow.getSrcSwitchId() : flow.getDestSwitchId();
        SwitchId egressSwitchId = isForward ? flow.getDestSwitchId() : flow.getSrcSwitchId();
        int customerPort = isForward ? flow.getSrcPort() : flow.getDestPort();
        int inVlan = isForward ? flow.getSrcVlan() : flow.getDestVlan();
        long cookie = new FlowSegmentCookie(flowPath.getCookie().getValue()).toBuilder()
                .type(CookieType.SERVER_42_INGRESS)
                .build()
                .getValue();

        return new InstallServer42IngressFlow(transactionIdGenerator.generate(), flow.getFlowId(),
                cookie, switchId, server42Port, outputPort, customerPort, inVlan, resources.getTransitEncapsulationId(),
                resources.getEncapsulationType(), getOutputVlanType(flow, flowPath), egressSwitchId, server42MacAddress,
                multiTable);
    }

    private RemoveFlow buildRemoveIngressFlow(Flow flow, FlowPath flowPath, Integer outputPortNo, boolean multiTable,
                                              boolean cleanUpIngress, boolean cleanUpIngressLldp,
                                              boolean cleanUpIngressArp) {
        boolean isForward = flow.isForward(flowPath);
        SwitchId switchId = isForward ? flow.getSrcSwitchId() : flow.getDestSwitchId();
        int inPort = isForward ? flow.getSrcPort() : flow.getDestPort();
        int inVlan = isForward ? flow.getSrcVlan() : flow.getDestVlan();


        long cookie = flowPath.getCookie().getValue();
        Long meterId = Optional.ofNullable(flowPath.getMeterId()).map(MeterId::getValue).orElse(null);
        DeleteRulesCriteria ingressCriteria = new DeleteRulesCriteria(cookie, inPort,
                inVlan, 0, outputPortNo, FlowEncapsulationType.TRANSIT_VLAN, null);
        return RemoveFlow.builder()
                .transactionId(transactionIdGenerator.generate())
                .flowId(flow.getFlowId())
                .cookie(cookie)
                .switchId(switchId)
                .meterId(meterId)
                .criteria(ingressCriteria)
                .multiTable(multiTable)
                .ruleType(RuleType.INGRESS)
                .cleanUpIngress(cleanUpIngress)
                .cleanUpIngressLldp(cleanUpIngressLldp)
                .cleanUpIngressArp(cleanUpIngressArp)
                .build();
    }

    private boolean needToInstallOrRemoveLldpFlow(FlowPath path) {
        Flow flow = path.getFlow();
        boolean isForward = flow.isForward(path);
        DetectConnectedDevices detect = flow.getDetectConnectedDevices();
        return  (isForward && (detect.isSrcLldp() || detect.isSrcSwitchLldp()))
                || (!isForward && (detect.isDstLldp() || detect.isDstSwitchLldp()));
    }

    private boolean needToInstallOrRemoveArpFlow(FlowPath path) {
        Flow flow = path.getFlow();
        boolean isForward = flow.isForward(path);
        DetectConnectedDevices detect = flow.getDetectConnectedDevices();
        return  (isForward && (detect.isSrcArp() || detect.isSrcSwitchArp()))
                || (!isForward && (detect.isDstArp() || detect.isDstSwitchArp()));
    }

    /**
     * Generate install one swithc flow command.
     *
     * @param flow the flow.
     * @param flowPath flow path with segments to be used for building of install rules.
     * @return install one switch flow command
     */
    public InstallOneSwitchFlow makeOneSwitchRule(Flow flow, FlowPath flowPath) {
        boolean enableLldp = needToInstallOrRemoveLldpFlow(flowPath);
        boolean enableArp = needToInstallOrRemoveArpFlow(flowPath);
        boolean multiTable = flowPath.isSrcWithMultiTable();

        FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
        FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();

        Long meterId = Optional.ofNullable(flowPath.getMeterId()).map(MeterId::getValue).orElse(null);
        return new InstallOneSwitchFlow(transactionIdGenerator.generate(),
                flow.getFlowId(), flowPath.getCookie().getValue(),
                ingressEndpoint.getSwitchId(), ingressEndpoint.getPortNumber(),
                egressEndpoint.getPortNumber(),
                ingressEndpoint.getOuterVlanId(), ingressEndpoint.getInnerVlanId(),
                egressEndpoint.getOuterVlanId(), egressEndpoint.getInnerVlanId(),
                getOutputVlanType(flow, flowPath), flow.getBandwidth(), meterId, multiTable, enableLldp, enableArp);
    }

    private OutputVlanType getOutputVlanType(Flow flow, FlowPath flowPath) {
        int sourceVlan = flow.isForward(flowPath) ? flow.getSrcVlan() : flow.getDestVlan();
        int dstVlan = flow.isForward(flowPath) ? flow.getDestVlan() : flow.getSrcVlan();
        if (sourceVlan == 0) {
            return dstVlan == 0 ? OutputVlanType.NONE : OutputVlanType.PUSH;
        }
        return dstVlan == 0 ? OutputVlanType.POP : OutputVlanType.REPLACE;
    }
}
