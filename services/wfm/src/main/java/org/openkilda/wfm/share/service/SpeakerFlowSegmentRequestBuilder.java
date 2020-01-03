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

package org.openkilda.wfm.share.service;

import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.floodlight.api.request.factory.EgressFlowSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.IngressFlowSegmentRequestFactory;
import org.openkilda.floodlight.api.request.factory.OneSwitchFlowRequestFactory;
import org.openkilda.floodlight.api.request.factory.SharedIngressFlowSegmentOuterVlanMatchRequestFactory;
import org.openkilda.floodlight.api.request.factory.TransitFlowSegmentRequestFactory;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SharedOfFlow;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.model.FlowPathSnapshot;
import org.openkilda.wfm.share.model.FlowSegmentRequestFactoryFilter;
import org.openkilda.wfm.share.model.SharedOfFlowStatus;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import lombok.NonNull;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpeakerFlowSegmentRequestBuilder implements FlowCommandBuilder {
    private final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    @Override
    public List<FlowSegmentRequestFactory> buildAll(
            CommandContext context, Flow flow, FlowPathSnapshot path, FlowPathSnapshot oppositePath) {
        return makeRequests(context, flow, path, oppositePath, true, true, true);
    }

    @Override
    public List<FlowSegmentRequestFactory> buildAllExceptIngress(
            CommandContext context, Flow flow, FlowPathSnapshot path, FlowPathSnapshot oppositePath) {
        return makeRequests(context, flow, path, oppositePath, false, true, true);
    }

    @Override
    public List<FlowSegmentRequestFactory> buildIngressOnly(
            CommandContext context, Flow flow, FlowPathSnapshot path, FlowPathSnapshot oppositePath) {
        return makeRequests(context, flow, path, oppositePath, true, false, false);
    }

    private List<FlowSegmentRequestFactory> makeRequests(
            CommandContext context, Flow flow, FlowPathSnapshot pathSnapshot,
            FlowPathSnapshot oppositePathSnapshot,
            boolean doIngress, boolean doTransit, boolean doEgress) {
        if (pathSnapshot == null) {
            pathSnapshot = oppositePathSnapshot;
            oppositePathSnapshot = null;
        }
        if (pathSnapshot == null) {
            throw new IllegalArgumentException("At least one flow path must be not null");
        }

        List<FlowSegmentRequestFactory> requests = new ArrayList<>(
                makePathRequests(flow, pathSnapshot, context, doIngress, doTransit, doEgress));
        if (oppositePathSnapshot != null) {
            requests.addAll(makePathRequests(flow, oppositePathSnapshot, context, doIngress, doTransit, doEgress));
        }

        return requests;
    }

    private List<FlowSegmentRequestFactory> makePathRequests(
            @NonNull Flow flow, @NonNull FlowPathSnapshot pathSnapshot, CommandContext context,
            boolean doIngress, boolean doTransit, boolean doEgress) {
        final FlowPath path = pathSnapshot.getPath();
        final FlowSideAdapter ingressSide = FlowSideAdapter.makeIngressAdapter(flow, path);
        final FlowSideAdapter egressSide = FlowSideAdapter.makeEgressAdapter(flow, path);

        final List<FlowSegmentRequestFactory> requests = new ArrayList<>();

        PathSegment lastSegment = null;
        for (PathSegment segment : path.getSegments()) {
            if (lastSegment == null) {
                if (doIngress) {
                    requests.addAll(makeSharedIngressRequests(context, flow, pathSnapshot));
                    requests.add(makeIngressRequest(context, pathSnapshot, ingressSide, segment, egressSide));
                }
            } else {
                if (doTransit) {
                    requests.add(makeTransitRequest(context, flow, pathSnapshot, lastSegment, segment));
                }
            }
            lastSegment = segment;
        }

        if (lastSegment != null) {
            if (doEgress) {
                requests.add(makeEgressRequest(context, pathSnapshot, lastSegment, egressSide, ingressSide));
            }
        } else if (doIngress) {
            // one switch flow (path without path segments)
            requests.addAll(makeSharedIngressRequests(context, flow, pathSnapshot));
            requests.add(makeOneSwitchRequest(context, pathSnapshot, ingressSide, egressSide));
        }

        return requests;
    }

    private FlowSegmentRequestFactory makeIngressRequest(
            CommandContext context, FlowPathSnapshot pathSnapshot,
            FlowSideAdapter flowSide, PathSegment segment, FlowSideAdapter egressFlowSide) {
        PathSegmentSide segmentSide = makePathSegmentSourceSide(segment);
        FlowPath path = pathSnapshot.getPath();

        UUID commandId = commandIdGenerator.generate();
        MessageContext messageContext = new MessageContext(commandId.toString(), context.getCorrelationId());
        String flowId = flowSide.getFlow().getFlowId();
        return IngressFlowSegmentRequestFactory.builder()
                .messageContext(messageContext)
                .metadata(new FlowSegmentMetadata(flowId, path.getCookie(), ensureEqualMultiTableFlag(
                        flowSide.isMultiTableSegment(), segmentSide.isMultiTable(),
                        String.format("First flow(id:%s, path:%s) segment and flow level multi-table flag values are "
                                        + "incompatible to each other - flow(%s) != segment(%s)",
                                flowId, path.getPathId(), flowSide.isMultiTableSegment(), segmentSide.isMultiTable()))))
                .endpoint(flowSide.getEndpoint())
                .meterConfig(getMeterConfig(path))
                .egressSwitchId(egressFlowSide.getEndpoint().getSwitchId())
                .islPort(segmentSide.getEndpoint().getPortNumber())
                .encapsulation(makeEncapsulation(pathSnapshot))
                .removeCustomerPortSharedCatchRule(pathSnapshot.isRemoveCustomerPortSharedCatchRule())
                .build();
    }

    private FlowSegmentRequestFactory makeTransitRequest(
            CommandContext context, Flow flow, FlowPathSnapshot pathSnapshot, PathSegment ingress, PathSegment egress) {
        final PathSegmentSide inboundSide = makePathSegmentDestSide(ingress);
        final PathSegmentSide outboundSide = makePathSegmentSourceSide(egress);

        final IslEndpoint ingressEndpoint = inboundSide.getEndpoint();
        final IslEndpoint egressEndpoint = outboundSide.getEndpoint();

        assert ingressEndpoint.getSwitchId().equals(egressEndpoint.getSwitchId())
                : "Only neighbor segments can be used for for transit segment request creation";

        String flowId = flow.getFlowId();
        FlowPath path = pathSnapshot.getPath();
        UUID commandId = commandIdGenerator.generate();
        MessageContext messageContext = new MessageContext(commandId.toString(), context.getCorrelationId());
        return TransitFlowSegmentRequestFactory.builder()
                .messageContext(messageContext)
                .switchId(ingressEndpoint.getSwitchId())
                .metadata(new FlowSegmentMetadata(flowId, path.getCookie(), ensureEqualMultiTableFlag(
                        inboundSide.isMultiTable(), outboundSide.isMultiTable(),
                        String.format(
                                "Flow(id:%s, path:%s) have incompatible multi-table flags between segments %s and %s",
                                flowId, path.getPathId(), ingress, egress))))
                .ingressIslPort(ingressEndpoint.getPortNumber())
                .egressIslPort(egressEndpoint.getPortNumber())
                .encapsulation(makeEncapsulation(pathSnapshot))
                .build();
    }

    private FlowSegmentRequestFactory makeEgressRequest(
            CommandContext context, FlowPathSnapshot pathSnapshot,
            PathSegment segment, FlowSideAdapter flowSide, FlowSideAdapter ingressFlowSide) {
        PathSegmentSide segmentSide = makePathSegmentDestSide(segment);

        FlowPath path = pathSnapshot.getPath();
        UUID commandId = commandIdGenerator.generate();
        MessageContext messageContext = new MessageContext(commandId.toString(), context.getCorrelationId());
        String flowId = flowSide.getFlow().getFlowId();
        return EgressFlowSegmentRequestFactory.builder()
                .messageContext(messageContext)
                .metadata(new FlowSegmentMetadata(flowId, path.getCookie(), ensureEqualMultiTableFlag(
                        segmentSide.isMultiTable(), flowSide.isMultiTableSegment(),
                        String.format("Last flow(id:%s, path:%s) segment and flow level multi-table flags value are "
                                              + "incompatible to each other - segment(%s) != flow(%s)",
                                      flowId, path.getPathId(), segmentSide.isMultiTable(),
                                      flowSide.isMultiTableSegment()))))
                .endpoint(flowSide.getEndpoint())
                .ingressEndpoint(ingressFlowSide.getEndpoint())
                .islPort(segmentSide.getEndpoint().getPortNumber())
                .encapsulation(makeEncapsulation(pathSnapshot))
                .build();
    }

    private FlowSegmentRequestFactory makeOneSwitchRequest(
            CommandContext context, FlowPathSnapshot pathSnapshot,
            FlowSideAdapter ingressSide, FlowSideAdapter egressSide) {
        Flow flow = ingressSide.getFlow();
        FlowPath path = pathSnapshot.getPath();

        UUID commandId = commandIdGenerator.generate();
        MessageContext messageContext = new MessageContext(commandId.toString(), context.getCorrelationId());
        return OneSwitchFlowRequestFactory.builder()
                .messageContext(messageContext)
                .metadata(new FlowSegmentMetadata(flow.getFlowId(), path.getCookie(), ensureEqualMultiTableFlag(
                        ingressSide.isMultiTableSegment(), egressSide.isMultiTableSegment(),
                        String.format("Flow(id:%s) have incompatible for one-switch flow per-side multi-table flags - "
                                              + "src(%s) != dst(%s)",
                                      flow.getFlowId(), flow.isSrcWithMultiTable(), flow.isDestWithMultiTable()))))
                .endpoint(ingressSide.getEndpoint())
                .meterConfig(getMeterConfig(path))
                .egressEndpoint(egressSide.getEndpoint())
                .removeCustomerPortSharedCatchRule(pathSnapshot.isRemoveCustomerPortSharedCatchRule())
                .build();
    }

    private List<FlowSegmentRequestFactory> makeSharedIngressRequests(
            CommandContext commandContext, Flow flow, FlowPathSnapshot pathSnapshot) {
        List<FlowSegmentRequestFactory> requestFactories = new ArrayList<>();

        FlowSideAdapter ingressFlowSide = FlowSideAdapter.makeIngressAdapter(flow, pathSnapshot.getPath());
        if (pathSnapshot.getSharedIngressSegmentOuterVlanMatchStatus() != null) {
            requestFactories.add(makeSharedIngressSegmentOuterVlanMatchRequest(
                    commandContext, ingressFlowSide, pathSnapshot.getSharedIngressSegmentOuterVlanMatchStatus()));
        }

        return requestFactories;
    }

    private FlowSegmentRequestFactory makeSharedIngressSegmentOuterVlanMatchRequest(
            CommandContext context, FlowSideAdapter ingressFlowSide, SharedOfFlowStatus sharedOfFlowStatus) {
        UUID commandId = commandIdGenerator.generate();
        SharedOfFlow sharedOfFlow = sharedOfFlowStatus.getSharedFlow();
        MessageContext messageContext = new MessageContext(commandId.toString(), context.getCorrelationId());
        FlowSegmentMetadata metadata = new FlowSegmentMetadata(
                ingressFlowSide.getFlow().getFlowId(), sharedOfFlow.getCookie(), ingressFlowSide.isMultiTableSegment());
        return addSharedRequestFactoryFilter(
                SharedIngressFlowSegmentOuterVlanMatchRequestFactory.builder()
                        .messageContext(messageContext)
                        .metadata(metadata)
                        .endpoint(ingressFlowSide.getEndpoint())
                        .build(),
                sharedOfFlowStatus);
    }

    private FlowSegmentRequestFactory addSharedRequestFactoryFilter(
            FlowSegmentRequestFactory target, SharedOfFlowStatus sharedOfFlowStatus) {
        FlowSegmentRequestFactoryFilter.FlowSegmentRequestFactoryFilterBuilder filter = FlowSegmentRequestFactoryFilter
                .builder(target);
        filter.allowVerify(true);

        if (sharedOfFlowStatus.getForeignReferencesCount() < 1) {
            filter.allowCreate(true);
            filter.allowRemove(true);
        }

        return filter.build();
    }

    private boolean ensureEqualMultiTableFlag(boolean ingress, boolean egress, String errorMessage) {
        if (ingress != egress) {
            throw new IllegalArgumentException(errorMessage);
        }
        return ingress;
    }

    private PathSegmentSide makePathSegmentSourceSide(PathSegment segment) {
        return new PathSegmentSide(
                new IslEndpoint(segment.getSrcSwitch().getSwitchId(), segment.getSrcPort()),
                segment.isSrcWithMultiTable());
    }

    private PathSegmentSide makePathSegmentDestSide(PathSegment segment) {
        return new PathSegmentSide(
                new IslEndpoint(segment.getDestSwitch().getSwitchId(), segment.getDestPort()),
                segment.isDestWithMultiTable());
    }

    @Value
    private static class PathSegmentSide {
        private final IslEndpoint endpoint;

        private boolean multiTable;
    }

    private MeterConfig getMeterConfig(FlowPath path) {
        if (path.getMeterId() == null) {
            return null;
        }
        return new MeterConfig(path.getMeterId(), path.getBandwidth());
    }

    private FlowTransitEncapsulation makeEncapsulation(FlowPathSnapshot pathSnapshot) {
        EncapsulationResources resources = pathSnapshot.getResources()
                .getEncapsulationResources();
        return new FlowTransitEncapsulation(resources.getTransitEncapsulationId(), resources.getEncapsulationType());
    }
}
