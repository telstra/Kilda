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
import lombok.experimental.Delegate;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents an inter-switch link (ISL). This includes the source and destination, link status,
 * maximum and available bandwidth.
 */
@DefaultSerializer(BeanSerializer.class)
public class Isl implements CompositeDataEntity<Isl.IslData> {
    @Setter
    @Getter
    private transient IslConfig islConfig;

    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private IslData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private Isl() {
        data = new IslDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public Isl(@NonNull Isl entityToClone) {
        data = IslCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public Isl(@NonNull Switch srcSwitch, @NonNull Switch destSwitch, int srcPort, int destPort,
               long latency, long speed, int cost, long maxBandwidth, long defaultMaxBandwidth,
               long availableBandwidth, IslStatus status, IslStatus actualStatus, IslStatus roundTripStatus,
               IslDownReason downReason,
               boolean underMaintenance, Duration bfdInterval, short bfdMultiplier,
               BfdSessionStatus bfdSessionStatus, Instant timeUnstable) {
        data = IslDataImpl.builder().srcSwitch(srcSwitch).destSwitch(destSwitch).srcPort(srcPort).destPort(destPort)
                .latency(latency).speed(speed).cost(cost).maxBandwidth(maxBandwidth)
                .defaultMaxBandwidth(defaultMaxBandwidth).availableBandwidth(availableBandwidth)
                .status(status).actualStatus(actualStatus).roundTripStatus(roundTripStatus)
                .downReason(downReason)
                .underMaintenance(underMaintenance)
                .bfdInterval(bfdInterval).bfdMultiplier(bfdMultiplier).bfdSessionStatus(bfdSessionStatus)
                .timeUnstable(timeUnstable).build();
    }

    public Isl(@NonNull IslData data) {
        this.data = data;
    }

    /**
     * Return true if ISL is unstable and false otherwise.
     */
    public boolean isUnstable() {
        if (islConfig == null) {
            throw new IllegalStateException("IslConfig has not initialized.");
        }

        return getTimeUnstable() != null
                && getTimeUnstable().plus(islConfig.getUnstableIslTimeout()).isAfter(Instant.now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Isl that = (Isl) o;
        return new EqualsBuilder()
                .append(getSrcPort(), that.getSrcPort())
                .append(getDestPort(), that.getDestPort())
                .append(getLatency(), that.getLatency())
                .append(getSpeed(), that.getSpeed())
                .append(getCost(), that.getCost())
                .append(getMaxBandwidth(), that.getMaxBandwidth())
                .append(getDefaultMaxBandwidth(), that.getDefaultMaxBandwidth())
                .append(getAvailableBandwidth(), that.getAvailableBandwidth())
                .append(isUnderMaintenance(), that.isUnderMaintenance())
                .append(getSrcSwitchId(), that.getSrcSwitchId())
                .append(getDestSwitchId(), that.getDestSwitchId())
                .append(getStatus(), that.getStatus())
                .append(getActualStatus(), that.getActualStatus())
                .append(getRoundTripStatus(), that.getRoundTripStatus())
                .append(getDownReason(), that.getDownReason())
                .append(getTimeCreate(), that.getTimeCreate())
                .append(getTimeModify(), that.getTimeModify())
                .append(getBfdInterval(), that.getBfdInterval())
                .append(getBfdMultiplier(), that.getBfdInterval())
                .append(getBfdSessionStatus(), that.getBfdSessionStatus())
                .append(getTimeUnstable(), that.getTimeUnstable())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSrcSwitchId(), getDestSwitchId(), getSrcPort(), getDestPort(), getLatency(),
                getSpeed(), getCost(), getMaxBandwidth(), getDefaultMaxBandwidth(), getAvailableBandwidth(),
                getStatus(), getActualStatus(), getRoundTripStatus(), getDownReason(),
                getTimeCreate(), getTimeModify(),
                isUnderMaintenance(), getBfdInterval(), getBfdMultiplier(), getBfdSessionStatus(), getTimeUnstable());
    }

    @Override
    public String toString() {
        return "Isl{"
                + "srcSwitch=" + getSrcSwitchId()
                + ", destSwitch=" + data.getDestSwitchId()
                + ", srcPort=" + getSrcPort()
                + ", destPort=" + getDestPort()
                + ", cost=" + getCost()
                + ", availableBandwidth=" + getAvailableBandwidth()
                + ", status=" + getStatus()
                + '}';
    }

    /**
     * Defines persistable data of the IslData.
     */
    public interface IslData {
        SwitchId getSrcSwitchId();

        Switch getSrcSwitch();

        void setSrcSwitch(Switch srcSwitch);

        SwitchId getDestSwitchId();

        Switch getDestSwitch();

        void setDestSwitch(Switch destSwitch);

        int getSrcPort();

        void setSrcPort(int srcPort);

        int getDestPort();

        void setDestPort(int destPort);

        long getLatency();

        void setLatency(long latency);

        long getSpeed();

        void setSpeed(long speed);

        int getCost();

        void setCost(int cost);

        long getMaxBandwidth();

        void setMaxBandwidth(long maxBandwidth);

        long getDefaultMaxBandwidth();

        void setDefaultMaxBandwidth(long defaultMaxBandwidth);

        long getAvailableBandwidth();

        void setAvailableBandwidth(long availableBandwidth);

        IslStatus getStatus();

        void setStatus(IslStatus status);

        IslStatus getActualStatus();

        void setActualStatus(IslStatus actualStatus);

        IslStatus getRoundTripStatus();

        void setRoundTripStatus(IslStatus roundTripStatus);

        IslDownReason getDownReason();

        void setDownReason(IslDownReason downReason);

        Instant getTimeCreate();

        void setTimeCreate(Instant timeCreate);

        Instant getTimeModify();

        void setTimeModify(Instant timeModify);

        boolean isUnderMaintenance();

        void setUnderMaintenance(boolean underMaintenance);

        Duration getBfdInterval();

        void setBfdInterval(Duration interval);

        Short getBfdMultiplier();

        void setBfdMultiplier(Short multiplier);

        BfdSessionStatus getBfdSessionStatus();

        void setBfdSessionStatus(BfdSessionStatus bfdSessionStatus);

        Instant getTimeUnstable();

        void setTimeUnstable(Instant timeUnstable);
    }

    /**
     * POJO implementation of IslData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class IslDataImpl implements IslData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull Switch srcSwitch;
        @NonNull Switch destSwitch;
        int srcPort;
        int destPort;
        long latency;
        long speed;
        int cost;
        long maxBandwidth;
        long defaultMaxBandwidth;
        long availableBandwidth;
        IslStatus status;
        IslStatus actualStatus;
        IslStatus roundTripStatus;
        IslDownReason downReason;
        Instant timeCreate;
        Instant timeModify;
        boolean underMaintenance;
        Duration bfdInterval;
        Short bfdMultiplier;
        BfdSessionStatus bfdSessionStatus;
        Instant timeUnstable;

        @Override
        public SwitchId getSrcSwitchId() {
            return srcSwitch.getSwitchId();
        }

        @Override
        public SwitchId getDestSwitchId() {
            return destSwitch.getSwitchId();
        }
    }

    /**
     * A cloner for Isl entity.
     */
    @Mapper
    public interface IslCloner {
        IslCloner INSTANCE = Mappers.getMapper(IslCloner.class);

        @Mapping(target = "srcSwitch", ignore = true)
        @Mapping(target = "destSwitch", ignore = true)
        void copyWithoutSwitches(IslData source, @MappingTarget IslData target);

        /**
         * Performs deep copy of entity data.
         */
        default IslData deepCopy(IslData source) {
            IslData result = new IslDataImpl();
            copyWithoutSwitches(source, result);
            result.setSrcSwitch(new Switch(source.getSrcSwitch()));
            result.setDestSwitch(new Switch(source.getDestSwitch()));
            return result;
        }
    }
}
