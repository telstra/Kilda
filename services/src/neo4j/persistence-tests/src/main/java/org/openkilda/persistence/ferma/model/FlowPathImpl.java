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

package org.openkilda.persistence.ferma.model;

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Represents a flow path.
 */
@Data
@EqualsAndHashCode(exclude = {"segments", "flow"})
@ToString(exclude = {"segments", "flow"})
public class FlowPathImpl implements FlowPath {
    private PathId pathId;
    private Flow flow;
    private Cookie cookie;
    private MeterId meterId;
    private long latency;
    private long bandwidth;
    private boolean ignoreBandwidth;
    private Instant timeCreate;
    private Instant timeModify;
    private FlowPathStatus status;
    private Switch srcSwitch;
    private Switch destSwitch;
    private List<PathSegment> segments = new ArrayList<>();

    @Builder(toBuilder = true)
    public FlowPathImpl(@NonNull PathId pathId, @NonNull Switch srcSwitch, @NonNull Switch destSwitch,
                        @NonNull Flow flow, Cookie cookie, MeterId meterId,
                        long latency, long bandwidth, boolean ignoreBandwidth,
                        Instant timeCreate, Instant timeModify,
                        FlowPathStatus status, List<PathSegment> segments) {
        this.pathId = pathId;
        this.srcSwitch = srcSwitch;
        this.destSwitch = destSwitch;
        this.flow = flow;
        this.cookie = cookie;
        this.meterId = meterId;
        this.latency = latency;
        this.bandwidth = bandwidth;
        this.ignoreBandwidth = ignoreBandwidth;
        this.timeCreate = timeCreate;
        this.timeModify = timeModify;
        this.status = status;
        setSegments(segments != null ? segments : new ArrayList<>());
    }

    public static FlowPathImplBuilder clone(FlowPath flowPath) {
        if (flowPath instanceof FlowPathImpl) {
            return ((FlowPathImpl) flowPath).toBuilder();
        } else {
            return FlowPathImpl.builder()
                    .pathId(flowPath.getPathId())
                    .srcSwitch(flowPath.getSrcSwitch())
                    .destSwitch(flowPath.getDestSwitch())
                    .flow(flowPath.getFlow())
                    .cookie(flowPath.getCookie())
                    .meterId(flowPath.getMeterId())
                    .latency(flowPath.getLatency())
                    .bandwidth(flowPath.getBandwidth())
                    .ignoreBandwidth(flowPath.isIgnoreBandwidth())
                    .timeCreate(flowPath.getTimeCreate())
                    .timeModify(flowPath.getTimeModify())
                    .status(flowPath.getStatus());
        }
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return Collections.unmodifiableList(segments);
    }

    /**
     * Set the segments.
     */
    @Override
    public final void setPathSegments(List<PathSegment> segments) {
        for (int idx = 0; idx < segments.size(); idx++) {
            PathSegment segment = segments.get(idx);
            segment.setSeqId(idx);
            ((PathSegmentImpl) segment).setPath(this);
        }

        this.segments = new ArrayList<>(segments);
    }

    @Override
    public SwitchId getSrcSwitchId() {
        return Optional.ofNullable(getSrcSwitch()).map(Switch::getSwitchId).orElse(null);
    }

    @Override
    public SwitchId getDestSwitchId() {
        return Optional.ofNullable(getDestSwitch()).map(Switch::getSwitchId).orElse(null);
    }
}