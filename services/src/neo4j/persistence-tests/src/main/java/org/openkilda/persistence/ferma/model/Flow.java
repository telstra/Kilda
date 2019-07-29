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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public interface Flow {
    String getFlowId();

    void setFlowId(String flowId);

    int getSrcPort();

    void setSrcPort(int srcPort);

    int getSrcVlan();

    void setSrcVlan(int srcVlan);

    int getDestPort();

    void setDestPort(int destPort);

    int getDestVlan();

    void setDestVlan(int destVlan);

    PathId getForwardPathId();

    void setForwardPathId(PathId forwardPathId);

    PathId getReversePathId();

    void setReversePathId(PathId reversePathId);

    boolean isAllocateProtectedPath();

    void setAllocateProtectedPath(boolean allocateProtectedPath);

    PathId getProtectedForwardPathId();

    void setProtectedForwardPathId(PathId protectedForwardPathId);

    PathId getProtectedReversePathId();

    void setProtectedReversePathId(PathId protectedReversePathId);

    String getGroupId();

    void setGroupId(String groupId);

    long getBandwidth();

    void setBandwidth(long bandwidth);

    boolean isIgnoreBandwidth();

    void setIgnoreBandwidth(boolean ignoreBandwidth);

    String getDescription();

    void setDescription(String description);

    boolean isPeriodicPings();

    void setPeriodicPings(boolean periodicPings);

    FlowEncapsulationType getEncapsulationType();

    void setEncapsulationType(FlowEncapsulationType encapsulationType);

    FlowStatus getStatus();

    void setStatus(FlowStatus status);

    Integer getMaxLatency();

    void setMaxLatency(Integer maxLatency);

    Integer getPriority();

    void setPriority(Integer priority);

    Instant getTimeCreate();

    void setTimeCreate(Instant timeCreate);

    Instant getTimeModify();

    void setTimeModify(Instant timeModify);

    boolean isPinned();

    void setPinned(boolean pinned);

    Switch getSrcSwitch();

    SwitchId getSrcSwitchId();

    void setSrcSwitch(Switch srcSwitch);

    Switch getDestSwitch();

    SwitchId getDestSwitchId();

    void setDestSwitch(Switch destSwitch);

    Set<FlowPath> getPaths();

    void setPaths(Set<FlowPath> paths);

    Set<PathId> getPathIds();

    Optional<FlowPath> getPath(PathId pathId);

    /**
     * Return opposite pathId to passed pathId.
     */
    PathId getOppositePathId(PathId pathId);

    void addPaths(FlowPath... paths);

    void removePaths(PathId... pathIds);

    /**
     * Add a path and set it as the forward.
     */
    default void setForwardPath(FlowPath forwardPath) {
        if (forwardPath != null) {
            addPaths(validateForwardPath(forwardPath));
            setForwardPathId(forwardPath.getPathId());
        } else {
            removePaths(getForwardPathId());
            setForwardPathId(null);
        }
    }

    /**
     * Get the forward path.
     */
    default FlowPath getForwardPath() {
        if (getForwardPathId() == null) {
            return null;
        }

        return getPath(getForwardPathId()).orElse(null);
    }

    /**
     * Add a path and set it as the protected forward.
     */
    default void setProtectedForwardPath(FlowPath forwardPath) {
        if (forwardPath != null) {
            addPaths(validateForwardPath(forwardPath));
            setProtectedForwardPathId(forwardPath.getPathId());
        } else {
            removePaths(getProtectedForwardPathId());
            setProtectedForwardPathId(null);
        }
    }

    /**
     * Get the protected forward path.
     */
    default FlowPath getProtectedForwardPath() {
        if (getProtectedForwardPathId() == null) {
            return null;
        }

        return getPath(getProtectedForwardPathId()).orElse(null);
    }

    default FlowPath validateForwardPath(FlowPath path) {
        checkArgument(isForward(path),
                "Forward path %s and the flow have different endpoints, but expected the same.",
                path.getPathId());

        return path;
    }

    /**
     * Add a path and set it as the reverse path.
     */
    default void setReversePath(FlowPath reversePath) {
        if (reversePath != null) {
            addPaths(validateReversePath(reversePath));
            setReversePathId(reversePath.getPathId());
        } else {
            removePaths(getReversePathId());
            setReversePathId(null);
        }
    }

    /**
     * Get the reverse path.
     */
    default FlowPath getReversePath() {
        if (getReversePathId() == null) {
            return null;
        }

        return getPath(getReversePathId()).orElse(null);
    }

    /**
     * Add a path and set it as the protected reverse.
     */
    default void setProtectedReversePath(FlowPath reversePath) {
        if (reversePath != null) {
            addPaths(validateReversePath(reversePath));
            setProtectedReversePathId(reversePath.getPathId());
        } else {
            removePaths(getProtectedReversePathId());
            setProtectedReversePathId(null);
        }
    }

    /**
     * Get the protected reverse path.
     */
    default FlowPath getProtectedReversePath() {
        if (getProtectedReversePathId() == null) {
            return null;
        }

        return getPath(getProtectedReversePathId()).orElse(null);
    }

    default FlowPath validateReversePath(FlowPath path) {
        checkArgument(isReverse(path),
                "Reverse path %s and the flow have different endpoints, but expected the same.",
                path.getPathId());

        return path;
    }

    /**
     * Checks whether the flow is through a single switch.
     *
     * @return true if source and destination switches are the same, otherwise false
     */
    default boolean isOneSwitchFlow() {
        return getSrcSwitchId().equals(getDestSwitchId());
    }


    default boolean isActive() {
        return getStatus() == FlowStatus.UP;
    }

    /**
     * Check whether the path corresponds to the forward flow.
     */
    default boolean isForward(FlowPath path) {
        return Objects.equals(path.getFlow().getFlowId(), this.getFlowId())
                && Objects.equals(path.getSrcSwitch().getSwitchId(), getSrcSwitch().getSwitchId())
                && Objects.equals(path.getDestSwitch().getSwitchId(), getDestSwitch().getSwitchId())
                && (!isOneSwitchFlow() || path.getCookie() != null && path.getCookie().isMaskedAsForward());
    }

    /**
     * Check whether the path corresponds to the reverse flow.
     */
    default boolean isReverse(FlowPath path) {
        return Objects.equals(path.getFlow().getFlowId(), this.getFlowId())
                && Objects.equals(path.getSrcSwitch().getSwitchId(), getDestSwitch().getSwitchId())
                && Objects.equals(path.getDestSwitch().getSwitchId(), getSrcSwitch().getSwitchId())
                && (!isOneSwitchFlow() || path.getCookie() != null && path.getCookie().isMaskedAsReversed());
    }

    /**
     * Return main flow prioritized paths status.
     */
    default FlowPathStatus getMainFlowPrioritizedPathsStatus() {
        return getFlowPrioritizedPathStatus(getForwardPath(), getReversePath());
    }

    /**
     * Return protected flow prioritized paths status.
     */
    default FlowPathStatus getProtectedFlowPrioritizedPathsStatus() {
        return getFlowPrioritizedPathStatus(getProtectedForwardPath(), getProtectedReversePath());
    }

    default FlowPathStatus getFlowPrioritizedPathStatus(FlowPath... flowPaths) {
        return Stream.of(flowPaths)
                .filter(Objects::nonNull)
                .map(FlowPath::getStatus)
                .max(FlowPathStatus::compareTo)
                .orElse(null);
    }

    /**
     * Calculate the combined flow status based on the status of primary and protected paths.
     */
    default FlowStatus computeFlowStatus() {
        FlowPathStatus mainFlowPrioritizedPathsStatus = getMainFlowPrioritizedPathsStatus();
        FlowPathStatus protectedFlowPrioritizedPathsStatus = getProtectedFlowPrioritizedPathsStatus();

        // Calculate the combined flow status.
        if (protectedFlowPrioritizedPathsStatus != null
                && protectedFlowPrioritizedPathsStatus != FlowPathStatus.ACTIVE
                && mainFlowPrioritizedPathsStatus == FlowPathStatus.ACTIVE) {
            return FlowStatus.DEGRADED;
        } else {
            switch (mainFlowPrioritizedPathsStatus) {
                case ACTIVE:
                    return FlowStatus.UP;
                case INACTIVE:
                    return FlowStatus.DOWN;
                case IN_PROGRESS:
                    return FlowStatus.IN_PROGRESS;
                default:
                    throw new IllegalArgumentException(
                            format("Unsupported flow path status %s", mainFlowPrioritizedPathsStatus));
            }
        }
    }
}
