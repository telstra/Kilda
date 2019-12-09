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

package org.openkilda.model;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Property;
import org.neo4j.ogm.annotation.typeconversion.Convert;
import org.neo4j.ogm.typeconversion.InstantStringConverter;

import java.time.Instant;

@Data
@NoArgsConstructor
@EqualsAndHashCode(exclude = { "entityId", "timeCreate" })
@NodeEntity(label = "application_rule")
public class ApplicationRule {

    // Hidden as needed for OGM only.
    @Id
    @GeneratedValue
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    private Long entityId;

    @NonNull
    @Property("flow_id")
    private String flowId;

    @NonNull
    @Convert(graphPropertyType = String.class)
    @Property(name = "switch_id")
    private SwitchId switchId;

    @NonNull
    @Convert(graphPropertyType = Long.class)
    private Cookie cookie;

    @Property("src_ip")
    private String srcIp;

    @Property("src_port")
    private int srcPort;

    @Property("dst_ip")
    private String dstIp;

    @Property("dst_port")
    private int dstPort;

    @Property("proto")
    private String proto;

    @Property("eth_type")
    private String ethType;

    @Convert(graphPropertyType = Long.class)
    private Metadata metadata;

    @Property(name = "time_create")
    @Convert(InstantStringConverter.class)
    private Instant timeCreate;

    @Property("expiration_timeout")
    private int expirationTimeout;

    @Builder(toBuilder = true)
    public ApplicationRule(String flowId, SwitchId switchId, Cookie cookie, String srcIp, int srcPort, String dstIp,
                           int dstPort, String proto, String ethType, Metadata metadata, Instant timeCreate,
                           int expirationTimeout) {
        this.flowId = flowId;
        this.switchId = switchId;
        this.cookie = cookie;
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.dstIp = dstIp;
        this.dstPort = dstPort;
        this.proto = proto;
        this.ethType = ethType;
        this.metadata = metadata;
        this.timeCreate = timeCreate;
        this.expirationTimeout = expirationTimeout;
    }
}
