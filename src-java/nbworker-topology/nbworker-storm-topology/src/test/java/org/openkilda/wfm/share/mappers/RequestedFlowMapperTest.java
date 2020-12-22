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

package org.openkilda.wfm.share.mappers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.model.DetectConnectedDevicesDto;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;

import org.junit.Test;

public class RequestedFlowMapperTest {

    public static final String FLOW_ID = "flow_id";
    public static final SwitchId SRC_SWITCH_ID = new SwitchId("1");
    public static final SwitchId DST_SWITCH_ID = new SwitchId("2");
    public static final Integer SRC_PORT = 1;
    public static final Integer DST_PORT = 2;
    public static final int SRC_VLAN = 3;
    public static final int DST_VLAN = 4;
    public static final Integer PRIORITY = 5;
    public static final String DESCRIPTION = "description";
    public static final int BANDWIDTH = 1000;
    public static final Long MAX_LATENCY = 200L;
    public static final String PATH_COMPUTATION_STRATEGY = PathComputationStrategy.COST.toString().toLowerCase();

    private Flow flow = Flow.builder()
            .flowId(FLOW_ID)
            .srcSwitch(Switch.builder().switchId(SRC_SWITCH_ID).build())
            .srcPort(SRC_PORT)
            .srcVlan(SRC_VLAN)
            .destSwitch(Switch.builder().switchId(DST_SWITCH_ID).build())
            .destPort(DST_PORT)
            .destVlan(DST_VLAN)
            .priority(PRIORITY)
            .description(DESCRIPTION)
            .bandwidth(BANDWIDTH)
            .maxLatency(MAX_LATENCY)
            .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
            .pathComputationStrategy(PathComputationStrategy.COST)
            .detectConnectedDevices(
                    new org.openkilda.model.DetectConnectedDevices(true, true, true, true, true, true, true, true))
            .pinned(true)
            .allocateProtectedPath(true)
            .ignoreBandwidth(true)
            .periodicPings(true)
            .build();

    @Test
    public void mapFlowToFlowRequestTest() {
        FlowRequest flowRequest = RequestedFlowMapper.INSTANCE.toFlowRequest(flow);
        assertEquals(FLOW_ID, flowRequest.getFlowId());
        assertEquals(SRC_SWITCH_ID, flowRequest.getSource().getSwitchId());
        assertEquals(SRC_PORT, flowRequest.getSource().getPortNumber());
        assertEquals(SRC_VLAN, flowRequest.getSource().getOuterVlanId());
        assertEquals(DST_SWITCH_ID, flowRequest.getDestination().getSwitchId());
        assertEquals(DST_PORT, flowRequest.getDestination().getPortNumber());
        assertEquals(DST_VLAN, flowRequest.getDestination().getOuterVlanId());
        assertEquals(PRIORITY, flowRequest.getPriority());
        assertEquals(DESCRIPTION, flowRequest.getDescription());
        assertEquals(BANDWIDTH, flowRequest.getBandwidth());
        assertEquals(MAX_LATENCY, flowRequest.getMaxLatency());
        assertEquals(org.openkilda.messaging.payload.flow.FlowEncapsulationType.TRANSIT_VLAN,
                flowRequest.getEncapsulationType());
        assertEquals(PATH_COMPUTATION_STRATEGY, flowRequest.getPathComputationStrategy());
        assertTrue(flowRequest.isPinned());
        assertTrue(flowRequest.isAllocateProtectedPath());
        assertTrue(flowRequest.isIgnoreBandwidth());
        assertTrue(flowRequest.isPeriodicPings());
        assertEquals(new DetectConnectedDevicesDto(true, true, true, true, true, true, true, true),
                flowRequest.getDetectConnectedDevices());
    }
}
