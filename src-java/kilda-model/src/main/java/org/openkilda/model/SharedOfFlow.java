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

import static org.neo4j.ogm.annotation.Relationship.INCOMING;

import org.openkilda.model.cookie.SharedOfFlowCookieSchema;

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
import org.neo4j.ogm.annotation.Index;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Property;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.typeconversion.Convert;
import org.neo4j.ogm.typeconversion.InstantStringConverter;

import java.io.Serializable;
import java.time.Instant;

@Data
@NoArgsConstructor
@EqualsAndHashCode(of = {"uniqueIndex"})
@NodeEntity(label = "shared_of_flow")
public class SharedOfFlow implements Serializable {
    // Hidden as needed for OGM only.
    @Id
    @GeneratedValue
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    private Long entityId;

    @NonNull
    @Relationship(type = "owns", direction = INCOMING)
    private Switch switchObj;

    @NonNull
    @Convert(graphPropertyType = Long.class)
    private SharedOfFlowCookieSchema cookie;

    @Convert(graphPropertyType = String.class)
    private SharedOfFlowType type;

    @Property(name = "time_create")
    @Convert(InstantStringConverter.class)
    private Instant timeCreate;

    @Property(name = "time_modify")
    @Convert(InstantStringConverter.class)
    private Instant timeModify;

    @Property(name = "unique_index")
    @Index(unique = true)
    @Setter(AccessLevel.NONE)
    private String uniqueIndex;

    @Builder(toBuilder = true)
    public SharedOfFlow(@NonNull Switch switchObj, @NonNull SharedOfFlowCookieSchema cookie, SharedOfFlowType type) {
        this.switchObj = switchObj;
        this.cookie = cookie;
        this.type = type;
        calculateUniqueIndex();
    }

    private void calculateUniqueIndex() {
        uniqueIndex = String.format("%s_%s", switchObj.getSwitchId(), cookie);
    }

    public enum SharedOfFlowType {
        INGRESS_OUTER_VLAN_MATCH
    }
}
