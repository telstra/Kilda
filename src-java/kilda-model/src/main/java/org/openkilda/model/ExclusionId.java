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
 * Represents an exclusion id allocated for a flow.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class ExclusionId implements CompositeDataEntity<ExclusionId.ExclusionIdData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private ExclusionIdData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private ExclusionId() {
        data = new ExclusionIdDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public ExclusionId(@NonNull ExclusionId entityToClone) {
        data = ExclusionIdCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public ExclusionId(@NonNull String flowId, int recordId) {
        data = ExclusionIdDataImpl.builder()
                .flowId(flowId).recordId(recordId).build();
    }

    public ExclusionId(@NonNull ExclusionIdData data) {
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
        ExclusionId that = (ExclusionId) o;
        return new EqualsBuilder()
                .append(getFlowId(), that.getFlowId())
                .append(getRecordId(), that.getRecordId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowId(), getRecordId());
    }

    /**
     * Defines persistable data of the ExclusionId.
     */
    public interface ExclusionIdData {
        String getFlowId();

        void setFlowId(String flowId);

        int getRecordId();

        void setRecordId(int recordId);
    }

    /**
     * POJO implementation of ExclusionIdData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class ExclusionIdDataImpl implements ExclusionIdData, Serializable {
        private static final long serialVersionUID = 1L;

        @NonNull
        String flowId;
        int recordId;
    }

    /**
     * A cloner for ExclusionId entity.
     */
    @Mapper
    public interface ExclusionIdCloner {
        ExclusionIdCloner INSTANCE = Mappers.getMapper(ExclusionIdCloner.class);

        void copy(ExclusionIdData source, @MappingTarget ExclusionIdData target);

        /**
         * Performs deep copy of entity data.
         */
        default ExclusionIdData deepCopy(ExclusionIdData source) {
            ExclusionIdData result = new ExclusionIdDataImpl();
            copy(source, result);
            return result;
        }
    }
}
