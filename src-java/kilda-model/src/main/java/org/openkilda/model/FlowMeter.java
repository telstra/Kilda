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
 * Represents a meter allocated for a flow path.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class FlowMeter implements CompositeDataEntity<FlowMeter.FlowMeterData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private FlowMeterData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private FlowMeter() {
        data = new FlowMeterDataImpl();
    }

    @Builder
    public FlowMeter(@NonNull SwitchId switchId, @NonNull MeterId meterId, @NonNull String flowId,
                     @NonNull PathId pathId) {
        data = FlowMeterDataImpl.builder().switchId(switchId).meterId(meterId).flowId(flowId).pathId(pathId).build();
    }

    public FlowMeter(@NonNull FlowMeterData data) {
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
        FlowMeter that = (FlowMeter) o;
        return new EqualsBuilder()
                .append(getSwitchId(), that.getSwitchId())
                .append(getMeterId(), that.getMeterId())
                .append(getFlowId(), that.getFlowId())
                .append(getPathId(), that.getPathId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSwitchId(), getMeterId(), getFlowId(), getPathId());
    }

    /**
     * Defines persistable data of the FlowMeter.
     */
    public interface FlowMeterData {
        SwitchId getSwitchId();

        void setSwitchId(SwitchId switchId);

        MeterId getMeterId();

        void setMeterId(MeterId meterId);

        String getFlowId();

        void setFlowId(String flowId);

        PathId getPathId();

        void setPathId(PathId pathId);
    }

    /**
     * POJO implementation of FlowMeterData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class FlowMeterDataImpl implements FlowMeterData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull SwitchId switchId;
        @NonNull MeterId meterId;
        @NonNull String flowId;
        @NonNull PathId pathId;
    }

    /**
     * A cloner for FlowMeter entity.
     */
    @Mapper
    public interface FlowMeterCloner {
        FlowMeterCloner INSTANCE = Mappers.getMapper(FlowMeterCloner.class);

        void copy(FlowMeterData source, @MappingTarget FlowMeterData target);

        /**
         * Performs deep copy of entity data.
         */
        default FlowMeterData deepCopy(FlowMeterData source) {
            FlowMeterData result = new FlowMeterDataImpl();
            copy(source, result);
            return result;
        }
    }
}
