/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.topology.ping.model;

import org.openkilda.messaging.model.FlowDirection;
import org.openkilda.messaging.model.Ping;
import org.openkilda.messaging.model.PingMeters;
import org.openkilda.model.Flow;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PingContext implements Serializable {
    public enum Kinds {
        PERIODIC,
        ON_DEMAND
    }

    private Kinds kind;
    private GroupId group;

    private Flow flow;
    private FlowDirection direction;
    private Long timeout;

    private Ping ping;

    private Long timestamp;
    private Ping.Errors error;
    private PingMeters meters;

    public PingContext(Kinds kind, Flow flow) {
        this.kind = kind;
        this.flow = flow;
    }

    public String getFlowId() {
        return flow.getFlowId();
    }

    /**
     * Return ping ID if ping field is filled.
     */
    public UUID getPingId() {
        if (ping == null) {
            return null;
        }
        return ping.getPingId();
    }

    public boolean isError() {
        return error != null;
    }

    /**
     * Some set of error is permanent.
     *
     * <p>I.e. ping will always fail on this combination of source and dest. This method return true if current error is
     * a permanent error.
     */
    public boolean isPermanentError() {
        if (! isError()) {
            return false;
        }

        boolean result;
        switch (error) {
            case NOT_CAPABLE:
                result = true;
                break;

            default:
                result = false;
        }
        return result;
    }

    /**
     * Return flow's cookie.
     *
     * <p>It use direction value to determine which(forward, reverse, of flagless) cookie should be returned.
     */
    public long getCookie() {
        long value;
        if (direction == null) {
            value = FlowSegmentCookieSchema.INSTANCE.getFlowEffectiveId(flow.getForwardPath().getCookie());
        } else if (direction == FlowDirection.FORWARD) {
            value = flow.getForwardPath().getCookie().getValue();
        } else if (direction == FlowDirection.REVERSE) {
            value = flow.getReversePath().getCookie().getValue();
        } else {
            throw new IllegalArgumentException(String.format(
                    "Unsupported %s.%s value", FlowDirection.class.getName(), direction));
        }
        return value;
    }

    @Override
    public String toString() {
        return String.format(
                "<%s{flowId=%s, error=%s: %s}>", getClass().getCanonicalName(), getFlowId(), getError(), getPing());
    }
}
