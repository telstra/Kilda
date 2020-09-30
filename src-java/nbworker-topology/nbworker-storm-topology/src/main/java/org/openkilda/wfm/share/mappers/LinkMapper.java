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

package org.openkilda.wfm.share.mappers;

import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.model.EffectiveBfdProperties;
import org.openkilda.model.Isl;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public abstract class LinkMapper {
    public static final LinkMapper INSTANCE = Mappers.getMapper(LinkMapper.class);

    /**
     * Convert {@link Isl} into {@link BfdPropertiesResponse}.
     */
    public BfdPropertiesResponse mapResponse(
            Isl link, EffectiveBfdProperties effectiveSource, EffectiveBfdProperties effectiveDestination) {
        return new BfdPropertiesResponse(
                mapSource(link), mapDestination(link),
                IslMapper.INSTANCE.readBfdProperties(link), effectiveSource, effectiveDestination,
                IslMapper.INSTANCE.map(link));
    }

    @Mapping(target = "datapath", source = "srcSwitch.switchId")
    @Mapping(target = "portNumber", source = "srcPort")
    public abstract NetworkEndpoint mapSource(Isl link);

    @Mapping(target = "datapath", source = "destSwitch.switchId")
    @Mapping(target = "portNumber", source = "destPort")
    public abstract NetworkEndpoint mapDestination(Isl link);
}
