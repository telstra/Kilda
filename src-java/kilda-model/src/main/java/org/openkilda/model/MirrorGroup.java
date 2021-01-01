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

package org.openkilda.model;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.serializers.BeanSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Delegate;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a group allocated for a flow.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class MirrorGroup implements CompositeDataEntity<MirrorGroup.MirrorGroupData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private MirrorGroupData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private MirrorGroup() {
        data = new MirrorGroupDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public MirrorGroup(@NonNull MirrorGroup entityToClone) {
        data = MirrorGroupCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public MirrorGroup(@NonNull SwitchId switchId, @NonNull GroupId groupId,
                       @NonNull String flowId, @NonNull PathId pathId,
                       @NonNull MirrorGroupType mirrorGroupType,
                       @NonNull MirrorDirection mirrorDirection) {
        data = MirrorGroupDataImpl.builder()
                .switchId(switchId).groupId(groupId).flowId(flowId)
                .pathId(pathId).mirrorGroupType(mirrorGroupType).mirrorDirection(mirrorDirection).build();
    }

    public MirrorGroup(@NonNull MirrorGroupData data) {
        this.data = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MirrorGroup that = (MirrorGroup) o;
        return new EqualsBuilder()
                .append(getSwitchId(), that.getSwitchId())
                .append(getGroupId(), that.getGroupId())
                .append(getFlowId(), that.getFlowId())
                .append(getMirrorGroupType(), that.getMirrorGroupType())
                .append(getMirrorDirection(), that.getMirrorDirection())
                .append(getPathId(), that.getPathId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSwitchId(), getGroupId(), getFlowId(), getMirrorGroupType(),
                getMirrorDirection(), getPathId());
    }

    /**
     * Defines persistable data of the MirrorGroup.
     */
    public interface MirrorGroupData {
        SwitchId getSwitchId();

        void setSwitchId(SwitchId switchId);

        GroupId getGroupId();

        void setGroupId(GroupId groupId);

        String getFlowId();

        void setFlowId(String flowId);

        MirrorGroupType getMirrorGroupType();

        void setMirrorGroupType(MirrorGroupType mirrorGroupType);

        MirrorDirection getMirrorDirection();

        void setMirrorDirection(MirrorDirection mirrorDirection);

        PathId getPathId();

        void setPathId(PathId pathId);
    }

    /**
     * POJO implementation of MirrorGroupData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class MirrorGroupDataImpl implements MirrorGroupData, Serializable {
        private static final long serialVersionUID = 1L;

        @NonNull
        SwitchId switchId;
        @NonNull
        GroupId groupId;
        @NonNull
        String flowId;
        @NonNull
        MirrorGroupType mirrorGroupType;
        @NonNull
        MirrorDirection mirrorDirection;
        @NonNull
        PathId pathId;
    }

    /**
     * A cloner for MirrorGroup entity.
     */
    @Mapper
    public interface MirrorGroupCloner {
        MirrorGroupCloner INSTANCE = Mappers.getMapper(MirrorGroupCloner.class);

        void copy(MirrorGroupData source, @MappingTarget MirrorGroupData target);

        /**
         * Performs deep copy of entity data.
         */
        default MirrorGroupData deepCopy(MirrorGroupData source) {
            MirrorGroupData result = new MirrorGroupDataImpl();
            copy(source, result);
            return result;
        }
    }
}
