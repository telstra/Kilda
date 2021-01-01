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
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a flow connected device.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class SwitchConnectedDevice implements CompositeDataEntity<SwitchConnectedDevice.SwitchConnectedDeviceData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private SwitchConnectedDeviceData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private SwitchConnectedDevice() {
        data = new SwitchConnectedDeviceDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public SwitchConnectedDevice(@NonNull SwitchConnectedDevice entityToClone) {
        data = SwitchConnectedDeviceCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public SwitchConnectedDevice(@NonNull Switch switchObj, int portNumber, int vlan, String flowId, Boolean source,
                                 @NonNull String macAddress, @NonNull ConnectedDeviceType type, String ipAddress,
                                 String chassisId, String portId, Integer ttl, String portDescription,
                                 String systemName, String systemDescription, String systemCapabilities,
                                 String managementAddress, Instant timeFirstSeen, Instant timeLastSeen) {
        data = new SwitchConnectedDeviceDataImpl(switchObj, portNumber, vlan, flowId, source, macAddress, type,
                ipAddress, chassisId, portId, ttl, portDescription, systemName, systemDescription,
                systemCapabilities, managementAddress, timeFirstSeen, timeLastSeen);
    }

    public SwitchConnectedDevice(@NonNull SwitchConnectedDeviceData data) {
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
        SwitchConnectedDevice that = (SwitchConnectedDevice) o;
        return new EqualsBuilder()
                .append(getPortNumber(), that.getPortNumber())
                .append(getVlan(), that.getVlan())
                .append(getSwitchId(), that.getSwitchId())
                .append(getFlowId(), that.getFlowId())
                .append(getSource(), that.getSource())
                .append(getMacAddress(), that.getMacAddress())
                .append(getType(), that.getType())
                .append(getIpAddress(), that.getIpAddress())
                .append(getChassisId(), that.getChassisId())
                .append(getPortId(), that.getPortId())
                .append(getTtl(), that.getTtl())
                .append(getPortDescription(), that.getPortDescription())
                .append(getSystemName(), that.getSystemName())
                .append(getSystemDescription(), that.getSystemDescription())
                .append(getSystemCapabilities(), that.getSystemCapabilities())
                .append(getManagementAddress(), that.getManagementAddress())
                .append(getTimeFirstSeen(), that.getTimeFirstSeen())
                .append(getTimeLastSeen(), that.getTimeLastSeen())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSwitchId(), getPortNumber(), getVlan(), getFlowId(), getSource(), getMacAddress(),
                getType(), getIpAddress(), getChassisId(), getPortId(), getTtl(), getPortDescription(),
                getSystemName(), getSystemDescription(), getSystemCapabilities(), getManagementAddress(),
                getTimeFirstSeen(), getTimeLastSeen());
    }

    /**
     * Defines persistable data of the SwitchConnectedDevice.
     */
    public interface SwitchConnectedDeviceData {
        SwitchId getSwitchId();

        Switch getSwitchObj();

        void setSwitchObj(Switch switchObj);

        int getPortNumber();

        void setPortNumber(int portNumber);

        int getVlan();

        void setVlan(int vlan);

        String getFlowId();

        void setFlowId(String flowId);

        Boolean getSource();

        void setSource(Boolean source);

        String getMacAddress();

        void setMacAddress(String macAddress);

        ConnectedDeviceType getType();

        void setType(ConnectedDeviceType connectedDeviceType);

        String getIpAddress();

        void setIpAddress(String ipAddress);

        String getChassisId();

        void setChassisId(String chassisId);

        String getPortId();

        void setPortId(String portId);

        Integer getTtl();

        void setTtl(Integer ttl);

        String getPortDescription();

        void setPortDescription(String portDescription);

        String getSystemName();

        void setSystemName(String systemName);

        String getSystemDescription();

        void setSystemDescription(String systemDescription);

        String getSystemCapabilities();

        void setSystemCapabilities(String systemCapabilities);

        String getManagementAddress();

        void setManagementAddress(String managementAddress);

        Instant getTimeFirstSeen();

        void setTimeFirstSeen(Instant timeFirstSeen);

        Instant getTimeLastSeen();

        void setTimeLastSeen(Instant timeLastSeen);
    }

    /**
     * POJO implementation of SwitchConnectedDeviceData.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static final class SwitchConnectedDeviceDataImpl implements SwitchConnectedDeviceData, Serializable {
        private static final long serialVersionUID = 1L;
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        @NonNull Switch switchObj;
        int portNumber;
        int vlan;
        String flowId;
        Boolean source;
        @NonNull String macAddress;
        @NonNull ConnectedDeviceType type;
        String ipAddress;
        String chassisId;
        String portId;
        Integer ttl;
        String portDescription;
        String systemName;
        String systemDescription;
        String systemCapabilities;
        String managementAddress;
        Instant timeFirstSeen;
        Instant timeLastSeen;

        @Override
        public SwitchId getSwitchId() {
            return switchObj.getSwitchId();
        }
    }

    /**
     * A cloner for SwitchConnectedDevice entity.
     */
    @Mapper
    public interface SwitchConnectedDeviceCloner {
        SwitchConnectedDeviceCloner INSTANCE = Mappers.getMapper(SwitchConnectedDeviceCloner.class);

        void copy(SwitchConnectedDeviceData source, @MappingTarget SwitchConnectedDeviceData target);

        /**
         * Performs deep copy of entity data.
         */
        default SwitchConnectedDeviceData deepCopy(SwitchConnectedDeviceData source) {
            SwitchConnectedDeviceData result = new SwitchConnectedDeviceDataImpl();
            copyWithoutSwitch(source, result);
            result.setSwitchObj(new Switch(source.getSwitchObj()));
            return result;
        }

        @Mapping(target = "switchObj", ignore = true)
        void copyWithoutSwitch(SwitchConnectedDeviceData source, @MappingTarget SwitchConnectedDeviceData target);
    }
}
