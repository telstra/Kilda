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

package org.openkilda.wfm.topology.reroute.model;

import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData.FlowThrottlingDataBuilder;
import org.openkilda.wfm.topology.reroute.service.IRerouteQueueCarrier;

import com.google.common.collect.Sets;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Optional;

@Slf4j
@Builder
@Data
public class RerouteQueue {

    private FlowThrottlingData inProgress;
    private FlowThrottlingData pending;
    private FlowThrottlingData throttling;

    public static RerouteQueue empty() {
        return RerouteQueue.builder().build();
    }

    /**
     * Put reroute request in progress.
     * @param flowThrottlingData reroute request.
     */
    public void putToInProgress(FlowThrottlingData flowThrottlingData) {
        inProgress = flowThrottlingData;
    }

    /**
     * Put reroute request into throttling.
     * @param flowThrottlingData reroute request.
     */
    public void putToThrottling(FlowThrottlingData flowThrottlingData) {
        throttling = merge(flowThrottlingData, throttling);
    }

    /**
     * Check if queue is ready to process new reroute request.
     * @return true if ready.
     */
    public boolean hasInProgress() {
        return inProgress != null;
    }

    /**
     * Process reroute retry request.
     * @param retryRequest retry request.
     * @param carrier carrier.
     * @return new request to send or null if no nothing ready to send.
     */
    public FlowThrottlingData processRetryRequest(FlowThrottlingData retryRequest, IRerouteQueueCarrier carrier) {
        if (pending == null) {
            putToThrottling(retryRequest);
            carrier.sendExtendTimeWindowEvent();
            inProgress = null;
        } else {
            inProgress = merge(retryRequest, pending);
            pending = null;
        }
        return inProgress;
    }

    /**
     * Move pending to in progress.
     * @return reroute request to send if any.
     */
    public FlowThrottlingData processPending() {
        inProgress = pending;
        pending = null;
        return inProgress;
    }

    /**
     * Move request from throttling to pending or in progress.
     * @return reroute request to send if any.
     */
    public Optional<FlowThrottlingData> flushThrottling() {
        if (throttling == null) {
            return Optional.empty();
        }
        if (inProgress == null) {
            inProgress = throttling;
            throttling = null;
            return Optional.of(inProgress);
        } else {
            pending = merge(throttling, pending);
            throttling = null;
            return Optional.empty();
        }
    }

    private FlowThrottlingData merge(FlowThrottlingData first, FlowThrottlingData second) {
        if (second == null) {
            return first;
        }
        log.info("Merge reroute requests. Previous correlationId {}. New correlationId {} and reason {}.",
                second.getCorrelationId(), first.getCorrelationId(), first.getReason());
        FlowThrottlingDataBuilder merged = FlowThrottlingData.builder();
        merged.correlationId(first.getCorrelationId());
        if (first.getAffectedIsl().isEmpty() || second.getAffectedIsl().isEmpty()) {
            merged.affectedIsl(Collections.emptySet());
        } else {
            merged.affectedIsl(Sets.union(first.getAffectedIsl(), second.getAffectedIsl()));
        }
        merged.force(first.isForce() || second.isForce());
        merged.ignoreBandwidth(first.isIgnoreBandwidth() || second.isIgnoreBandwidth());
        merged.effectivelyDown(first.isEffectivelyDown() || second.isEffectivelyDown());
        merged.reason(second.getReason());
        merged.retryCounter(Math.min(first.getRetryCounter(), second.getRetryCounter()));
        merged.priority(Math.max(Optional.ofNullable(first.getPriority()).orElse(0),
                Optional.ofNullable(second.getPriority()).orElse(0)));
        merged.timeCreate(first.getTimeCreate());

        return merged.build();
    }
}
