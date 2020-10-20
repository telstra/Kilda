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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.response;

import org.openkilda.messaging.MessageContext;
import org.openkilda.model.Flow;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.UUID;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AllocateProtectedResourcesResponse extends BaseAllocateResourcesResponse {
    AllocatePathStatus allocatePathStatus;

    @Builder
    public AllocateProtectedResourcesResponse(
            @NonNull MessageContext messageContext, @NonNull UUID commandId, PathId forwardPathId, PathId reversePathId,
            FlowResources flowResources, PathComputationStrategy newPrimaryPathComputationStrategy,
            FlowPathPair newPaths, Flow flow, AllocatePathStatus allocatePathStatus) {
        super(messageContext, commandId, forwardPathId, reversePathId, flowResources, newPrimaryPathComputationStrategy,
                newPaths, flow);
        this.allocatePathStatus = allocatePathStatus;
    }

    public enum AllocatePathStatus {
        FOUND_OVERLAPPING_PATH,
        FOUND_NEW_PATH,
        FOUND_SAME_PATH
    }
}
