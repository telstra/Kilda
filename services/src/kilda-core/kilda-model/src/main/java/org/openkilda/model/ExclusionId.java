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

import static java.lang.String.format;

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

import java.io.Serializable;

/**
 * Represents an exclusion id allocated for a flow.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(exclude = {"entityId"})
@NodeEntity(label = "exclusion_id")
public class ExclusionId implements Serializable {
    private static final long serialVersionUID = 6022722903714656513L;

    // Hidden as needed for OGM only.
    @Id
    @GeneratedValue
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    private Long entityId;

    @NonNull
    @Property(name = "flow_id")
    private String flowId;

    @NonNull
    @Property(name = "id")
    private int id;

    // Hidden as used to imitate unique composite index for non-enterprise Neo4j versions.
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    @Property(name = "unique_index")
    @Index(unique = true)
    private String uniqueIndex;

    @Builder(toBuilder = true)
    public ExclusionId(@NonNull String flowId, int id) {
        this.flowId = flowId;
        this.id = id;
        calculateUniqueIndex();
    }

    private void calculateUniqueIndex() {
        uniqueIndex = format("%s_%d", flowId, id);
    }
}
