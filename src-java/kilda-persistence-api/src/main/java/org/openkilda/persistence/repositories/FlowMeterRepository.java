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

import org.openkilda.model.FlowMeter;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import java.util.Collection;
import java.util.Optional;

public interface FlowMeterRepository extends Repository<FlowMeter> {
    Collection<FlowMeter> findAll();

    /**
     * Find meters by Path Id.
     *
     * @param pathId path ID
     * @return a collection of {@link FlowMeter}
     */
    Optional<FlowMeter> findByPathId(PathId pathId);

    /**
     * Find the maximum among assigned meter IDs.
     *
     * @param switchId the switch defines where the meter is applied on.
     * @return the maximum meter ID or {@link Optional#empty()} if there's no assigned meter.
     */
    Optional<MeterId> findMaximumAssignedMeter(SwitchId switchId);

    /**
     * Find the first (lowest by value) meter ID which is not assigned to any flow.
     *
     * @param switchId the switch defines where the meter is applied on.
     * @param startMeterId the lowest value for a potential meter ID.
     * @return the found meter ID
     */
    MeterId findFirstUnassignedMeter(SwitchId switchId, MeterId startMeterId);
}
