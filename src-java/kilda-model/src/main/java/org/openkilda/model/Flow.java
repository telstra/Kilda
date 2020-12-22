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

package org.openkilda.model;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;

import org.openkilda.model.FlowPath.FlowPathData;
import org.openkilda.model.FlowPath.FlowPathDataImpl;
import org.openkilda.model.cookie.FlowSegmentCookie;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.serializers.BeanSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Delegate;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a bi-directional flow. This includes the source and destination, flow status,
 * bandwidth and description, associated paths, encapsulation type.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class Flow implements CompositeDataEntity<Flow.FlowData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private FlowData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private Flow() {
        data = new FlowDataImpl();
        // The reference is used to link flow paths back to the flow. See {@link FlowDataImpl#addPaths(FlowPath...)}.
        ((FlowDataImpl) data).flow = this;
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the flow entity to copy entity data from.
     */
    public Flow(@NonNull Flow entityToClone) {
        this();
        FlowCloner.INSTANCE.copy(entityToClone.getData(), data, this);
    }

    @Builder
    public Flow(@NonNull String flowId, @NonNull Switch srcSwitch, @NonNull Switch destSwitch,
                int srcPort, int srcVlan, int srcInnerVlan, int destPort, int destVlan, int destInnerVlan,
                boolean allocateProtectedPath, String groupId, long bandwidth, boolean ignoreBandwidth,
                String description, boolean periodicPings, FlowEncapsulationType encapsulationType,
                FlowStatus status, String statusInfo, Long maxLatency, Long maxLatencyTier2, Integer priority,
                boolean pinned, DetectConnectedDevices detectConnectedDevices,
                PathComputationStrategy pathComputationStrategy,
                PathComputationStrategy targetPathComputationStrategy, SwitchId loopSwitchId) {
        FlowDataImpl.FlowDataImplBuilder builder = FlowDataImpl.builder()
                .flowId(flowId).srcSwitch(srcSwitch).destSwitch(destSwitch)
                .srcPort(srcPort).srcVlan(srcVlan).srcInnerVlan(srcInnerVlan)
                .destPort(destPort).destVlan(destVlan).destInnerVlan(destInnerVlan)
                .allocateProtectedPath(allocateProtectedPath).groupId(groupId)
                .bandwidth(bandwidth).ignoreBandwidth(ignoreBandwidth)
                .description(description).periodicPings(periodicPings).encapsulationType(encapsulationType)
                .status(status).statusInfo(statusInfo).maxLatency(maxLatency).maxLatencyTier2(maxLatencyTier2)
                .priority(priority).pinned(pinned).pathComputationStrategy(pathComputationStrategy)
                .targetPathComputationStrategy(targetPathComputationStrategy)
                .loopSwitchId(loopSwitchId);
        if (detectConnectedDevices != null) {
            builder.detectConnectedDevices(detectConnectedDevices);
        }
        data = builder.build();

        // The reference is used to link flow paths back to the flow. See {@link FlowDataImpl#addPaths(FlowPath...)}.
        ((FlowDataImpl) data).flow = this;
    }

    public Flow(@NonNull FlowData data) {
        this.data = data;
    }

    /**
     * Checks whether the flow is through a single switch.
     *
     * @return true if source and destination switches are the same, otherwise false
     */
    public boolean isOneSwitchFlow() {
        return getSrcSwitchId().equals(getDestSwitchId());
    }

    public boolean isActive() {
        return getStatus() == FlowStatus.UP;
    }

    /**
     * Get the forward path.
     */
    public FlowPath getForwardPath() {
        if (getForwardPathId() == null) {
            return null;
        }

        return getPath(getForwardPathId()).orElse(null);
    }

    /**
     * Add a path and set it as the forward path.
     */
    public void setForwardPath(FlowPath forwardPath) {
        if (forwardPath != null) {
            if (!hasPath(forwardPath)) {
                addPaths(validateForwardPath(forwardPath));
            }
            setForwardPathId(forwardPath.getPathId());
        } else {
            setForwardPathId(null);
        }
    }

    /**
     * Get the protected forward path.
     */
    public final FlowPath getProtectedForwardPath() {
        if (getProtectedForwardPathId() == null) {
            return null;
        }

        return getPath(getProtectedForwardPathId()).orElse(null);
    }

    /**
     * Add a path and set it as the protected forward path.
     */
    public void setProtectedForwardPath(FlowPath forwardPath) {
        if (forwardPath != null) {
            if (!hasPath(forwardPath)) {
                addPaths(validateForwardPath(forwardPath));
            }
            setProtectedForwardPathId(forwardPath.getPathId());
        } else {
            setProtectedForwardPathId(null);
        }
    }

    /**
     * Get the reverse path.
     */
    public FlowPath getReversePath() {
        if (getReversePathId() == null) {
            return null;
        }

        return getPath(getReversePathId()).orElse(null);
    }


    /**
     * Add a path and set it as the reverse path.
     */
    public void setReversePath(FlowPath reversePath) {
        if (reversePath != null) {
            if (!hasPath(reversePath)) {
                addPaths(validateReversePath(reversePath));
            }
            setReversePathId(reversePath.getPathId());
        } else {
            setReversePathId(null);
        }
    }

    /**
     * Get the protected reverse path.
     */
    public FlowPath getProtectedReversePath() {
        if (getProtectedReversePathId() == null) {
            return null;
        }

        return getPath(getProtectedReversePathId()).orElse(null);
    }

    /**
     * Add a path and set it as the protected reverse.
     */
    public void setProtectedReversePath(FlowPath reversePath) {
        if (reversePath != null) {
            if (!hasPath(reversePath)) {
                addPaths(validateReversePath(reversePath));
            }
            setProtectedReversePathId(reversePath.getPathId());
        } else {
            setProtectedReversePathId(null);
        }
    }

    /**
     * Sets null to all flow paths.
     */
    public void resetPaths() {
        setForwardPathId(null);
        setReversePathId(null);
        setProtectedForwardPathId(null);
        setProtectedReversePathId(null);
    }

    /**
     * Return opposite pathId to passed pathId.
     */
    public Optional<PathId> getOppositePathId(@NonNull PathId pathId) {
        if (pathId.equals(getForwardPathId()) && getReversePathId() != null) {
            return Optional.of(getReversePathId());
        } else if (pathId.equals(getReversePathId()) && getForwardPathId() != null) {
            return Optional.of(getForwardPathId());
        } else if (pathId.equals(getProtectedForwardPathId()) && getProtectedReversePathId() != null) {
            return Optional.of(getProtectedReversePathId());
        } else if (pathId.equals(getProtectedReversePathId()) && getProtectedForwardPathId() != null) {
            return Optional.of(getProtectedForwardPathId());
        } else {
            // Handling the case of non-active paths.
            Optional<Long> requestedPathCookie = getPath(pathId)
                    .map(FlowPath::getCookie)
                    .map(FlowSegmentCookie::getFlowEffectiveId);
            if (requestedPathCookie.isPresent()) {
                return getPaths().stream()
                        .filter(path -> !path.getPathId().equals(pathId))
                        .filter(path -> path.getCookie().getFlowEffectiveId() == requestedPathCookie.get())
                        .findAny()
                        .map(FlowPath::getPathId);
            } else {
                throw new IllegalArgumentException(format("Flow %s does not have path %s", getFlowId(), pathId));
            }
        }
    }

    /**
     * Return main flow prioritized paths status.
     */
    public FlowPathStatus getMainFlowPrioritizedPathsStatus() {
        return getFlowPrioritizedPathStatus(getForwardPath(), getReversePath());
    }

    /**
     * Return protected flow prioritized paths status.
     */
    public FlowPathStatus getProtectedFlowPrioritizedPathsStatus() {
        FlowPathStatus pathStatus = getFlowPrioritizedPathStatus(getProtectedForwardPath(), getProtectedReversePath());
        return isAllocateProtectedPath() && pathStatus == null ? FlowPathStatus.INACTIVE : pathStatus;
    }

    private FlowPathStatus getFlowPrioritizedPathStatus(FlowPath... flowPaths) {
        return Stream.of(flowPaths)
                .filter(Objects::nonNull)
                .map(FlowPath::getStatus)
                .max(FlowPathStatus::compareTo)
                .orElse(null);
    }

    /**
     * Check whether the path corresponds to the forward flow.
     */
    public boolean isForward(FlowPath path) {
        return Objects.equals(path.getSrcSwitchId(), getSrcSwitchId())
                && Objects.equals(path.getDestSwitchId(), getDestSwitchId())
                && (!isOneSwitchFlow() || path.getCookie() != null
                && path.getCookie().getDirection() == FlowPathDirection.FORWARD);
    }

    /**
     * Check whether the path corresponds to the reverse flow.
     */
    public boolean isReverse(FlowPath path) {
        return Objects.equals(path.getSrcSwitchId(), getDestSwitchId())
                && Objects.equals(path.getDestSwitchId(), getSrcSwitchId())
                && (!isOneSwitchFlow() || path.getCookie() != null
                && path.getCookie().getDirection() == FlowPathDirection.REVERSE);
    }

    private FlowPath validateForwardPath(FlowPath path) {
        checkArgument(isForward(path),
                "Forward path %s and the flow have different endpoints, but expected the same.",
                path.getPathId());

        return path;
    }

    private FlowPath validateReversePath(FlowPath path) {
        checkArgument(isReverse(path),
                "Reverse path %s and the flow have different endpoints, but expected the same.",
                path.getPathId());

        return path;
    }

    /**
     * Calculate the combined flow status based on the status of primary and protected paths.
     */
    public FlowStatus computeFlowStatus() {
        FlowPathStatus mainFlowPrioritizedPathsStatus = getMainFlowPrioritizedPathsStatus();
        FlowPathStatus protectedFlowPrioritizedPathsStatus = getProtectedFlowPrioritizedPathsStatus();

        // Calculate the combined flow status.
        if (protectedFlowPrioritizedPathsStatus != null
                && protectedFlowPrioritizedPathsStatus != FlowPathStatus.ACTIVE
                && mainFlowPrioritizedPathsStatus == FlowPathStatus.ACTIVE) {
            return FlowStatus.DEGRADED;
        } else {
            if (mainFlowPrioritizedPathsStatus == null) {
                // No main path
                return FlowStatus.DOWN;
            }

            switch (mainFlowPrioritizedPathsStatus) {
                case ACTIVE:
                    return FlowStatus.UP;
                case INACTIVE:
                    return FlowStatus.DOWN;
                case IN_PROGRESS:
                    return FlowStatus.IN_PROGRESS;
                case DEGRADED:
                    return FlowStatus.DEGRADED;
                default:
                    throw new IllegalArgumentException(
                            format("Unsupported flow path status %s", mainFlowPrioritizedPathsStatus));
            }
        }
    }

    /**
     * Checks if pathId belongs to the current flow.
     */
    public boolean isActualPathId(PathId pathId) {
        return pathId != null && (pathId.equals(getForwardPathId()) || pathId.equals(getReversePathId())
                || pathId.equals(getProtectedForwardPathId()) || pathId.equals(getProtectedReversePathId()));
    }

    /**
     * Checks if flow looped.
     * @return true if flow is looped.
     */
    public boolean isLooped() {
        return getLoopSwitchId() != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Flow that = (Flow) o;
        return new EqualsBuilder()
                .append(getSrcPort(), that.getSrcPort())
                .append(getSrcVlan(), that.getSrcVlan())
                .append(getSrcInnerVlan(), that.getSrcInnerVlan())
                .append(getDestPort(), that.getDestPort())
                .append(getDestVlan(), that.getDestVlan())
                .append(getDestInnerVlan(), that.getDestInnerVlan())
                .append(isAllocateProtectedPath(), that.isAllocateProtectedPath())
                .append(getBandwidth(), that.getBandwidth())
                .append(isIgnoreBandwidth(), that.isIgnoreBandwidth())
                .append(isPeriodicPings(), that.isPeriodicPings())
                .append(isPinned(), that.isPinned())
                .append(getFlowId(), that.getFlowId())
                .append(getSrcSwitchId(), that.getSrcSwitchId())
                .append(getDestSwitchId(), that.getDestSwitchId())
                .append(getForwardPathId(), that.getForwardPathId())
                .append(getReversePathId(), that.getReversePathId())
                .append(getProtectedForwardPathId(), that.getProtectedForwardPathId())
                .append(getProtectedReversePathId(), that.getProtectedReversePathId())
                .append(getGroupId(), that.getGroupId())
                .append(getDescription(), that.getDescription())
                .append(getEncapsulationType(), that.getEncapsulationType())
                .append(getStatus(), that.getStatus())
                .append(getStatusInfo(), that.getStatusInfo())
                .append(getMaxLatency(), that.getMaxLatency())
                .append(getMaxLatencyTier2(), that.getMaxLatencyTier2())
                .append(getPriority(), that.getPriority())
                .append(getTimeCreate(), that.getTimeCreate())
                .append(getTimeModify(), that.getTimeModify())
                .append(getDetectConnectedDevices(), that.getDetectConnectedDevices())
                .append(getPathComputationStrategy(), that.getPathComputationStrategy())
                .append(getPaths(), that.getPaths())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowId(), getSrcSwitchId(), getDestSwitchId(), getSrcPort(), getSrcVlan(),
                getSrcInnerVlan(), getDestPort(), getDestVlan(), getDestInnerVlan(),
                getForwardPathId(), getReversePathId(),
                isAllocateProtectedPath(), getProtectedForwardPathId(), getProtectedReversePathId(),
                getGroupId(), getBandwidth(), isIgnoreBandwidth(), getDescription(), isPeriodicPings(),
                getEncapsulationType(), getStatus(), getStatusInfo(), getMaxLatency(), getPriority(), getTimeCreate(),
                getTimeModify(), isPinned(), getDetectConnectedDevices(), getPathComputationStrategy(), getPaths());
    }

    /**
     * Defines persistable data of the Flow.
     */
    public interface FlowData {
        String getFlowId();

        void setFlowId(String flowId);

        SwitchId getSrcSwitchId();

        Switch getSrcSwitch();

        void setSrcSwitch(Switch srcSwitch);

        SwitchId getDestSwitchId();

        Switch getDestSwitch();

        void setDestSwitch(Switch destSwitch);

        int getSrcPort();

        void setSrcPort(int srcPort);

        int getSrcVlan();

        void setSrcVlan(int srcVlan);

        int getSrcInnerVlan();

        void setSrcInnerVlan(int srcInnerVlan);

        int getDestPort();

        void setDestPort(int destPort);

        int getDestVlan();

        void setDestVlan(int destVlan);

        int getDestInnerVlan();

        void setDestInnerVlan(int destVlan);

        PathId getForwardPathId();

        void setForwardPathId(PathId forwardPathId);

        PathId getReversePathId();

        void setReversePathId(PathId reversePathId);

        Collection<FlowPath> getPaths();

        Set<PathId> getPathIds();

        Optional<FlowPath> getPath(PathId pathId);

        boolean hasPath(FlowPath path);

        void addPaths(FlowPath... paths);

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

        String getStatusInfo();

        void setStatusInfo(String statusInfo);

        Long getMaxLatency();

        void setMaxLatency(Long maxLatency);

        Long getMaxLatencyTier2();

        void setMaxLatencyTier2(Long maxLatencyTier2);

        Integer getPriority();

        void setPriority(Integer priority);

        Instant getTimeCreate();

        void setTimeCreate(Instant timeCreate);

        Instant getTimeModify();

        void setTimeModify(Instant timeModify);

        boolean isPinned();

        void setPinned(boolean pinned);

        DetectConnectedDevices getDetectConnectedDevices();

        void setDetectConnectedDevices(DetectConnectedDevices detectConnectedDevices);

        PathComputationStrategy getPathComputationStrategy();

        void setPathComputationStrategy(PathComputationStrategy pathComputationStrategy);

        PathComputationStrategy getTargetPathComputationStrategy();

        void setTargetPathComputationStrategy(PathComputationStrategy pathComputationStrategy);

        SwitchId getLoopSwitchId();

        void setLoopSwitchId(SwitchId loopSwitchId);
    }

    /**
     * POJO implementation of FlowData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class FlowDataImpl implements FlowData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull String flowId;
        @NonNull Switch srcSwitch;
        @NonNull Switch destSwitch;
        int srcPort;
        int srcVlan;
        int srcInnerVlan;
        int destPort;
        int destVlan;
        int destInnerVlan;
        PathId forwardPathId;
        PathId reversePathId;
        boolean allocateProtectedPath;
        PathId protectedForwardPathId;
        PathId protectedReversePathId;
        String groupId;
        long bandwidth;
        boolean ignoreBandwidth;
        String description;
        boolean periodicPings;
        FlowEncapsulationType encapsulationType;
        FlowStatus status;
        String statusInfo;
        Long maxLatency;
        Long maxLatencyTier2;
        Integer priority;
        Instant timeCreate;
        Instant timeModify;
        boolean pinned;
        @Builder.Default
        @NonNull DetectConnectedDevices detectConnectedDevices = DetectConnectedDevices.builder().build();
        PathComputationStrategy pathComputationStrategy;
        PathComputationStrategy targetPathComputationStrategy;
        SwitchId loopSwitchId;
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        final Set<FlowPath> paths = new HashSet<>();
        // The reference is used to link flow paths back to the flow. See {@link FlowDataImpl#addPaths(FlowPath...)}.
        @Setter(AccessLevel.NONE)
        @Getter(AccessLevel.NONE)
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        Flow flow;

        @Override
        public SwitchId getSrcSwitchId() {
            return srcSwitch.getSwitchId();
        }

        @Override
        public SwitchId getDestSwitchId() {
            return destSwitch.getSwitchId();
        }

        @Override
        public Set<PathId> getPathIds() {
            return paths.stream().map(FlowPath::getPathId).collect(Collectors.toSet());
        }

        @Override
        public boolean hasPath(FlowPath path) {
            return paths.contains(path);
        }

        /**
         * Add and associate flow path(s) with the flow.
         */
        @Override
        public final void addPaths(FlowPath... paths) {
            for (FlowPath pathToAdd : paths) {
                boolean toBeAdded = true;
                Iterator<FlowPath> it = this.paths.iterator();
                while (it.hasNext()) {
                    FlowPath each = it.next();
                    if (pathToAdd == each) {
                        toBeAdded = false;
                        break;
                    }
                    if (pathToAdd.getPathId().equals(each.getPathId())) {
                        it.remove();
                        // Quit as no duplicates expected.
                        break;
                    }
                }
                if (toBeAdded) {
                    this.paths.add(pathToAdd);
                    FlowPathData data = pathToAdd.getData();
                    if (data instanceof FlowPathDataImpl) {
                        ((FlowPathDataImpl) data).flow = flow;
                    }
                }
            }
        }

        /**
         * Get an associated path by id.
         */
        @Override
        public Optional<FlowPath> getPath(PathId pathId) {
            return paths.stream()
                    .filter(path -> path.getPathId().equals(pathId))
                    .findAny();
        }

        /**
         * Return detect connected devices flags.
         */
        @Override
        public void setDetectConnectedDevices(DetectConnectedDevices detectConnectedDevices) {
            if (detectConnectedDevices == null) {
                this.detectConnectedDevices = DetectConnectedDevices.builder().build();
            } else {
                this.detectConnectedDevices = detectConnectedDevices;
            }
        }
    }

    /**
     * A cloner for Flow entity.
     */
    @Mapper
    public interface FlowCloner {
        FlowCloner INSTANCE = Mappers.getMapper(FlowCloner.class);

        /**
         * Performs deep copy of entity data.
         */
        default void copy(FlowData source, FlowData target, Flow targetFlow) {
            copyWithoutSwitchesAndPaths(source, target);
            target.setSrcSwitch(new Switch(source.getSrcSwitch()));
            target.setDestSwitch(new Switch(source.getDestSwitch()));
            target.addPaths(source.getPaths().stream()
                    .map(path -> new FlowPath(path, targetFlow))
                    .toArray(FlowPath[]::new));
        }

        @Mapping(target = "paths", ignore = true)
        void copyWithoutPaths(FlowData source, @MappingTarget FlowData target);

        /**
         * Performs deep copy of entity data.
         */
        default FlowData copyWithoutPaths(FlowData source, Flow targetFlow) {
            FlowDataImpl result = new FlowDataImpl();
            // The reference is used to link flow paths back to the flow.See {@link FlowDataImpl#addPaths(FlowPath...)}.
            result.flow = targetFlow;
            copyWithoutSwitchesAndPaths(source, result);
            result.setSrcSwitch(new Switch(source.getSrcSwitch()));
            result.setDestSwitch(new Switch(source.getDestSwitch()));
            return result;
        }

        @Mapping(target = "srcSwitch", ignore = true)
        @Mapping(target = "destSwitch", ignore = true)
        @Mapping(target = "paths", ignore = true)
        void copyWithoutSwitchesAndPaths(FlowData source, @MappingTarget FlowData target);
    }
}
