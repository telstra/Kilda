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
import java.util.Objects;

@DefaultSerializer(BeanSerializer.class)
@ToString
public class PortProperties implements CompositeDataEntity<PortProperties.PortPropertiesData> {
    public static final boolean DISCOVERY_ENABLED_DEFAULT = true;

    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private PortPropertiesData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private PortProperties() {
        data = new PortPropertiesDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy properties data from.
     */
    public PortProperties(@NonNull PortProperties entityToClone) {
        data = PortPropertiesCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public PortProperties(@NonNull Switch switchObj, int port, Boolean discoveryEnabled) {
        PortPropertiesDataImpl.PortPropertiesDataImplBuilder builder = PortPropertiesDataImpl.builder()
                .switchObj(switchObj).port(port);
        builder.discoveryEnabled(discoveryEnabled != null ? discoveryEnabled : DISCOVERY_ENABLED_DEFAULT);
        data = builder.build();
    }

    public PortProperties(@NonNull PortPropertiesData data) {
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
        PortProperties that = (PortProperties) o;
        return new EqualsBuilder()
                .append(getPort(), that.getPort())
                .append(isDiscoveryEnabled(), that.isDiscoveryEnabled())
                .append(getSwitchId(), that.getSwitchId())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSwitchId(), getPort(), isDiscoveryEnabled());
    }

    /**
     * Defines persistable data of the PortProperties.
     */
    public interface PortPropertiesData {
        SwitchId getSwitchId();

        Switch getSwitchObj();

        void setSwitchObj(Switch switchObj);

        int getPort();

        void setPort(int port);

        boolean isDiscoveryEnabled();

        void setDiscoveryEnabled(boolean discoveryEnabled);
    }

    /**
     * POJO implementation of PortPropertiesData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class PortPropertiesDataImpl implements PortPropertiesData, Serializable {
        private static final long serialVersionUID = 1L;
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        @NonNull Switch switchObj;
        int port;
        @Builder.Default
        boolean discoveryEnabled = DISCOVERY_ENABLED_DEFAULT;

        @Override
        public SwitchId getSwitchId() {
            return switchObj.getSwitchId();
        }
    }

    /**
     * A cloner for PortProperties entity.
     */
    @Mapper
    public interface PortPropertiesCloner {
        PortPropertiesCloner INSTANCE = Mappers.getMapper(PortPropertiesCloner.class);

        default void copy(PortPropertiesData source, PortPropertiesData target) {
            copyWithoutSwitch(source, target);
            target.setSwitchObj(new Switch(source.getSwitchObj()));
        }

        @Mapping(target = "switchObj", ignore = true)
        void copyWithoutSwitch(PortPropertiesData source, @MappingTarget PortPropertiesData target);

        /**
         * Performs deep copy of entity data.
         */
        default PortPropertiesData deepCopy(PortPropertiesData source) {
            PortPropertiesData result = new PortPropertiesDataImpl();
            copyWithoutSwitch(source, result);
            result.setSwitchObj(new Switch(source.getSwitchObj()));
            return result;
        }
    }
}
