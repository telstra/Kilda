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
 * Represents a vxlan allocated for a flow path.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class Vxlan implements EncapsulationId, CompositeDataEntity<Vxlan.VxlanData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private VxlanData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private Vxlan() {
        data = new VxlanDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public Vxlan(@NonNull Vxlan entityToClone) {
        data = VxlanCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public Vxlan(@NonNull String flowId, @NonNull PathId pathId, int vni) {
        data = VxlanDataImpl.builder().flowId(flowId).pathId(pathId).vni(vni).build();
    }

    public Vxlan(@NonNull VxlanData data) {
        this.data = data;
    }

    /**
     * Defines persistable data of the Vxlan.
     */
    public interface VxlanData {
        String getFlowId();

        void setFlowId(String flowId);

        PathId getPathId();

        void setPathId(PathId pathId);

        int getVni();

        void setVni(int vni);
    }

    @Override
    public int getEncapsulationId() {
        return getVni();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Vxlan vxlanData = (Vxlan) o;
        return new EqualsBuilder()
                .append(getVni(), vxlanData.getVni())
                .append(getFlowId(), vxlanData.getFlowId())
                .append(getPathId(), vxlanData.getPathId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowId(), getPathId(), getVni());
    }

    /**
     * POJO implementation of VxlanData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class VxlanDataImpl implements VxlanData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull String flowId;
        @NonNull PathId pathId;
        int vni;
    }

    /**
     * A cloner for Vxlan entity.
     */
    @Mapper
    public interface VxlanCloner {
        VxlanCloner INSTANCE = Mappers.getMapper(VxlanCloner.class);

        void copy(VxlanData source, @MappingTarget VxlanData target);

        /**
         * Performs deep copy of entity data.
         */
        default VxlanData deepCopy(VxlanData source) {
            VxlanData result = new VxlanDataImpl();
            copy(source, result);
            return result;
        }
    }
}
