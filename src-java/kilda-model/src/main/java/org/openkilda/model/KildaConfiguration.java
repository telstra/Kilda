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
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;
import java.util.Objects;

@DefaultSerializer(BeanSerializer.class)
@ToString
public class KildaConfiguration implements CompositeDataEntity<KildaConfiguration.KildaConfigurationData> {
    public static final KildaConfiguration DEFAULTS = new KildaConfiguration(KildaConfigurationDataImpl.builder()
            .flowEncapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
            .useMultiTable(false)
            .pathComputationStrategy(PathComputationStrategy.COST_AND_AVAILABLE_BANDWIDTH)
            .build());

    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private KildaConfigurationData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private KildaConfiguration() {
        data = new KildaConfigurationDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public KildaConfiguration(@NonNull KildaConfiguration entityToClone) {
        data = KildaConfigurationCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public KildaConfiguration(FlowEncapsulationType flowEncapsulationType, Boolean useMultiTable,
                              PathComputationStrategy pathComputationStrategy) {
        data = KildaConfigurationDataImpl.builder().flowEncapsulationType(flowEncapsulationType)
                .useMultiTable(useMultiTable)
                .pathComputationStrategy(pathComputationStrategy).build();
    }

    public KildaConfiguration(@NonNull KildaConfigurationData data) {
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
        KildaConfiguration that = (KildaConfiguration) o;
        return new EqualsBuilder()
                .append(getFlowEncapsulationType(), that.getFlowEncapsulationType())
                .append(getUseMultiTable(), that.getUseMultiTable())
                .append(getPathComputationStrategy(), that.getPathComputationStrategy())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowEncapsulationType(), getUseMultiTable(), getPathComputationStrategy());
    }

    /**
     * Defines persistable data of the KildaConfiguration.
     */
    public interface KildaConfigurationData {
        FlowEncapsulationType getFlowEncapsulationType();

        void setFlowEncapsulationType(FlowEncapsulationType flowEncapsulationType);

        Boolean getUseMultiTable();

        void setUseMultiTable(Boolean useMultiTable);

        PathComputationStrategy getPathComputationStrategy();

        void setPathComputationStrategy(PathComputationStrategy pathComputationStrategy);
    }

    /**
     * POJO implementation of KildaConfigurationData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class KildaConfigurationDataImpl implements KildaConfigurationData, Serializable {
        private static final long serialVersionUID = 1L;
        FlowEncapsulationType flowEncapsulationType;
        Boolean useMultiTable;
        PathComputationStrategy pathComputationStrategy;
    }

    /**
     * A cloner for KildaConfiguration entity.
     */
    @Mapper(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    public interface KildaConfigurationCloner {
        KildaConfigurationCloner INSTANCE = Mappers.getMapper(KildaConfigurationCloner.class);

        void copyNonNull(KildaConfigurationData source, @MappingTarget KildaConfigurationData target);

        default void copyNonNull(KildaConfiguration source, KildaConfiguration target) {
            copyNonNull(source.getData(), target.getData());
        }

        /**
         * Performs deep copy of entity data.
         */
        default KildaConfigurationData deepCopy(KildaConfigurationData source) {
            KildaConfigurationData result = new KildaConfigurationDataImpl();
            copyNonNull(source, result);
            return result;
        }

        /**
         * Replaces null properties of the target with the source data.
         */
        default void replaceNullProperties(KildaConfiguration source, KildaConfiguration target) {
            if (target.getFlowEncapsulationType() == null) {
                target.setFlowEncapsulationType(source.getFlowEncapsulationType());
            }
            if (target.getUseMultiTable() == null) {
                target.setUseMultiTable(source.getUseMultiTable());
            }
            if (target.getPathComputationStrategy() == null) {
                target.setPathComputationStrategy(source.getPathComputationStrategy());
            }
        }
    }
}
