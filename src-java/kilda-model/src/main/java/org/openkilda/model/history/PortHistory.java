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

package org.openkilda.model.history;

import org.openkilda.model.CompositeDataEntity;
import org.openkilda.model.SwitchId;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.serializers.BeanSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
import java.util.UUID;

@DefaultSerializer(BeanSerializer.class)
@ToString
public class PortHistory implements CompositeDataEntity<PortHistory.PortHistoryData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private PortHistoryData data;

    /**
     * No args constructor for deserialization purpose.
     */
    public PortHistory() {
        data = new PortHistoryDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the port history entity.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public PortHistory(@NonNull PortHistory entityToClone) {
        data = PortHistoryCloner.INSTANCE.deepCopy(entityToClone.getData());
    }

    public PortHistory(@NonNull PortHistoryData data) {
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
        PortHistory that = (PortHistory) o;
        return new EqualsBuilder()
                .append(getPortNumber(), that.getPortNumber())
                .append(getUpEventsCount(), that.getUpEventsCount())
                .append(getDownEventsCount(), that.getDownEventsCount())
                .append(getRecordId(), that.getRecordId())
                .append(getSwitchId(), that.getSwitchId())
                .append(getEvent(), that.getEvent())
                .append(getTime(), that.getTime())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getRecordId(), getSwitchId(), getPortNumber(), getEvent(), getTime(),
                getUpEventsCount(), getDownEventsCount());
    }

    /**
     * Defines persistable data of the PortHistory.
     */
    public interface PortHistoryData {
        UUID getRecordId();

        void setRecordId(UUID id);

        SwitchId getSwitchId();

        void setSwitchId(SwitchId switchId);

        int getPortNumber();

        void setPortNumber(int portNumber);

        String getEvent();

        void setEvent(String event);

        Instant getTime();

        void setTime(Instant time);

        int getUpEventsCount();

        void setUpEventsCount(int upEventsCount);

        int getDownEventsCount();

        void setDownEventsCount(int downEventsCount);
    }

    /**
     * POJO implementation of PortHistoryData.
     */
    @Data
    @NoArgsConstructor
    static final class PortHistoryDataImpl implements PortHistoryData, Serializable {
        private static final long serialVersionUID = 1L;
        @NonNull UUID recordId;
        @NonNull SwitchId switchId;
        int portNumber;
        @NonNull String event;
        @NonNull Instant time;
        int upEventsCount;
        int downEventsCount;
    }

    @Mapper
    public interface PortHistoryCloner {
        PortHistoryCloner INSTANCE = Mappers.getMapper(PortHistoryCloner.class);

        void copy(PortHistoryData source, @MappingTarget PortHistoryData target);

        /**
         * Performs deep copy of entity data.
         */
        default PortHistoryData deepCopy(PortHistoryData source) {
            PortHistoryData result = new PortHistoryDataImpl();
            copy(source, result);
            return result;
        }
    }
}
