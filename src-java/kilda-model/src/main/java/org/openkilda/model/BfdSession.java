/* Copyright 2018 Telstra Open Source
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
import java.time.Duration;
import java.util.Objects;

@DefaultSerializer(BeanSerializer.class)
@ToString
public class BfdSession implements CompositeDataEntity<BfdSession.BfdSessionData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private BfdSessionData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private BfdSession() {
        data = new BfdSessionDataImpl();
    }

    @Builder
    public BfdSession(@NonNull SwitchId switchId, String ipAddress, SwitchId remoteSwitchId,
                      String remoteIpAddress, @NonNull Integer port, @NonNull Integer physicalPort,
                      Integer discriminator, Duration interval, short multiplier) {
        data = BfdSessionDataImpl.builder()
                .switchId(switchId).ipAddress(ipAddress).remoteSwitchId(remoteSwitchId)
                .remoteIpAddress(remoteIpAddress).port(port).physicalPort(physicalPort)
                .discriminator(discriminator).interval(interval).multiplier(multiplier).build();
    }

    public BfdSession(@NonNull BfdSessionData data) {
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
        BfdSession that = (BfdSession) o;
        return new EqualsBuilder()
                .append(getSwitchId(), that.getSwitchId())
                .append(getIpAddress(), that.getIpAddress())
                .append(getRemoteSwitchId(), that.getRemoteSwitchId())
                .append(getRemoteIpAddress(), that.getRemoteIpAddress())
                .append(getPort(), that.getPort())
                .append(getPhysicalPort(), that.getPhysicalPort())
                .append(getDiscriminator(), that.getDiscriminator())
                .append(getInterval(), that.getInterval())
                .append(getMultiplier(), that.getMultiplier())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSwitchId(), getIpAddress(), getRemoteSwitchId(), getRemoteIpAddress(),
                getPort(), getPhysicalPort(), getDiscriminator(), getInterval(), getMultiplier());
    }

    /**
     * Defines persistable data of the BfdSession.
     */
    public interface BfdSessionData {
        SwitchId getSwitchId();

        void setSwitchId(SwitchId switchId);

        String getIpAddress();

        void setIpAddress(String ipAddress);

        SwitchId getRemoteSwitchId();

        void setRemoteSwitchId(SwitchId remoteSwitchId);

        String getRemoteIpAddress();

        void setRemoteIpAddress(String remoteIpAddress);

        Integer getPort();

        void setPort(Integer port);

        Integer getPhysicalPort();

        void setPhysicalPort(Integer port);

        Integer getDiscriminator();

        void setDiscriminator(Integer discriminator);

        Duration getInterval();

        void setInterval(Duration interval);

        Short getMultiplier();

        void setMultiplier(Short multiplier);
    }

    /**
     * POJO implementation of BfdSessionData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class BfdSessionDataImpl implements BfdSessionData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull SwitchId switchId;
        String ipAddress;
        SwitchId remoteSwitchId;
        String remoteIpAddress;
        @NonNull Integer port;
        @NonNull Integer physicalPort;
        Integer discriminator;
        Duration interval;
        @NonNull Short multiplier;
    }

    /**
     * A cloner for BfdSession entity.
     */
    @Mapper
    public interface BfdSessionCloner {
        BfdSessionCloner INSTANCE = Mappers.getMapper(BfdSessionCloner.class);

        void copy(BfdSessionData source, @MappingTarget BfdSessionData target);

        /**
         * Performs deep copy of entity data.
         */
        default BfdSessionData deepCopy(BfdSessionData source) {
            BfdSessionData result = new BfdSessionDataImpl();
            copy(source, result);
            return result;
        }
    }
}
