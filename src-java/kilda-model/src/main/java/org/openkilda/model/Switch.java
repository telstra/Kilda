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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a switch.
 */
@DefaultSerializer(BeanSerializer.class)
@ToString
public class Switch implements CompositeDataEntity<Switch.SwitchData> {
    private static final Pattern NOVIFLOW_SOFTWARE_REGEX = Pattern.compile("(.*)NW\\d{3}\\.\\d+\\.\\d+(.*)");
    private static final Pattern E_SWITCH_HARDWARE_DESCRIPTION_REGEX = Pattern.compile("^WB5\\d{3}-E$");
    private static final String E_SWITCH_MANUFACTURER_DESCRIPTION = "E";

    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private SwitchData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private Switch() {
        data = new SwitchDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public Switch(@NonNull Switch entityToClone) {
        data = SwitchCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public Switch(@NonNull SwitchId switchId, SwitchStatus status, InetSocketAddress socketAddress,
                  String hostname, String controller, String description, String ofVersion,
                  String ofDescriptionManufacturer, String ofDescriptionHardware, String ofDescriptionSoftware,
                  String ofDescriptionSerialNumber, String ofDescriptionDatapath,
                  Set<SwitchFeature> features, boolean underMaintenance, String pop,
                  double latitude, double longitude, String street, String city, String country) {
        SwitchDataImpl.SwitchDataImplBuilder builder = SwitchDataImpl.builder()
                .switchId(switchId).status(status).socketAddress(socketAddress).hostname(hostname)
                .controller(controller).description(description).ofVersion(ofVersion)
                .ofDescriptionManufacturer(ofDescriptionManufacturer)
                .ofDescriptionHardware(ofDescriptionHardware).ofDescriptionSoftware(ofDescriptionSoftware)
                .ofDescriptionSerialNumber(ofDescriptionSerialNumber)
                .ofDescriptionDatapath(ofDescriptionDatapath)
                .underMaintenance(underMaintenance).pop(pop)
                .latitude(latitude).longitude(longitude).street(street).city(city).country(country);
        if (features != null) {
            builder.features(features);
        }

        data = builder.build();
    }

    public Switch(@NonNull SwitchData data) {
        this.data = data;
    }

    /**
     * Checks Centec switch by the manufacturer description.
     */
    public static boolean isCentecSwitch(String manufacturerDescription) {
        return StringUtils.contains(manufacturerDescription.toLowerCase(), "centec");
    }

    /**
     * Checks Noviflow switch by the software description.
     */
    public static boolean isNoviflowSwitch(String softwareDescription) {
        return NOVIFLOW_SOFTWARE_REGEX.matcher(softwareDescription).matches();
    }

    /**
     * Checks Noviflow E switch by the manufacturer and hardware description.
     */
    public static boolean isNoviflowESwitch(String manufacturerDescription, String hardwareDescription) {
        return E_SWITCH_MANUFACTURER_DESCRIPTION.equalsIgnoreCase(manufacturerDescription)
                || hardwareDescription != null
                && E_SWITCH_HARDWARE_DESCRIPTION_REGEX.matcher(hardwareDescription).matches();
    }

    @JsonIgnore
    public boolean isActive() {
        return getStatus() == SwitchStatus.ACTIVE;
    }

    /**
     * Checks switch feature support.
     */
    public boolean supports(SwitchFeature feature) {
        return getFeatures().contains(feature);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Switch that = (Switch) o;
        return new EqualsBuilder()
                .append(isUnderMaintenance(), that.isUnderMaintenance())
                .append(getSwitchId(), that.getSwitchId())
                .append(getStatus(), that.getStatus())
                .append(getSocketAddress(), that.getSocketAddress())
                .append(getHostname(), that.getHostname())
                .append(getController(), that.getController())
                .append(getDescription(), that.getDescription())
                .append(getOfVersion(), that.getOfVersion())
                .append(getOfDescriptionManufacturer(), that.getOfDescriptionManufacturer())
                .append(getOfDescriptionHardware(), that.getOfDescriptionHardware())
                .append(getOfDescriptionSoftware(), that.getOfDescriptionSoftware())
                .append(getOfDescriptionSerialNumber(), that.getOfDescriptionSerialNumber())
                .append(getOfDescriptionDatapath(), that.getOfDescriptionDatapath())
                .append(getFeatures(), that.getFeatures())
                .append(getTimeCreate(), that.getTimeCreate())
                .append(getTimeModify(), that.getTimeModify())
                .append(getPop(), that.getPop())
                .append(getLatitude(), that.getLatitude())
                .append(getLongitude(), that.getLongitude())
                .append(getStreet(), that.getStreet())
                .append(getCity(), that.getCity())
                .append(getCountry(), that.getCountry())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSwitchId(), getStatus(), getSocketAddress(), getHostname(), getController(),
                getDescription(), getOfVersion(), getOfDescriptionManufacturer(), getOfDescriptionHardware(),
                getOfDescriptionSoftware(), getOfDescriptionSerialNumber(), getOfDescriptionDatapath(),
                getFeatures(), isUnderMaintenance(), getTimeCreate(), getTimeModify(), getPop(),
                getLatitude(), getLongitude(), getStreet(), getCity(), getCountry());
    }

    /**
     * Defines persistable data of the Switch.
     */
    public interface SwitchData {
        SwitchId getSwitchId();

        void setSwitchId(SwitchId switchId);

        SwitchStatus getStatus();

        void setStatus(SwitchStatus status);

        InetSocketAddress getSocketAddress();

        void setSocketAddress(InetSocketAddress socketAddress);

        String getHostname();

        void setHostname(String hostname);

        String getController();

        void setController(String controller);

        String getDescription();

        void setDescription(String description);

        String getOfVersion();

        void setOfVersion(String ofVersion);

        String getOfDescriptionManufacturer();

        void setOfDescriptionManufacturer(String ofDescriptionManufacturer);

        String getOfDescriptionHardware();

        void setOfDescriptionHardware(String ofDescriptionHardware);

        String getOfDescriptionSoftware();

        void setOfDescriptionSoftware(String ofDescriptionSoftware);

        String getOfDescriptionSerialNumber();

        void setOfDescriptionSerialNumber(String ofDescriptionSerialNumber);

        String getOfDescriptionDatapath();

        void setOfDescriptionDatapath(String ofDescriptionDatapath);

        boolean isUnderMaintenance();

        void setUnderMaintenance(boolean underMaintenance);

        Instant getTimeCreate();

        void setTimeCreate(Instant timeCreate);

        Instant getTimeModify();

        void setTimeModify(Instant timeModify);

        String getPop();

        void setPop(String pop);

        double getLatitude();

        void setLatitude(double latitude);

        double getLongitude();

        void setLongitude(double longitude);

        String getStreet();

        void setStreet(String street);

        String getCity();

        void setCity(String city);

        String getCountry();

        void setCountry(String country);

        Set<SwitchFeature> getFeatures();

        void setFeatures(Set<SwitchFeature> features);
    }

    /**
     * POJO implementation of SwitchData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class SwitchDataImpl implements SwitchData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull SwitchId switchId;
        SwitchStatus status;
        InetSocketAddress socketAddress;
        String hostname;
        String controller;
        String description;
        String ofVersion;
        String ofDescriptionManufacturer;
        String ofDescriptionHardware;
        String ofDescriptionSoftware;
        String ofDescriptionSerialNumber;
        String ofDescriptionDatapath;
        @Builder.Default
        Set<SwitchFeature> features = new HashSet<>();
        boolean underMaintenance;
        Instant timeCreate;
        Instant timeModify;
        String pop;
        double latitude;
        double longitude;
        String street;
        String city;
        String country;

        /**
         * Set features for the switch.
         *
         * @param features target features
         */
        public void setFeatures(Set<SwitchFeature> features) {
            if (features == null) {
                features = new HashSet<>();
            }
            this.features = features;
        }
    }

    /**
     * A cloner for Switch entity.
     */
    @Mapper
    public interface SwitchCloner {
        SwitchCloner INSTANCE = Mappers.getMapper(SwitchCloner.class);

        @Mapping(target = "features", ignore = true)
        void copyWithoutFeatures(SwitchData source, @MappingTarget SwitchData target);

        default void copy(SwitchData source, SwitchData target) {
            copyWithoutFeatures(source, target);
            target.setFeatures(source.getFeatures());
        }

        /**
         * Performs deep copy of entity data.
         */
        default SwitchData deepCopy(SwitchData source) {
            SwitchDataImpl result = new SwitchDataImpl();
            copy(source, result);
            return result;
        }
    }
}
