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

package org.openkilda.adapter;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;

import lombok.Getter;

public abstract class FlowSideAdapter {
    @Getter
    protected final Flow flow;

    /**
     * Determine "forward" direction for provided flow/path pair and create adapter to access source endpoint.
     */
    public static FlowSideAdapter makeIngressAdapter(Flow flow, FlowPath path) {
        if (FlowSegmentCookieSchema.INSTANCE.getValidatedDirection(path.getCookie()) == FlowPathDirection.FORWARD) {
            return new FlowSourceAdapter(flow);
        } else {
            return new FlowDestAdapter(flow);
        }
    }

    /**
     * Determine "forward" direction for provided flow/path pair and create adapter to access dest endpoint.
     */
    public static FlowSideAdapter makeEgressAdapter(Flow flow, FlowPath path) {
        if (FlowSegmentCookieSchema.INSTANCE.getValidatedDirection(path.getCookie()) == FlowPathDirection.FORWARD) {
            return new FlowDestAdapter(flow);
        } else {
            return new FlowSourceAdapter(flow);
        }
    }

    protected FlowSideAdapter(Flow flow) {
        this.flow = flow;
    }

    public abstract FlowEndpoint getEndpoint();

    public abstract boolean isMultiTableSegment();
}
