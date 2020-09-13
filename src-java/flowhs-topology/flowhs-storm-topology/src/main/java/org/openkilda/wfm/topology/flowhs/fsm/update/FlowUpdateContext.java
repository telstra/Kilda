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

package org.openkilda.wfm.topology.flowhs.fsm.update;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowContext;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlowUpdateContext extends FlowContext {
    private RequestedFlow targetFlow;
    private Set<String> bulkUpdateFlowIds;
    private boolean doNotRevert;

    @Builder
    public FlowUpdateContext(SpeakerFlowSegmentResponse speakerFlowResponse, RequestedFlow targetFlow,
                             Set<String> bulkUpdateFlowIds, boolean doNotRevert) {
        super(speakerFlowResponse, null);
        this.targetFlow = targetFlow;
        this.bulkUpdateFlowIds = bulkUpdateFlowIds;
        this.doNotRevert = doNotRevert;
    }
}
