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

package org.openkilda.wfm.topology.flowhs.fsm.reroute;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.model.IslEndpoint;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowContext;
import org.openkilda.wfm.topology.flowhs.service.DbResponse;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Set;

@Getter
@EqualsAndHashCode(callSuper = true)
public class FlowRerouteContext extends FlowContext {
    private String flowId;
    private Set<IslEndpoint> affectedIsl;
    private boolean forceReroute;
    private boolean ignoreBandwidth;
    private boolean effectivelyDown;
    private String rerouteReason;

    @Builder
    public FlowRerouteContext(
            SpeakerFlowSegmentResponse speakerFlowResponse, DbResponse dbResponse, String flowId,
            Set<IslEndpoint> affectedIsl, boolean forceReroute, boolean ignoreBandwidth, boolean effectivelyDown,
            String rerouteReason) {
        super(speakerFlowResponse, dbResponse);
        this.flowId = flowId;
        this.affectedIsl = affectedIsl;
        this.forceReroute = forceReroute;
        this.ignoreBandwidth = ignoreBandwidth;
        this.effectivelyDown = effectivelyDown;
        this.rerouteReason = rerouteReason;
    }
}
