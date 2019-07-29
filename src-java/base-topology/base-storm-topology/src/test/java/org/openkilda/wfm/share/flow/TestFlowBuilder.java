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

package org.openkilda.wfm.share.flow;

import org.openkilda.model.Cookie;
import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.Vxlan;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.transitvlan.TransitVlanEncapsulation;
import org.openkilda.wfm.share.flow.resources.vxlan.VxlanEncapsulation;
import org.openkilda.wfm.topology.flow.model.FlowPathsWithEncapsulation;

import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.UUID;

@Setter
@Accessors(fluent = true)
public class TestFlowBuilder {

    private String flowId = UUID.randomUUID().toString();
    private Switch srcSwitch;
    private int srcPort;
    private int srcVlan;
    private Switch destSwitch;
    private int destPort;
    private int destVlan;
    private Long cookie = null;
    private long unmaskedCookie = 1;
    private long bandwidth;
    private boolean ignoreBandwidth = false;
    private int transitEncapsulationId = 0;
    private Integer meterId = null;
    private FlowStatus status = FlowStatus.UP;
    private Long maxLatency = null;
    private Integer priority = null;
    private DetectConnectedDevices detectConnectedDevices = DetectConnectedDevices.builder().build();
    private FlowEncapsulationType encapsulationType;
    private PathComputationStrategy pathComputationStrategy;

    public TestFlowBuilder() {
    }

    public TestFlowBuilder(String flowId) {
        this.flowId = flowId;
    }

    /**
     * Build a Flow with set properties.
     */
    public Flow build() {
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .srcVlan(srcVlan)
                .destSwitch(destSwitch)
                .destPort(destPort)
                .destVlan(destVlan)
                .bandwidth(bandwidth)
                .ignoreBandwidth(ignoreBandwidth)
                .encapsulationType(encapsulationType)
                .maxLatency(maxLatency)
                .priority(priority)
                .detectConnectedDevices(detectConnectedDevices)
                .pathComputationStrategy(pathComputationStrategy)
                .build();
        flow.setStatus(status);

        FlowPath forwardPath =
                buildFlowPath(flow, srcSwitch, destSwitch,
                        cookie != null ? new Cookie(cookie) : Cookie.buildForwardCookie(unmaskedCookie),
                        meterId != null ? new MeterId(meterId) : null);
        FlowPath reversePath =
                buildFlowPath(flow, destSwitch, srcSwitch, Cookie.buildReverseCookie(unmaskedCookie), null);

        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);

        return flow;
    }

    /**
     * Build a UnidirectionalFlow with set properties.
     */
    public FlowPathsWithEncapsulation buildFlowPathsWithEncapsulation() {
        Flow flow = build();
        EncapsulationResources encapsulationResources = null;
        if (FlowEncapsulationType.TRANSIT_VLAN.equals(encapsulationType)) {
            //TODO: hard-coded encapsulation will be removed in Flow H&S
            TransitVlan transitVlan = TransitVlan.builder()
                    .flowId(flowId)
                    .pathId(flow.getForwardPath().getPathId())
                    .vlan(transitEncapsulationId > 0 ? transitEncapsulationId : srcVlan + destVlan + 1)
                    .build();
            encapsulationResources = TransitVlanEncapsulation.builder().transitVlan(transitVlan).build();
        } else if (FlowEncapsulationType.VXLAN.equals(encapsulationType)) {
            Vxlan vxlan = Vxlan.builder()
                    .flowId(flowId)
                    .pathId(flow.getForwardPath().getPathId())
                    .vni(transitEncapsulationId > 0 ? transitEncapsulationId : srcVlan + destVlan + 1)
                    .build();
            encapsulationResources = VxlanEncapsulation.builder().vxlan(vxlan).build();
        }
        return FlowPathsWithEncapsulation.builder().flow(flow).forwardPath(flow.getForwardPath())
                .forwardEncapsulation(encapsulationResources).build();
    }

    private FlowPath buildFlowPath(Flow flow, Switch pathSrc, Switch pathDest, Cookie cookie, MeterId meterId) {
        return FlowPath.builder()
                .pathId(new PathId(UUID.randomUUID().toString()))
                .srcSwitch(pathSrc)
                .destSwitch(pathDest)
                .cookie(cookie)
                .meterId(meterId)
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth())
                .build();
    }
}
