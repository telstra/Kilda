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
import org.projectfloodlight.openflow.types.IpProtocol;

/**
 * Utility class that converts OFlowStats from the switch to kilda known format for further processing.
 */
@Mapper
@Slf4j
public abstract class IpProtocolMapper {

    public static final IpProtocolMapper INSTANCE = Mappers.getMapper(IpProtocolMapper.class);

    private static final String TCP = "TCP";
    private static final String UDP = "UDP";

    /**
     * Convert {@link String} to {@link IpProtocol}.
     *
     * @param proto IP protocol in string representation.
     * @return result of transformation {@link IpProtocol}.
     */
    public IpProtocol convert(String proto) {
        if (TCP.equals(proto)) {
            return IpProtocol.TCP;
        } else if (UDP.equals(proto)) {
            return IpProtocol.UDP;
        } else {
            log.error("Unexpected ip protocol {}", proto);
            return IpProtocol.NONE;
        }
    }

    /**
     * Convert {@link IpProtocol} to {@link String}.
     *
     * @param proto IP protocol.
     * @return result of transformation {@link String}.
     */
    public String convert(IpProtocol proto) {
        if (IpProtocol.TCP.equals(proto)) {
            return TCP;
        } else if (IpProtocol.UDP.equals(proto)) {
            return UDP;
        } else {
            log.error("Unexpected ip protocol {}", proto);
            return null;
        }
    }
}
