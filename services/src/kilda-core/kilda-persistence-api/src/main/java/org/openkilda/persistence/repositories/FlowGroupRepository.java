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

import org.openkilda.model.FlowGroup;
import org.openkilda.model.GroupId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import java.util.Collection;
import java.util.Optional;

public interface FlowGroupRepository extends Repository<FlowGroup> {

    /**
     * Find group by Path Id.
     *
     * @param pathId path ID
     * @return a collection of {@link FlowGroup}
     */
    Collection<FlowGroup> findByPathId(PathId pathId);

    /**
     * Find a group id which is not assigned to any flow.
     *
     * @param switchId       the switch defines where the meter is applied on.
     * @param defaultGroupId the potential group to be checked first.
     * @return a meter id or {@link Optional#empty()} if no meter available.
     */
    Optional<GroupId> findUnassignedGroupId(SwitchId switchId, GroupId defaultGroupId);
}
