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
 * Represents a transit vlan allocated for a flow path.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class TransitVlan implements EncapsulationId, CompositeDataEntity<TransitVlan.TransitVlanData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private TransitVlanData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private TransitVlan() {
        data = new TransitVlanDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public TransitVlan(@NonNull TransitVlan entityToClone) {
        data = TransitVlanCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public TransitVlan(@NonNull String flowId, @NonNull PathId pathId, int vlan) {
        data = TransitVlanDataImpl.builder().flowId(flowId).pathId(pathId).vlan(vlan).build();
    }

    public TransitVlan(@NonNull TransitVlanData data) {
        this.data = data;
    }

    /**
     * Defines persistable data of the TransitVlan.
     */
    public interface TransitVlanData {
        String getFlowId();

        void setFlowId(String flowId);

        PathId getPathId();

        void setPathId(PathId pathId);

        int getVlan();

        void setVlan(int vlan);
    }

    @Override
    public int getEncapsulationId() {
        return getVlan();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransitVlan that = (TransitVlan) o;
        return new EqualsBuilder()
                .append(getVlan(), that.getVlan())
                .append(getFlowId(), that.getFlowId())
                .append(getPathId(), that.getPathId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowId(), getPathId(), getVlan());
    }

    /**
     * POJO implementation of TransitVlanData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class TransitVlanDataImpl implements TransitVlanData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull String flowId;
        @NonNull PathId pathId;
        int vlan;
    }

    /**
     * A cloner for TransitVlan entity.
     */
    @Mapper
    public interface TransitVlanCloner {
        TransitVlanCloner INSTANCE = Mappers.getMapper(TransitVlanCloner.class);

        void copy(TransitVlanData source, @MappingTarget TransitVlanData target);

        /**
         * Performs deep copy of entity data.
         */
        default TransitVlanData deepCopy(TransitVlanData source) {
            TransitVlanData result = new TransitVlanDataImpl();
            copy(source, result);
            return result;
        }
    }
}
