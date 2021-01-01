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
import java.time.Instant;
import java.util.Objects;

@DefaultSerializer(BeanSerializer.class)
@ToString
public class LinkProps implements CompositeDataEntity<LinkProps.LinkPropsData> {
    public static final String COST_PROP_NAME = "cost";
    public static final String MAX_BANDWIDTH_PROP_NAME = "max_bandwidth";

    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private LinkPropsData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private LinkProps() {
        data = new LinkPropsDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public LinkProps(@NonNull LinkProps entityToClone) {
        data = LinkPropsCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public LinkProps(@NonNull SwitchId srcSwitchId, @NonNull SwitchId dstSwitchId, int srcPort, int dstPort,
                     Integer cost, Long maxBandwidth) {
        data = LinkPropsDataImpl.builder().srcSwitchId(srcSwitchId).dstSwitchId(dstSwitchId)
                .srcPort(srcPort).dstPort(dstPort).cost(cost).maxBandwidth(maxBandwidth).build();
    }

    public LinkProps(@NonNull LinkPropsData data) {
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
        LinkProps that = (LinkProps) o;
        return new EqualsBuilder()
                .append(getSrcPort(), that.getSrcPort())
                .append(getDstPort(), that.getDstPort())
                .append(getSrcSwitchId(), that.getSrcSwitchId())
                .append(getDstSwitchId(), that.getDstSwitchId())
                .append(getCost(), that.getCost())
                .append(getMaxBandwidth(), that.getMaxBandwidth())
                .append(getTimeCreate(), that.getTimeCreate())
                .append(getTimeModify(), that.getTimeModify())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSrcPort(), getDstPort(), getSrcSwitchId(), getDstSwitchId(), getCost(),
                getMaxBandwidth(), getTimeCreate(), getTimeModify());
    }

    /**
     * Defines persistable data of the LinkProps.
     */
    public interface LinkPropsData {
        int getSrcPort();

        void setSrcPort(int srcPort);

        int getDstPort();

        void setDstPort(int dstPort);

        SwitchId getSrcSwitchId();

        void setSrcSwitchId(SwitchId srcSwitchId);

        SwitchId getDstSwitchId();

        void setDstSwitchId(SwitchId dstSwitchId);

        Integer getCost();

        void setCost(Integer cost);

        Long getMaxBandwidth();

        void setMaxBandwidth(Long maxBandwidth);

        Instant getTimeCreate();

        void setTimeCreate(Instant timeCreate);

        Instant getTimeModify();

        void setTimeModify(Instant timeModify);
    }

    /**
     * POJO implementation of LinkPropsData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class LinkPropsDataImpl implements LinkPropsData, Serializable {
        private static final long serialVersionUID = 1L;
        int srcPort;
        int dstPort;
        @NonNull SwitchId srcSwitchId;
        @NonNull SwitchId dstSwitchId;
        Integer cost;
        Long maxBandwidth;
        Instant timeCreate;
        Instant timeModify;
    }

    /**
     * A cloner for LinkProps entity.
     */
    @Mapper
    public interface LinkPropsCloner {
        LinkPropsCloner INSTANCE = Mappers.getMapper(LinkPropsCloner.class);

        void copy(LinkPropsData source, @MappingTarget LinkPropsData target);

        /**
         * Performs deep copy of entity data.
         */
        default LinkPropsData deepCopy(LinkPropsData source) {
            LinkPropsData result = new LinkPropsDataImpl();
            copy(source, result);
            return result;
        }
    }
}
