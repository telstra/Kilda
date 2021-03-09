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

package org.openkilda.wfm.topology.flowhs.fsm.common;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResources;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Getter
@Setter
@Slf4j
public abstract class FlowPathSwappingFsm<T extends NbTrackableFsm<T, S, E, C>, S, E, C>
        extends NbTrackableFsm<T, S, E, C> {

    protected final String flowId;

    protected FlowResources newPrimaryResources;
    protected FlowResources newProtectedResources;
    protected PathId newPrimaryForwardPath;
    protected PathId newPrimaryReversePath;
    protected PathId newProtectedForwardPath;
    protected PathId newProtectedReversePath;
    protected boolean backUpPrimaryPathComputationWayUsed;
    protected boolean backUpProtectedPathComputationWayUsed;

    protected final Collection<FlowResources> oldResources = new ArrayList<>();
    protected PathId oldPrimaryForwardPath;
    protected FlowPathStatus oldPrimaryForwardPathStatus;
    protected PathId oldPrimaryReversePath;
    protected FlowPathStatus oldPrimaryReversePathStatus;
    protected PathId oldProtectedForwardPath;
    protected FlowPathStatus oldProtectedForwardPathStatus;
    protected PathId oldProtectedReversePath;
    protected FlowPathStatus oldProtectedReversePathStatus;

    protected final Collection<PathId> rejectedPaths = new ArrayList<>();
    protected final Collection<FlowResources> rejectedResources = new ArrayList<>();

    protected final Map<UUID, SwitchId> pendingCommands = new HashMap<>();
    protected final Map<UUID, Integer> retriedCommands = new HashMap<>();
    protected final Map<UUID, FlowErrorResponse> failedCommands = new HashMap<>();
    protected final Map<UUID, SpeakerFlowSegmentResponse> failedValidationResponses = new HashMap<>();

    protected final Map<UUID, FlowSegmentRequestFactory> ingressCommands = new HashMap<>();
    protected final Map<UUID, FlowSegmentRequestFactory> nonIngressCommands = new HashMap<>();
    protected final Map<UUID, FlowSegmentRequestFactory> removeCommands = new HashMap<>();

    protected String errorReason;
    protected boolean periodicPingsEnabled;

    public FlowPathSwappingFsm(CommandContext commandContext, String flowId) {
        super(commandContext);
        this.flowId = flowId;
    }

    public FlowSegmentRequestFactory getInstallCommand(UUID commandId) {
        FlowSegmentRequestFactory requestFactory = nonIngressCommands.get(commandId);
        if (requestFactory == null) {
            requestFactory = ingressCommands.get(commandId);
        }
        return requestFactory;
    }

    public abstract void fireNoPathFound(String errorReason);

    public void clearPendingCommands() {
        pendingCommands.clear();
    }

    public Optional<SwitchId> getPendingCommand(UUID key) {
        return Optional.ofNullable(pendingCommands.get(key));
    }

    public void addPendingCommand(UUID key, SwitchId switchId) {
        pendingCommands.put(key, switchId);
    }

    public Optional<SwitchId> removePendingCommand(UUID key) {
        return Optional.ofNullable(pendingCommands.remove(key));
    }

    public void clearRetriedCommands() {
        retriedCommands.clear();
    }

    public int doRetryForCommand(UUID key) {
        int attempt = retriedCommands.getOrDefault(key, 0) + 1;
        retriedCommands.put(key, attempt);
        return attempt;
    }

    public void clearFailedCommands() {
        failedCommands.clear();
    }

    public void addFailedCommand(UUID key, FlowErrorResponse errorResponse) {
        failedCommands.put(key, errorResponse);
    }

    public void clearPendingAndRetriedAndFailedCommands() {
        clearPendingCommands();
        clearRetriedCommands();
        clearFailedCommands();
    }
}
