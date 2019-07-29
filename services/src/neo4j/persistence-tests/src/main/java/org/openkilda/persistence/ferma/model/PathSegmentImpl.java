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

import org.openkilda.model.SwitchId;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

import java.util.Optional;

/**
 * Represents a segment of a flow path.
 */
@Data
@EqualsAndHashCode(exclude = {"path"})
@ToString(exclude = {"path"})
public class PathSegmentImpl implements PathSegment {
    private int srcPort;
    private int destPort;
    private Long latency;
    private boolean failed = false;
    private int seqId;
    private Switch srcSwitch;
    private Switch destSwitch;
    private FlowPath path;

    @Builder(toBuilder = true)
    public PathSegmentImpl(@NonNull Switch srcSwitch, @NonNull Switch destSwitch,
                           int srcPort, int destPort, Long latency) {
        this.srcSwitch = srcSwitch;
        this.destSwitch = destSwitch;
        this.srcPort = srcPort;
        this.destPort = destPort;
        this.latency = latency;
    }

    public static PathSegmentImplBuilder clone(PathSegment pathSegment) {
        if (pathSegment instanceof PathSegmentImpl) {
            return ((PathSegmentImpl) pathSegment).toBuilder();
        } else {
            return PathSegmentImpl.builder()
                    .srcPort(pathSegment.getSrcPort())
                    .destPort(pathSegment.getDestPort())
                    .latency(pathSegment.getLatency())
                    .srcSwitch(pathSegment.getSrcSwitch())
                    .destSwitch(pathSegment.getDestSwitch());
        }
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
