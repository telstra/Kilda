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

package org.openkilda.persistence.dummy;

import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathComputationStrategy;

import lombok.Data;

@Data
public class FlowDefaults {
    long bandwidth = 1000;
    boolean ignoreBandwidth = false;

    boolean periodicPings = false;
    boolean allocateProtectedPath = false;
    boolean pinned = false;

    Long maxLatency = 500L;
    Long maxLatencyTier2 = 700L;
    Integer priority = 0;

    FlowStatus status = FlowStatus.UP;
    FlowEncapsulationType encapsulationType = FlowEncapsulationType.TRANSIT_VLAN;
    PathComputationStrategy pathComputationStrategy = PathComputationStrategy.COST;
    DetectConnectedDevices detectConnectedDevices = new DetectConnectedDevices(
            false, false, false, false, false, false, false, false);

    String description = "dummy flow";

    /**
     * Populate {@link Flow} object with defaults.
     */
    public Flow.FlowBuilder fill(Flow.FlowBuilder flow) {
        flow.bandwidth(bandwidth);
        flow.ignoreBandwidth(ignoreBandwidth);
        flow.periodicPings(periodicPings);
        flow.allocateProtectedPath(allocateProtectedPath);
        flow.pinned(pinned);
        flow.maxLatency(maxLatency);
        flow.maxLatencyTier2(maxLatencyTier2);
        flow.priority(priority);
        flow.status(status);
        flow.encapsulationType(encapsulationType);
        flow.pathComputationStrategy(pathComputationStrategy);
        flow.detectConnectedDevices(detectConnectedDevices);
        flow.description(description);

        return flow;
    }
}
