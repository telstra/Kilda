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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.model.DetectConnectedDevicesDto;
import org.openkilda.messaging.model.SwapFlowDto;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.topology.flowhs.model.DetectConnectedDevices;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import org.junit.Test;

public class RequestedFlowMapperTest {

    public static final String FLOW_ID = "flow_id";
    public static final SwitchId SRC_SWITCH_ID = new SwitchId("1");
    public static final SwitchId DST_SWITCH_ID = new SwitchId("2");
    public static final int SRC_PORT = 1;
    public static final int DST_PORT = 2;
    public static final int SRC_VLAN = 3;
    public static final int DST_VLAN = 4;
    public static final int SRC_INNER_VLAN = 5;
    public static final int DST_INNER_VLAN = 6;
    public static final Integer PRIORITY = 5;
    public static final String DIVERSE_FLOW_ID = "flow_2";
    public static final String DESCRIPTION = "description";
    public static final int BANDWIDTH = 1000;
    public static final Long MAX_LATENCY = 200L;
    public static final FlowEncapsulationType ENCAPSULATION_TYPE = FlowEncapsulationType.TRANSIT_VLAN;
    public static final PathComputationStrategy PATH_COMPUTATION_STRATEGY = PathComputationStrategy.COST;

    private static FlowEndpoint sourceEndpoint = new FlowEndpoint(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, SRC_INNER_VLAN);
    private static FlowEndpoint desctinationEndpoint = new FlowEndpoint(
            DST_SWITCH_ID, DST_PORT, DST_VLAN, DST_INNER_VLAN);

    private FlowRequest flowRequest = FlowRequest.builder()
            .flowId(FLOW_ID)
            .source(sourceEndpoint)
            .destination(desctinationEndpoint)
            .priority(PRIORITY)
            .diverseFlowId(DIVERSE_FLOW_ID)
            .description(DESCRIPTION)
            .bandwidth(BANDWIDTH)
            .maxLatency(MAX_LATENCY)
            .encapsulationType(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN)
            .pathComputationStrategy(PATH_COMPUTATION_STRATEGY.toString().toLowerCase())
            .detectConnectedDevices(new DetectConnectedDevicesDto(true, true, true, true, true, true, true, true))
            .pinned(true)
            .allocateProtectedPath(true)
            .ignoreBandwidth(true)
            .periodicPings(true)
            .loopSwitchId(sourceEndpoint.getSwitchId())
            .build();

    private Flow flow = Flow.builder()
            .flowId(FLOW_ID)
            .srcSwitch(Switch.builder().switchId(SRC_SWITCH_ID).build())
            .srcPort(SRC_PORT)
            .srcVlan(SRC_VLAN)
            .srcInnerVlan(SRC_INNER_VLAN)
            .destSwitch(Switch.builder().switchId(DST_SWITCH_ID).build())
            .destPort(DST_PORT)
            .destVlan(DST_VLAN)
            .destInnerVlan(DST_INNER_VLAN)
            .priority(PRIORITY)
            .description(DESCRIPTION)
            .bandwidth(BANDWIDTH)
            .maxLatency(MAX_LATENCY)
            .encapsulationType(ENCAPSULATION_TYPE)
            .pathComputationStrategy(PATH_COMPUTATION_STRATEGY)
            .detectConnectedDevices(
                    new org.openkilda.model.DetectConnectedDevices(true, true, true, true, true, true, true, true))
            .pinned(true)
            .allocateProtectedPath(true)
            .ignoreBandwidth(true)
            .periodicPings(true)
            .build();

    @Test
    public void mapFlowRequestToRequestedFlowTest() {
        RequestedFlow requestedFlow = RequestedFlowMapper.INSTANCE.toRequestedFlow(flowRequest);
        assertEquals(FLOW_ID, requestedFlow.getFlowId());
        assertEquals(SRC_SWITCH_ID, requestedFlow.getSrcSwitch());
        assertEquals(SRC_PORT, requestedFlow.getSrcPort());
        assertEquals(SRC_VLAN, requestedFlow.getSrcVlan());
        assertEquals(SRC_INNER_VLAN, requestedFlow.getSrcInnerVlan());
        assertEquals(DST_SWITCH_ID, requestedFlow.getDestSwitch());
        assertEquals(DST_PORT, requestedFlow.getDestPort());
        assertEquals(DST_VLAN, requestedFlow.getDestVlan());
        assertEquals(DST_INNER_VLAN, requestedFlow.getDestInnerVlan());
        assertEquals(PRIORITY, requestedFlow.getPriority());
        assertEquals(DIVERSE_FLOW_ID, requestedFlow.getDiverseFlowId());
        assertEquals(DESCRIPTION, requestedFlow.getDescription());
        assertEquals(BANDWIDTH, requestedFlow.getBandwidth());
        assertEquals(MAX_LATENCY, requestedFlow.getMaxLatency());
        assertEquals(ENCAPSULATION_TYPE, requestedFlow.getFlowEncapsulationType());
        assertTrue(requestedFlow.isPinned());
        assertTrue(requestedFlow.isAllocateProtectedPath());
        assertTrue(requestedFlow.isIgnoreBandwidth());
        assertTrue(requestedFlow.isPeriodicPings());
        assertEquals(new DetectConnectedDevices(true, true, true, true, true, true, true, true),
                requestedFlow.getDetectConnectedDevices());
    }

    @Test
    public void mapFlowToRequestedFlowTest() {
        RequestedFlow requestedFlow = RequestedFlowMapper.INSTANCE.toRequestedFlow(flow);
        assertEquals(FLOW_ID, requestedFlow.getFlowId());
        assertEquals(SRC_SWITCH_ID, requestedFlow.getSrcSwitch());
        assertEquals(SRC_PORT, requestedFlow.getSrcPort());
        assertEquals(SRC_VLAN, requestedFlow.getSrcVlan());
        assertEquals(SRC_INNER_VLAN, requestedFlow.getSrcInnerVlan());
        assertEquals(DST_SWITCH_ID, requestedFlow.getDestSwitch());
        assertEquals(DST_PORT, requestedFlow.getDestPort());
        assertEquals(DST_VLAN, requestedFlow.getDestVlan());
        assertEquals(DST_INNER_VLAN, requestedFlow.getDestInnerVlan());
        assertEquals(PRIORITY, requestedFlow.getPriority());
        assertEquals(DESCRIPTION, requestedFlow.getDescription());
        assertEquals(BANDWIDTH, requestedFlow.getBandwidth());
        assertEquals(MAX_LATENCY, requestedFlow.getMaxLatency());
        assertEquals(ENCAPSULATION_TYPE, requestedFlow.getFlowEncapsulationType());
        assertTrue(requestedFlow.isPinned());
        assertTrue(requestedFlow.isAllocateProtectedPath());
        assertTrue(requestedFlow.isIgnoreBandwidth());
        assertTrue(requestedFlow.isPeriodicPings());
        assertEquals(new DetectConnectedDevices(true, true, true, true, true, true, true, true),
                requestedFlow.getDetectConnectedDevices());

        Flow mappedFlow = RequestedFlowMapper.INSTANCE.toFlow(requestedFlow);
        assertEquals(flow, mappedFlow);
    }

    @Test
    public void mapRequestedFlowToFlow() {
        RequestedFlow requestedFlow = RequestedFlowMapper.INSTANCE.toRequestedFlow(flowRequest);
        Flow result = RequestedFlowMapper.INSTANCE.toFlow(requestedFlow);

        assertEquals(FLOW_ID, result.getFlowId());

        assertEquals(SRC_SWITCH_ID, result.getSrcSwitchId());
        assertEquals(DST_SWITCH_ID, result.getDestSwitchId());

        assertEquals(SRC_PORT, result.getSrcPort());
        assertEquals(SRC_VLAN, result.getSrcVlan());
        assertEquals(SRC_INNER_VLAN, result.getSrcInnerVlan());

        assertEquals(DST_PORT, result.getDestPort());
        assertEquals(DST_VLAN, result.getDestVlan());
        assertEquals(DST_INNER_VLAN, result.getDestInnerVlan());

        assertNull(result.getForwardPathId());
        assertNull(result.getReversePathId());

        assertTrue(result.isAllocateProtectedPath());

        assertNull(result.getProtectedForwardPathId());
        assertNull(result.getProtectedReversePathId());

        assertNull(result.getGroupId());

        assertEquals(BANDWIDTH, result.getBandwidth());
        assertTrue(result.isIgnoreBandwidth());

        assertEquals(DESCRIPTION, result.getDescription());

        assertTrue(result.isPeriodicPings());

        assertEquals(ENCAPSULATION_TYPE, result.getEncapsulationType());

        assertNull(result.getStatus());

        assertEquals(MAX_LATENCY, result.getMaxLatency());
        assertEquals(PRIORITY, result.getPriority());

        assertNull(result.getTimeCreate());
        assertNull(result.getTimeModify());

        assertTrue(result.isPinned());

        assertEquals(
                new org.openkilda.model.DetectConnectedDevices(true, true, true, true, true, true, true, true),
                result.getDetectConnectedDevices());

        assertEquals(PATH_COMPUTATION_STRATEGY, result.getPathComputationStrategy());
        assertNull(result.getTargetPathComputationStrategy());

        assertTrue(result.isLooped());
        assertEquals(flow.getSrcSwitchId(), result.getSrcSwitchId());
    }

    @Test
    public void mapFlowRequestToRequestedFlow() {
        RequestedFlow result = RequestedFlowMapper.INSTANCE.toRequestedFlow(flowRequest);

        assertEquals(FLOW_ID, result.getFlowId());
        assertEquals(SRC_SWITCH_ID, result.getSrcSwitch());
        assertEquals(SRC_PORT, result.getSrcPort());
        assertEquals(SRC_VLAN, result.getSrcVlan());
        assertEquals(SRC_INNER_VLAN, result.getSrcInnerVlan());

        assertEquals(DST_SWITCH_ID, result.getDestSwitch());
        assertEquals(DST_PORT, result.getDestPort());
        assertEquals(DST_VLAN, result.getDestVlan());
        assertEquals(DST_INNER_VLAN, result.getDestInnerVlan());

        assertEquals(PRIORITY, result.getPriority());
        assertTrue(result.isPinned());
        assertTrue(result.isAllocateProtectedPath());

        assertEquals(DIVERSE_FLOW_ID, result.getDiverseFlowId());

        assertEquals(DESCRIPTION, result.getDescription());
        assertEquals(BANDWIDTH, result.getBandwidth());
        assertTrue(result.isIgnoreBandwidth());
        assertTrue(result.isPeriodicPings());
        assertEquals(MAX_LATENCY, result.getMaxLatency());

        assertEquals(ENCAPSULATION_TYPE, result.getFlowEncapsulationType());
        assertEquals(PATH_COMPUTATION_STRATEGY, result.getPathComputationStrategy());
        assertEquals(
                new DetectConnectedDevices(true, true, true, true, true, true, true, true),
                result.getDetectConnectedDevices());
    }

    @Test
    public void mapFlowToFlowRequestTest() {
        FlowRequest flowRequest = RequestedFlowMapper.INSTANCE.toFlowRequest(flow);
        assertEquals(FLOW_ID, flowRequest.getFlowId());
        assertEquals(SRC_SWITCH_ID, flowRequest.getSource().getSwitchId());
        assertEquals(SRC_PORT, (int) flowRequest.getSource().getPortNumber());
        assertEquals(SRC_VLAN, flowRequest.getSource().getOuterVlanId());
        assertEquals(DST_SWITCH_ID, flowRequest.getDestination().getSwitchId());
        assertEquals(DST_PORT, (int) flowRequest.getDestination().getPortNumber());
        assertEquals(DST_VLAN, flowRequest.getDestination().getOuterVlanId());
        assertEquals(PRIORITY, flowRequest.getPriority());
        assertEquals(DESCRIPTION, flowRequest.getDescription());
        assertEquals(BANDWIDTH, flowRequest.getBandwidth());
        assertEquals(MAX_LATENCY, flowRequest.getMaxLatency());
        assertEquals(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN,
                flowRequest.getEncapsulationType());
        assertEquals(PATH_COMPUTATION_STRATEGY.toString().toLowerCase(), flowRequest.getPathComputationStrategy());
        assertTrue(flowRequest.isPinned());
        assertTrue(flowRequest.isAllocateProtectedPath());
        assertTrue(flowRequest.isIgnoreBandwidth());
        assertTrue(flowRequest.isPeriodicPings());
        assertEquals(new DetectConnectedDevicesDto(true, true, true, true, true, true, true, true),
                flowRequest.getDetectConnectedDevices());
    }

    @Test
    public void mapSwapDtoToRequesterFlowTest() {
        SwapFlowDto swapFlowDto = SwapFlowDto.builder()
                .flowId(FLOW_ID)
                .sourceSwitch(SRC_SWITCH_ID)
                .sourcePort(SRC_PORT)
                .sourceVlan(SRC_VLAN)
                .destinationSwitch(DST_SWITCH_ID)
                .destinationPort(DST_PORT)
                .destinationVlan(DST_VLAN)
                .build();
        RequestedFlow requestedFlow = RequestedFlowMapper.INSTANCE.toRequestedFlow(swapFlowDto);
        assertEquals(FLOW_ID, requestedFlow.getFlowId());
        assertEquals(SRC_SWITCH_ID, requestedFlow.getSrcSwitch());
        assertEquals(SRC_PORT, requestedFlow.getSrcPort());
        assertEquals(SRC_VLAN, requestedFlow.getSrcVlan());
        assertEquals(DST_SWITCH_ID, requestedFlow.getDestSwitch());
        assertEquals(DST_PORT, requestedFlow.getDestPort());
        assertEquals(DST_VLAN, requestedFlow.getDestVlan());
    }
}
