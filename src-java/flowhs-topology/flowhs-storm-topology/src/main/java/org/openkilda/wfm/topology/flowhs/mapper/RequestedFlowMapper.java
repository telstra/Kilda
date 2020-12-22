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

package org.openkilda.wfm.topology.flowhs.mapper;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.model.DetectConnectedDevicesDto;
import org.openkilda.messaging.model.SwapFlowDto;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.SwitchId;
import org.openkilda.server42.control.messaging.flowrtt.ActivateFlowMonitoringInfoData;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(imports = {FlowEndpoint.class})
public abstract class RequestedFlowMapper {

    public static final RequestedFlowMapper INSTANCE = Mappers.getMapper(RequestedFlowMapper.class);

    /**
     * Convert {@link Flow} to {@link RequestedFlow}.
     */
    public RequestedFlow toRequestedFlow(FlowRequest request) {
        RequestedFlow flow = generatedMap(request);
        RequestedFlowIncrementalMapper.INSTANCE.mapSource(flow, request.getSource());
        RequestedFlowIncrementalMapper.INSTANCE.mapDestination(flow, request.getDestination());
        return flow;
    }

    /**
     * Convert {@link Flow} to {@link RequestedFlow}.
     */
    @Mapping(source = "flowId", target = "flowId")
    @Mapping(target = "srcSwitch", expression = "java(flow.getSrcSwitchId())")
    @Mapping(source = "srcPort", target = "srcPort")
    @Mapping(source = "srcVlan", target = "srcVlan")
    @Mapping(target = "destSwitch", expression = "java(flow.getDestSwitchId())")
    @Mapping(source = "destPort", target = "destPort")
    @Mapping(source = "destVlan", target = "destVlan")
    @Mapping(source = "encapsulationType", target = "flowEncapsulationType")
    @Mapping(target = "diverseFlowId", ignore = true)
    @Mapping(target = "loopSwitchId", source = "loopSwitchId")
    public abstract RequestedFlow toRequestedFlow(Flow flow);

    /**
     * Convert {@link SwapFlowDto} to {@link RequestedFlow}.
     */
    @Mapping(source = "flowId", target = "flowId")
    @Mapping(source = "sourceSwitch", target = "srcSwitch")
    @Mapping(source = "sourcePort", target = "srcPort")
    @Mapping(source = "sourceVlan", target = "srcVlan")
    @Mapping(source = "destinationSwitch", target = "destSwitch")
    @Mapping(source = "destinationPort", target = "destPort")
    @Mapping(source = "destinationVlan", target = "destVlan")
    @Mapping(target = "priority", ignore = true)
    @Mapping(target = "pinned", ignore = true)
    @Mapping(target = "allocateProtectedPath", ignore = true)
    @Mapping(target = "diverseFlowId", ignore = true)
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "bandwidth", ignore = true)
    @Mapping(target = "ignoreBandwidth", ignore = true)
    @Mapping(target = "periodicPings", ignore = true)
    @Mapping(target = "maxLatency", ignore = true)
    @Mapping(target = "maxLatencyTier2", ignore = true)
    @Mapping(target = "flowEncapsulationType", ignore = true)
    @Mapping(target = "pathComputationStrategy", ignore = true)
    @Mapping(target = "detectConnectedDevices", ignore = true)
    @Mapping(target = "srcInnerVlan", ignore = true)
    @Mapping(target = "destInnerVlan", ignore = true)
    @Mapping(target = "loopSwitchId", ignore = true)
    public abstract RequestedFlow toRequestedFlow(SwapFlowDto flow);

    /**
     * Convert {@link RequestedFlow} to {@link Flow}.
     */
    @Mapping(source = "flowId", target = "flowId")
    @Mapping(target = "srcSwitch",
            expression = "java(org.openkilda.model.Switch.builder().switchId(requestedFlow.getSrcSwitch()).build())")
    @Mapping(source = "srcPort", target = "srcPort")
    @Mapping(source = "srcVlan", target = "srcVlan")
    @Mapping(target = "destSwitch",
            expression = "java(org.openkilda.model.Switch.builder().switchId(requestedFlow.getDestSwitch()).build())")
    @Mapping(source = "destPort", target = "destPort")
    @Mapping(source = "destVlan", target = "destVlan")
    @Mapping(source = "flowEncapsulationType", target = "encapsulationType")
    @Mapping(target = "groupId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "statusInfo", ignore = true)
    @Mapping(target = "targetPathComputationStrategy", ignore = true)
    @Mapping(target = "loopSwitchId", source = "loopSwitchId")
    public abstract Flow toFlow(RequestedFlow requestedFlow);

    @Mapping(source = "encapsulationType", target = "flowEncapsulationType")
    @Mapping(target = "srcSwitch", ignore = true)
    @Mapping(target = "srcPort", ignore = true)
    @Mapping(target = "srcVlan", ignore = true)
    @Mapping(target = "srcInnerVlan", ignore = true)
    @Mapping(target = "destSwitch", ignore = true)
    @Mapping(target = "destPort", ignore = true)
    @Mapping(target = "destVlan", ignore = true)
    @Mapping(target = "destInnerVlan", ignore = true)
    @Mapping(target = "loopSwitchId", source = "loopSwitchId")
    protected abstract RequestedFlow generatedMap(FlowRequest request);

    public abstract FlowEncapsulationType map(org.openkilda.messaging.payload.flow.FlowEncapsulationType source);

    public FlowEndpoint mapSource(RequestedFlow flow) {
        return new FlowEndpoint(flow.getSrcSwitch(), flow.getSrcPort(), flow.getSrcVlan(), flow.getSrcInnerVlan());
    }

    public FlowEndpoint mapDest(RequestedFlow flow) {
        return new FlowEndpoint(flow.getDestSwitch(), flow.getDestPort(), flow.getDestVlan(), flow.getDestInnerVlan());
    }

    /**
     * Decode string representation of {@code PathComputationStrategy}.
     */
    public PathComputationStrategy mapComputationStrategy(String raw) {
        if (raw == null) {
            return null;
        }
        return PathComputationStrategy.valueOf(raw.toUpperCase());
    }

    /**
     * Convert {@link Flow} to {@link FlowRequest}.
     */
    @Mapping(target = "flowId", source = "flowId")
    @Mapping(target = "source", expression = "java(new FlowEndpoint(flow.getSrcSwitchId(), "
            + "flow.getSrcPort(), flow.getSrcVlan(), flow.getSrcInnerVlan()))")
    @Mapping(target = "destination", expression = "java(new FlowEndpoint(flow.getDestSwitchId(), "
            + "flow.getDestPort(), flow.getDestVlan(), flow.getDestInnerVlan()))")
    @Mapping(target = "encapsulationType", source = "encapsulationType")
    @Mapping(target = "pathComputationStrategy",
            expression = "java(java.util.Optional.ofNullable(flow.getPathComputationStrategy())"
                    + ".map(pcs -> pcs.toString().toLowerCase())"
                    + ".orElse(null))")
    @Mapping(target = "bandwidth", source = "bandwidth")
    @Mapping(target = "ignoreBandwidth", source = "ignoreBandwidth")
    @Mapping(target = "periodicPings", source = "periodicPings")
    @Mapping(target = "allocateProtectedPath", source = "allocateProtectedPath")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "maxLatency", source = "maxLatency")
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "pinned", source = "pinned")
    @Mapping(target = "detectConnectedDevices", source = "detectConnectedDevices")
    @Mapping(target = "loopSwitchId", source = "loopSwitchId")
    @Mapping(target = "transitEncapsulationId", ignore = true)
    @Mapping(target = "diverseFlowId", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "bulkUpdateFlowIds", ignore = true)
    @Mapping(target = "doNotRevert", ignore = true)
    public abstract FlowRequest toFlowRequest(Flow flow);

    /**
     * Convert {@link DetectConnectedDevices} to {@link DetectConnectedDevicesDto}.
     */
    public abstract DetectConnectedDevicesDto toDetectConnectedDevices(DetectConnectedDevices detectConnectedDevices);

    /**
     * Convert {@link RequestedFlow} to {@link ActivateFlowMonitoringInfoData}.
     */
    @Mapping(target = "flowId", source = "flowId")
    @Mapping(target = "source.datapath", source = "srcSwitch")
    @Mapping(target = "source.portNumber", source = "srcPort")
    @Mapping(target = "source.vlanId", source = "srcVlan")
    @Mapping(target = "source.innerVlanId", source = "srcInnerVlan")
    @Mapping(target = "destination.datapath", source = "destSwitch")
    @Mapping(target = "destination.portNumber", source = "destPort")
    @Mapping(target = "destination.vlanId", source = "destVlan")
    @Mapping(target = "destination.innerVlanId", source = "destInnerVlan")
    public abstract ActivateFlowMonitoringInfoData toActivateFlowMonitoringInfoData(RequestedFlow flow);

    public SwitchId mapSwitchId(String value) {
        return value == null ? null : new SwitchId(value);
    }
}
