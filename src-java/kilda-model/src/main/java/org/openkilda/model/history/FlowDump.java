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
import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchId;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.serializers.BeanSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Delegate;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.io.Serializable;

/**
 * Represents information about the flow state.
 * Contains all Flow state.
 */
@DefaultSerializer(BeanSerializer.class)
public class FlowDump implements CompositeDataEntity<FlowDump.FlowDumpData> {
    @Getter
    @Setter
    @Delegate
    @JsonIgnore
    private FlowDumpData data;

    /**
     * No args constructor for deserialization purpose.
     */
    public FlowDump() {
        data = new FlowDumpDataImpl();
    }

    /**
     * Cloning constructor which performs deep copy of the flow dump.
     *
     * @param entityToClone the entity to copy entity data from.
     */
    public FlowDump(@NonNull FlowDump entityToClone) {
        data = FlowDumpCloner.INSTANCE.copy(entityToClone.getData());
    }

    public FlowDump(@NonNull FlowDumpData data) {
        this.data = data;
    }

    /**
     * Defines persistable data of the FlowDump.
     */
    public interface FlowDumpData {
        String getTaskId();

        void setTaskId(String taskId);

        String getFlowId();

        void setFlowId(String flowId);

        String getType();

        void setType(String type);

        long getBandwidth();

        void setBandwidth(long bandwidth);

        boolean isIgnoreBandwidth();

        void setIgnoreBandwidth(boolean ignoreBandwidth);

        Cookie getForwardCookie();

        void setForwardCookie(Cookie forwardCookie);

        Cookie getReverseCookie();

        void setReverseCookie(Cookie reverseCookie);

        SwitchId getSourceSwitch();

        void setSourceSwitch(SwitchId sourceSwitch);

        SwitchId getDestinationSwitch();

        void setDestinationSwitch(SwitchId destinationSwitch);

        int getSourcePort();

        void setSourcePort(int sourcePort);

        int getDestinationPort();

        void setDestinationPort(int destinationPort);

        int getSourceVlan();

        void setSourceVlan(int sourceVlan);

        int getDestinationVlan();

        void setDestinationVlan(int destinationVlan);

        MeterId getForwardMeterId();

        void setForwardMeterId(MeterId forwardMeterId);

        MeterId getReverseMeterId();

        void setReverseMeterId(MeterId reverseMeterId);

        String getForwardPath();

        void setForwardPath(String forwardPath);

        String getReversePath();

        void setReversePath(String reversePath);

        FlowPathStatus getForwardStatus();

        void setForwardStatus(FlowPathStatus forwardStatus);

        FlowPathStatus getReverseStatus();

        void setReverseStatus(FlowPathStatus reverseStatus);
    }

    /**
     * POJO implementation of FlowDumpData.
     */
    @Data
    @NoArgsConstructor
    static final class FlowDumpDataImpl implements FlowDumpData, Serializable {
        private static final long serialVersionUID = 1L;
        String taskId;
        String flowId;
        String type;
        long bandwidth;
        boolean ignoreBandwidth;
        Cookie forwardCookie;
        Cookie reverseCookie;
        SwitchId sourceSwitch;
        SwitchId destinationSwitch;
        int sourcePort;
        int destinationPort;
        int sourceVlan;
        int destinationVlan;
        MeterId forwardMeterId;
        MeterId reverseMeterId;
        String forwardPath;
        String reversePath;
        FlowPathStatus forwardStatus;
        FlowPathStatus reverseStatus;
    }

    @Mapper
    public interface FlowDumpCloner {
        FlowDumpCloner INSTANCE = Mappers.getMapper(FlowDumpCloner.class);

        void copy(FlowDumpData source, @MappingTarget FlowDumpData target);

        /**
         * Performs deep copy of entity data.
         */
        default FlowDumpData copy(FlowDumpData source) {
            FlowDumpData result = new FlowDumpDataImpl();
            copy(source, result);
            return result;
        }
    }
}
