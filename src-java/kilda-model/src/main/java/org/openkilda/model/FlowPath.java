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

import static java.lang.String.format;

import org.openkilda.model.PathSegment.PathSegmentData;
import org.openkilda.model.PathSegment.PathSegmentDataImpl;
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
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a flow path.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class FlowPath implements CompositeDataEntity<FlowPath.FlowPathData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private FlowPathData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private FlowPath() {
        data = new FlowPathDataImpl();
        // The reference is used to link path segments back to the path. See {@link #setSegments(List)}.
        ((FlowPathDataImpl) data).flowPath = this;
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the path entity to copy data from.
     * @param flow the flow to be referred ({@code FlowPath.getFlow()}) by the new path.
     */
    public FlowPath(@NonNull FlowPath entityToClone, Flow flow) {
        this();
        ((FlowPathDataImpl) data).flow = flow;
        FlowPathCloner.INSTANCE.copy(entityToClone.getData(), data, this);
    }

    @Builder
    public FlowPath(@NonNull PathId pathId, @NonNull Switch srcSwitch, @NonNull Switch destSwitch,
                    FlowSegmentCookie cookie, MeterId meterId, GroupId ingressMirrorGroupId,
                    long latency, long bandwidth,
                    boolean ignoreBandwidth, FlowPathStatus status, List<PathSegment> segments,
                    Set<FlowApplication> applications, boolean srcWithMultiTable, boolean destWithMultiTable) {
        data = FlowPathDataImpl.builder().pathId(pathId).srcSwitch(srcSwitch).destSwitch(destSwitch)
                .cookie(cookie).meterId(meterId).ingressMirrorGroupId(ingressMirrorGroupId)
                .latency(latency).bandwidth(bandwidth)
                .ignoreBandwidth(ignoreBandwidth).status(status)
                .applications(applications).srcWithMultiTable(srcWithMultiTable).destWithMultiTable(destWithMultiTable)
                .build();
        // The reference is used to link path segments back to the path. See {@link #setSegments(List)}.
        ((FlowPathDataImpl) data).flowPath = this;

        if (segments != null && !segments.isEmpty()) {
            data.setSegments(segments);
        }
    }

    public FlowPath(@NonNull FlowPathData data) {
        this.data = data;
    }

    /**
     * Sets the current flow path status corresponds with passed {@link FlowStatus} .
     */
    public void setStatusLikeFlow(FlowStatus flowStatus) {
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
    public boolean isOneSwitchFlow() {
        return getSrcSwitchId().equals(getDestSwitchId());
    }

    public boolean isForward() {
        return getCookie().getDirection() == FlowPathDirection.FORWARD;
    }

    /**
     * Check whether the path is protected for the flow.
     */
    public boolean isProtected() {
        Flow flow = getFlow();
        return flow != null && (getPathId().equals(flow.getProtectedForwardPathId())
                || getPathId().equals(flow.getProtectedReversePathId()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FlowPath that = (FlowPath) o;
        return new EqualsBuilder()
                .append(getLatency(), that.getLatency())
                .append(getBandwidth(), that.getBandwidth())
                .append(isIgnoreBandwidth(), that.isIgnoreBandwidth())
                .append(getPathId(), that.getPathId())
                .append(getSrcSwitchId(), that.getSrcSwitchId())
                .append(getDestSwitchId(), that.getDestSwitchId())
                .append(getFlowId(), that.getFlowId())
                .append(getCookie(), that.getCookie())
                .append(getMeterId(), that.getMeterId())
                .append(getIngressMirrorGroupId(), that.getIngressMirrorGroupId())
                .append(getTimeCreate(), that.getTimeCreate())
                .append(getTimeModify(), that.getTimeModify())
                .append(getStatus(), that.getStatus())
                .append(getSegments(), that.getSegments())
                .append(getApplications(), that.getApplications())
                .append(isSrcWithMultiTable(), that.isSrcWithMultiTable())
                .append(isDestWithMultiTable(), that.isDestWithMultiTable())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getPathId(), getSrcSwitchId(), getDestSwitchId(), getFlowId(), getCookie(), getMeterId(),
                getLatency(), getBandwidth(), isIgnoreBandwidth(), getTimeCreate(), getTimeModify(), getStatus(),
                getSegments(), getApplications(), isSrcWithMultiTable(), isDestWithMultiTable());
    }

    /**
     * Defines persistable data of the FlowPath.
     */
    public interface FlowPathData {
        PathId getPathId();

        void setPathId(PathId pathId);

        SwitchId getSrcSwitchId();

        Switch getSrcSwitch();

        void setSrcSwitch(Switch srcSwitch);

        SwitchId getDestSwitchId();

        Switch getDestSwitch();

        void setDestSwitch(Switch destSwitch);

        String getFlowId();

        Flow getFlow();

        FlowSegmentCookie getCookie();

        void setCookie(FlowSegmentCookie cookie);

        MeterId getMeterId();

        void setIngressMirrorGroupId(GroupId meterId);

        GroupId getIngressMirrorGroupId();

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

        List<PathSegment> getSegments();

        void setSegments(List<PathSegment> segments);

        Set<FlowApplication> getApplications();

        void setApplications(Set<FlowApplication> applications);

        boolean isSrcWithMultiTable();

        void setSrcWithMultiTable(boolean srcWithMultiTable);

        boolean isDestWithMultiTable();

        void setDestWithMultiTable(boolean destWithMultiTable);

    }

    /**
     * POJO implementation of FlowPathData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class FlowPathDataImpl implements FlowPathData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull PathId pathId;
        @NonNull Switch srcSwitch;
        @NonNull Switch destSwitch;
        @Setter(AccessLevel.NONE)
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        Flow flow;
        FlowSegmentCookie cookie;
        MeterId meterId;
        GroupId ingressMirrorGroupId;
        long latency;
        long bandwidth;
        boolean ignoreBandwidth;
        Instant timeCreate;
        Instant timeModify;
        FlowPathStatus status;
        @Builder.Default
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        @NonNull List<PathSegment> segments = new ArrayList<>();
        Set<FlowApplication> applications;
        // The reference is used to link path segments back to the path. See {@link #setSegments(List)}.
        @Setter(AccessLevel.NONE)
        @Getter(AccessLevel.NONE)
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        FlowPath flowPath;
        boolean srcWithMultiTable;
        boolean destWithMultiTable;

        @Override
        public String getFlowId() {
            return flow != null ? flow.getFlowId() : null;
        }

        @Override
        public SwitchId getSrcSwitchId() {
            return srcSwitch.getSwitchId();
        }

        @Override
        public SwitchId getDestSwitchId() {
            return destSwitch.getSwitchId();
        }

        public void setBandwidth(long bandwidth) {
            this.bandwidth = bandwidth;

            if (segments != null) {
                segments.forEach(segment -> segment.setBandwidth(bandwidth));
            }
        }

        public void setIgnoreBandwidth(boolean ignoreBandwidth) {
            this.ignoreBandwidth = ignoreBandwidth;

            if (segments != null) {
                segments.forEach(segment -> segment.setIgnoreBandwidth(ignoreBandwidth));
            }
        }

        @Override
        public List<PathSegment> getSegments() {
            return Collections.unmodifiableList(segments);
        }

        /**
         * Set the segments.
         */
        @Override
        public void setSegments(List<PathSegment> segments) {
            for (int idx = 0; idx < segments.size(); idx++) {
                PathSegment segment = segments.get(idx);
                segment.setSeqId(idx);
                segment.setIgnoreBandwidth(ignoreBandwidth);
                segment.setBandwidth(bandwidth);
                PathSegmentData data = segment.getData();
                if (data instanceof PathSegmentDataImpl) {
                    ((PathSegmentDataImpl) data).path = flowPath;
                }
            }

            this.segments = new ArrayList<>(segments);
        }
    }

    /**
     * A cloner for FlowPath entity.
     */
    @Mapper(collectionMappingStrategy = CollectionMappingStrategy.TARGET_IMMUTABLE)
    public interface FlowPathCloner {
        FlowPathCloner INSTANCE = Mappers.getMapper(FlowPathCloner.class);

        void copy(FlowPathData source, @MappingTarget FlowPathData target);

        /**
         * Performs deep copy of entity data.
         */
        default FlowPathData copy(FlowPathData source, FlowPath targetPath, Flow targetFlow) {
            FlowPathDataImpl result = new FlowPathDataImpl();
            result.flowPath = targetPath;
            result.flow = targetFlow;
            copy(source, result, targetPath);
            return result;
        }

        /**
         * Performs deep copy of entity data.
         */
        default void copy(FlowPathData source, FlowPathData target, FlowPath targetPath) {
            copyWithoutSwitchesAndSegments(source, target);
            target.setSrcSwitch(new Switch(source.getSrcSwitch()));
            target.setDestSwitch(new Switch(source.getDestSwitch()));
            target.setSegments(source.getSegments().stream()
                    .map(segment -> new PathSegment(segment, targetPath))
                    .collect(Collectors.toList()));
        }

        @Mapping(target = "srcSwitch", ignore = true)
        @Mapping(target = "destSwitch", ignore = true)
        @Mapping(target = "segments", ignore = true)
        void copyWithoutSwitchesAndSegments(FlowPathData source, @MappingTarget FlowPathData target);
    }
}
