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

package org.openkilda.floodlight.converter;

import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.projectfloodlight.openflow.types.EthType;

/**
 * Utility class that converts OFlowStats from the switch to kilda known format for further processing.
 */
@Mapper
@Slf4j
public abstract class EthTypeMapper {

    public static final EthTypeMapper INSTANCE = Mappers.getMapper(EthTypeMapper.class);

    private static final String IPv4 = "IPv4";

    /**
     * Convert {@link String} to {@link EthType}.
     *
     * @param ethType eth type in string representation.
     * @return result of transformation {@link EthType}.
     */
    public EthType convert(String ethType) {
        if (IPv4.equals(ethType)) {
            return EthType.IPv4;
        } else {
            log.error("Unexpected ethernet type {}", ethType);
            return EthType.NONE;
        }
    }

    /**
     * Convert {@link EthType} to {@link String}.
     *
     * @param ethType eth type.
     * @return result of transformation {@link String}.
     */
    public String convert(EthType ethType) {
        if (EthType.IPv4.equals(ethType)) {
            return IPv4;
        } else {
            log.error("Unexpected ethernet type {}", ethType);
            return null;
        }
    }
}
