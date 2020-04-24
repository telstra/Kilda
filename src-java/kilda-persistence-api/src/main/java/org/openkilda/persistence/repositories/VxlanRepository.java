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
import org.openkilda.model.Vxlan;

import java.util.Collection;
import java.util.Optional;

public interface VxlanRepository extends Repository<Vxlan> {
    Collection<Vxlan> findAll();

    Collection<Vxlan> findByPathId(PathId pathId, PathId oppositePathId);

    /**
     * Find the maximum among assigned vxlans.
     *
     * @return the maximum vxvlan value or {@link Optional#empty()} if there's no assigned vxlan.
     */
    Optional<Integer> findMaximumAssignedVxlan();

    /**
     * Find the first (lowest by value) vxvlan which is not assigned to any flow.
     *
     * @param startVxlan the lowest value for a potential vxlan.
     * @return the found vxvlan
     */
    int findFirstUnassignedVxlan(int startVxlan);
}
