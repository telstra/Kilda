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

package org.openkilda.wfm.share.mappers;

import org.openkilda.messaging.info.event.PortChangeType;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.payload.switches.PortPropertiesPayload;
import org.openkilda.model.Port;
import org.openkilda.model.PortProperties;
import org.openkilda.model.PortStatus;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Convert {@link org.openkilda.model.Port} to {@link PortInfoData} and back.
 */
@Mapper
public abstract class PortMapper {

    public static final PortMapper INSTANCE = Mappers.getMapper(PortMapper.class);

    @Mapping(source = "state", target = "status")
    public abstract Port map(PortInfoData portInfoData);

    @Mapping(target = "timestamp", ignore = true)
    @Mapping(target = "maxCapacity", ignore = true)
    @Mapping(target = "state", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "rtrtkPortTest", ignore = true)
    public abstract PortInfoData map(Port port);

    /**
     *  Convert {@link PortChangeType} to {@link PortStatus}.
     *
     * @param portChangeType port change type.
     * @return converted port status.
     */
    public PortStatus map(PortChangeType portChangeType) {
        if (portChangeType == null) {
            return null;
        }

        switch (portChangeType) {
            case ADD:
            case UP:
                return PortStatus.UP;
            default:
            case DELETE:
            case DOWN:
                return PortStatus.DOWN;
        }
    }

    @Mapping(source = "switchObj.switchId", target = "switchId")
    @Mapping(source = "port", target = "portNumber")
    @Mapping(target = "timestamp", ignore = true)
    public abstract PortPropertiesPayload map(PortProperties portProperties);
}
