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

package org.openkilda.pce.impl;

import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;

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
    private long unmaskedCookie = 1;
    private long bandwidth;
    private boolean ignoreBandwidth = false;
    private PathComputationStrategy pathComputationStrategy = PathComputationStrategy.COST;

    public TestFlowBuilder() {
    }

    public TestFlowBuilder(String flowId) {
        this.flowId = flowId;
    }

    /**
     * Build a UnidirectionalFlow with set properties.
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
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .pathComputationStrategy(pathComputationStrategy)
                .build();

        FlowPath forwardPath =
                buildFlowPath(flow, srcSwitch, destSwitch, Cookie.buildForwardCookie(unmaskedCookie));
        FlowPath reversePath =
                buildFlowPath(flow, destSwitch, srcSwitch, Cookie.buildReverseCookie(unmaskedCookie));

        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);

        return flow;
    }

    private FlowPath buildFlowPath(Flow flow, Switch pathSrc, Switch pathDest, Cookie cookie) {
        return FlowPath.builder()
                .pathId(new PathId(UUID.randomUUID().toString()))
                .srcSwitch(pathSrc)
                .destSwitch(pathDest)
                .cookie(cookie)
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth())
                .build();
    }
}
