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
 * Represents a cookie allocated for a flow.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class FlowCookie implements CompositeDataEntity<FlowCookie.FlowCookieData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private FlowCookieData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private FlowCookie() {
        data = new FlowCookieDataImpl();
    }

    @Builder
    public FlowCookie(@NonNull String flowId, long unmaskedCookie) {
        data = FlowCookieDataImpl.builder().flowId(flowId).unmaskedCookie(unmaskedCookie).build();
    }

    public FlowCookie(@NonNull FlowCookieData data) {
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
        FlowCookie that = (FlowCookie) o;
        return new EqualsBuilder()
                .append(getUnmaskedCookie(), that.getUnmaskedCookie())
                .append(getFlowId(), that.getFlowId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowId(), getUnmaskedCookie());
    }

    /**
     * Defines persistable data of the FlowCookie.
     */
    public interface FlowCookieData {
        String getFlowId();

        void setFlowId(String flowId);

        long getUnmaskedCookie();

        void setUnmaskedCookie(long unmaskedCookie);
    }

    /**
     * POJO implementation of FlowCookieData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class FlowCookieDataImpl implements FlowCookieData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull String flowId;
        long unmaskedCookie;
    }

    /**
     * A cloner for FlowCookie entity.
     */
    @Mapper
    public interface FlowCookieCloner {
        FlowCookieCloner INSTANCE = Mappers.getMapper(FlowCookieCloner.class);

        void copy(FlowCookieData source, @MappingTarget FlowCookieData target);

        /**
         * Performs deep copy of entity data.
         */
        default FlowCookieData deepCopy(FlowCookieData source) {
            FlowCookieData result = new FlowCookieDataImpl();
            copy(source, result);
            return result;
        }
    }
}
