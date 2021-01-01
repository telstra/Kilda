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

import org.openkilda.model.cookie.ExclusionCookie;

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

@DefaultSerializer(BeanSerializer.class)
@ToString
public class ApplicationRule implements CompositeDataEntity<ApplicationRule.ApplicationRuleData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private ApplicationRuleData data;

    /**
     * No args constructor for deserialization purpose.
     */
    private ApplicationRule() {
        data = new ApplicationRuleDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public ApplicationRule(@NonNull ApplicationRule entityToClone) {
        data = ApplicationRuleCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    @Builder
    public ApplicationRule(String flowId, SwitchId switchId, ExclusionCookie cookie, String srcIp,
                           int srcPort, String dstIp, int dstPort, String proto, String ethType,
                           long metadata, int expirationTimeout) {
        data = ApplicationRuleDataImpl.builder()
                .flowId(flowId).switchId(switchId).cookie(cookie).srcIp(srcIp).srcPort(srcPort)
                .dstIp(dstIp).dstPort(dstPort).proto(proto).ethType(ethType).metadata(metadata)
                .expirationTimeout(expirationTimeout).build();
    }

    public ApplicationRule(@NonNull ApplicationRuleData data) {
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
        ApplicationRule that = (ApplicationRule) o;
        return new EqualsBuilder()
                .append(getFlowId(), that.getFlowId())
                .append(getSwitchId(), that.getSwitchId())
                .append(getCookie(), that.getCookie())
                .append(getSrcIp(), that.getSrcIp())
                .append(getSrcPort(), that.getSrcPort())
                .append(getDstIp(), that.getDstIp())
                .append(getDstPort(), that.getDstPort())
                .append(getProto(), that.getProto())
                .append(getEthType(), that.getEthType())
                .append(getMetadata(), that.getMetadata())
                .append(getExpirationTimeout(), that.getExpirationTimeout())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getFlowId(), getSwitchId(), getCookie(), getSrcIp(),
                getSrcPort(), getDstIp(), getDstPort(), getProto(), getEthType(),
                getMetadata(), getExpirationTimeout());
    }

    /**
     * Defines persistable data of the ApplicationRule.
     */
    public interface ApplicationRuleData {
        String getFlowId();

        void setFlowId(String flowId);

        SwitchId getSwitchId();

        void setSwitchId(SwitchId switchId);

        ExclusionCookie getCookie();

        void setCookie(ExclusionCookie cookie);

        String getSrcIp();

        void setSrcIp(String srcIp);

        int getSrcPort();

        void setSrcPort(int srcPort);

        String getDstIp();

        void setDstIp(String dstIp);

        int getDstPort();

        void setDstPort(int dstPort);

        String getProto();

        void setProto(String proto);

        String getEthType();

        void setEthType(String ethType);

        long getMetadata();

        void setMetadata(long metadata);

        int getExpirationTimeout();

        void setExpirationTimeout(int expirationTimeout);
    }

    /**
     * POJO implementation of ApplicationRuleData.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static final class ApplicationRuleDataImpl implements ApplicationRuleData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull
        String flowId;
        @NonNull
        SwitchId switchId;
        @NonNull
        ExclusionCookie cookie;
        String srcIp;
        int srcPort;
        String dstIp;
        int dstPort;
        String proto;
        String ethType;
        long metadata;
        int expirationTimeout;
    }

    /**
     * A cloner for ApplicationRule entity.
     */
    @Mapper
    public interface ApplicationRuleCloner {
        ApplicationRuleCloner INSTANCE = Mappers.getMapper(ApplicationRuleCloner.class);

        void copy(ApplicationRuleData source, @MappingTarget ApplicationRuleData target);

        /**
         * Performs deep copy of entity data.
         */
        default ApplicationRuleData deepCopy(ApplicationRuleData source) {
            ApplicationRuleData result = new ApplicationRuleDataImpl();
            copy(source, result);
            return result;
        }
    }
}
