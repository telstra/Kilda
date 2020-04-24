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

package org.openkilda.persistence.repositories;

import org.openkilda.model.PathId;
import org.openkilda.model.TransitVlan;

import java.util.Collection;
import java.util.Optional;

public interface TransitVlanRepository extends Repository<TransitVlan> {
    Collection<TransitVlan> findAll();

    Collection<TransitVlan> findByPathId(PathId pathId, PathId oppositePathId);

    Optional<TransitVlan> findByPathId(PathId pathId);

    Optional<TransitVlan> findByVlan(int vlan);

    /**
     * Find the maximum among assigned transit vlans.
     *
     * @return the maximum vlan value or {@link Optional#empty()} if there's no assigned transit vlan.
     */
    Optional<Integer> findMaximumAssignedVlan();

    /**
     * Find the first (lowest by value) transit vlan which is not assigned to any flow.
     *
     * @param startTransitVlan the lowest value for a potential transit vlan.
     * @return the found transit vlan
     */
    int findFirstUnassignedVlan(int startTransitVlan);
}
