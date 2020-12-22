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

package org.openkilda.wfm.topology.flowhs.model;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.SwitchId;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RequestedFlow {
    private String flowId;

    private SwitchId srcSwitch;
    private int srcPort;
    private int srcVlan;
    private int srcInnerVlan;

    private SwitchId destSwitch;
    private int destPort;
    private int destVlan;
    private int destInnerVlan;

    private Integer priority;
    private boolean pinned;
    private boolean allocateProtectedPath;
    private String diverseFlowId;

    private String description;
    private long bandwidth;
    private boolean ignoreBandwidth;
    private boolean periodicPings;
    private Long maxLatency;
    private Long maxLatencyTier2;
    private FlowEncapsulationType flowEncapsulationType;
    private PathComputationStrategy pathComputationStrategy;
    private DetectConnectedDevices detectConnectedDevices;

    private SwitchId loopSwitchId;
}
