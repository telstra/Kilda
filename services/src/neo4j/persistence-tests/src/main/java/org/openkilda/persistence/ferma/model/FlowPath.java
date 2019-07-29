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

import static java.lang.String.format;

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import java.time.Instant;
import java.util.List;

public interface FlowPath {
    PathId getPathId();

    void setPathId(PathId pathId);

    Cookie getCookie();

    void setCookie(Cookie cookie);

    MeterId getMeterId();

    void setMeterId(MeterId meterId);

    long getLatency();

    void setLatency(long latency);

    long getBandwidth();

    void setBandwidth(long bandwidth);

    boolean isIgnoreBandwidth();

    void setIgnoreBandwidth(boolean ignoreBandwidth);

    Instant getTimeCreate();

    void setTimeCreate(Instant timeCreate);

    Instant getTimeModify();

    void setTimeModify(Instant timeModify);

    FlowPathStatus getStatus();

    void setStatus(FlowPathStatus status);

    Switch getSrcSwitch();

    SwitchId getSrcSwitchId();

    void setSrcSwitch(Switch srcSwitch);

    Switch getDestSwitch();

    SwitchId getDestSwitchId();

    void setDestSwitch(Switch destSwitch);

    List<PathSegment> getPathSegments();

    void setPathSegments(List<PathSegment> segments);

    Flow getFlow();

    /**
     * Sets the current flow path status corresponds with passed {@link FlowStatus} .
     */
    default void setStatusLikeFlow(FlowStatus flowStatus) {
        switch (flowStatus) {
            case UP:
                setStatus(FlowPathStatus.ACTIVE);
                break;
            case DOWN:
                setStatus(FlowPathStatus.INACTIVE);
                break;
            case IN_PROGRESS:
                setStatus(FlowPathStatus.IN_PROGRESS);
                break;
            default:
                throw new IllegalArgumentException(format("Unsupported status value: %s", flowStatus));
        }
    }

    /**
     * Checks whether the flow path goes through a single switch.
     *
     * @return true if source and destination switches are the same, otherwise false
     */
    default boolean isOneSwitchFlow() {
        return getSrcSwitchId().equals(getDestSwitchId());
    }
}
