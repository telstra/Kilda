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

package org.openkilda.wfm.topology.reroute.model;

import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathComputationStrategy;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Data
public class FlowThrottlingData implements Serializable {
    private String correlationId;
    private Integer priority;
    private Instant timeCreate;
    private Set<IslEndpoint> affectedIsl;
    private boolean force;
    private boolean ignoreBandwidth;
    private boolean effectivelyDown;
    private String reason;
    private PathComputationStrategy pathComputationStrategy;
    private long bandwidth;
    private int retryCounter;

    @Builder
    public FlowThrottlingData(String correlationId, Integer priority, Instant timeCreate,
                              Set<IslEndpoint> affectedIsl, boolean force, boolean ignoreBandwidth,
                              boolean effectivelyDown, String reason, PathComputationStrategy pathComputationStrategy,
                              long bandwidth, int retryCounter) {
        this.correlationId = correlationId;
        this.priority = priority;
        this.timeCreate = timeCreate;

        // FIXME(surabujin): this field treats as mutable set by object owners
        //  org.openkilda.wfm.topology.reroute.service.ReroutesThrottling.putRequest
        this.affectedIsl = new HashSet<>(affectedIsl);
        this.force = force;
        this.ignoreBandwidth = ignoreBandwidth;
        this.effectivelyDown = effectivelyDown;
        this.reason = reason;
        this.pathComputationStrategy = pathComputationStrategy;
        this.bandwidth = bandwidth;
        this.retryCounter = retryCounter;
    }

    public void increaseRetryCounter() {
        retryCounter++;
    }
}
