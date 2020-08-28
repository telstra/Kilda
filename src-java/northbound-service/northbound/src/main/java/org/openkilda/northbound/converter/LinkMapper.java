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

package org.openkilda.northbound.converter;

import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.nbtopology.request.BfdPropertiesReadRequest;
import org.openkilda.messaging.nbtopology.request.BfdPropertiesWriteRequest;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.dto.v1.links.LinkDto;
import org.openkilda.northbound.dto.v1.links.PathDto;
import org.openkilda.northbound.dto.v2.links.BfdPropertiesByEndpoint;
import org.openkilda.northbound.dto.v2.links.BfdPropertiesPayload;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.Duration;
import java.util.Arrays;

@Mapper(componentModel = "spring")
public abstract class LinkMapper {
    public abstract PathDto map(PathNode data);

    @Mapping(target = "interval", source = "intervalMs")
    public abstract org.openkilda.model.BfdProperties map(org.openkilda.northbound.dto.v2.links.BfdProperties source);

    @Mapping(target = "intervalMs", source = "interval")
    public abstract org.openkilda.northbound.dto.v2.links.BfdProperties map(org.openkilda.model.BfdProperties source);

    public abstract org.openkilda.northbound.dto.v2.links.BfdSessionStatus map(
            org.openkilda.model.BfdSessionStatus status);

    @Mapping(target = "timestamp", ignore = true)
    public abstract BfdPropertiesReadRequest mapBfdRequest(NetworkEndpoint source, NetworkEndpoint destination);

    @Mapping(target = "timestamp", ignore = true)
    @Mapping(target = "source", source = "source")
    @Mapping(target = "destination", source = "destination")
    @Mapping(target = "properties", source = "properties")
    public abstract BfdPropertiesWriteRequest mapBfdRequest(
            NetworkEndpoint source, NetworkEndpoint destination,
            org.openkilda.northbound.dto.v2.links.BfdProperties properties);

    /**
     * Convert {@link BfdPropertiesResponse} into {@link BfdPropertiesPayload}.
     */
    @Mapping(target = "intervalMs", source = "properties.interval")
    @Mapping(target = "multiplier", source = "properties.multiplier")
    public BfdPropertiesPayload mapResponse(BfdPropertiesResponse response) {
        org.openkilda.northbound.dto.v2.links.BfdProperties properties = map(response.getGoal());
        BfdPropertiesByEndpoint effectiveSource = new BfdPropertiesByEndpoint(
                response.getSource(), map(response.getEffectiveSource()),
                map(response.getEffectiveSource().getStatus()));
        BfdPropertiesByEndpoint effectiveDestination = new BfdPropertiesByEndpoint(
                response.getDestination(), map(response.getEffectiveDestination()),
                map(response.getEffectiveDestination().getStatus()));
        return new BfdPropertiesPayload(properties, effectiveSource, effectiveDestination);
    }

    /**
     * Convert {@link IslInfoData} into {@link LinkDto}.
     */
    public LinkDto mapResponse(IslInfoData source) {
        LinkDto target = new LinkDto();
        generatedMap(target, source);
        target.setPath(Arrays.asList(
                map(source.getSource()),
                map(source.getDestination())));
        return target;
    }

    @Mapping(target = "path", ignore = true)
    protected abstract void generatedMap(@MappingTarget LinkDto target, IslInfoData source);

    public Duration mapMillis(long millis) {
        return Duration.ofMillis(millis);
    }

    /**
     * Convert {@link Duration} into {@link long}.
     */
    public long mapMillis(Duration millis) {
        if (millis == null) {
            return 0;
        }
        return millis.toMillis();
    }

    public String toSwithId(SwitchId switchId) {
        return switchId.toString();
    }
}
