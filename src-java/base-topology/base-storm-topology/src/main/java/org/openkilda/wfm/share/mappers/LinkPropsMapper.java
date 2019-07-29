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

import org.openkilda.messaging.model.LinkPropsDto;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.model.LinkProps;

import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Mapper
@Slf4j
public abstract class LinkPropsMapper {

    public static final LinkPropsMapper INSTANCE = Mappers.getMapper(LinkPropsMapper.class);

    /**
     * Convert {@link LinkProps} to {@link LinkPropsDto}.
     */
    public LinkPropsDto map(LinkProps linkProps) {
        if (linkProps == null) {
            return null;
        }

        Map<String, String> props = new HashMap<>();
        if (linkProps.getCost() != null) {
            props.put(LinkProps.COST_PROP_NAME, Integer.toString(linkProps.getCost()));
        }
        if (linkProps.getMaxBandwidth() != null) {
            props.put(LinkProps.MAX_BANDWIDTH_PROP_NAME, Long.toString(linkProps.getMaxBandwidth()));
        }

        Long created = Optional.ofNullable(linkProps.getTimeCreate()).map(Instant::toEpochMilli).orElse(null);
        Long modified = Optional.ofNullable(linkProps.getTimeModify()).map(Instant::toEpochMilli).orElse(null);

        NetworkEndpoint source = new NetworkEndpoint(linkProps.getSrcSwitchId(), linkProps.getSrcPort());
        NetworkEndpoint destination = new NetworkEndpoint(linkProps.getDstSwitchId(), linkProps.getDstPort());

        return new LinkPropsDto(source, destination, props, created, modified);
    }

    /**
     * Convert {@link LinkPropsDto} to {@link LinkProps}.
     */
    public LinkProps map(LinkPropsDto linkProps) {
        if (linkProps == null) {
            return null;
        }

        LinkProps dbLinkProps = LinkProps.builder()
                .srcPort(linkProps.getSource().getPortNumber())
                .srcSwitchId(linkProps.getSource().getDatapath())
                .dstPort(linkProps.getDest().getPortNumber())
                .dstSwitchId(linkProps.getDest().getDatapath())
                .build();

        if (linkProps.getProps() != null) {
            for (Map.Entry<String, String> entry : linkProps.getProps().entrySet()) {
                switch (entry.getKey()) {
                    case LinkProps.COST_PROP_NAME:
                        dbLinkProps.setCost(Integer.valueOf(entry.getValue()));
                        break;
                    case LinkProps.MAX_BANDWIDTH_PROP_NAME:
                        dbLinkProps.setMaxBandwidth(Long.valueOf(entry.getValue()));
                        break;
                    default:
                        log.warn("Unsupported IslProps properties: {}", entry);
                }
            }
        }

        return dbLinkProps;
    }
}
