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

import org.openkilda.model.ExclusionId;

import java.util.Collection;
import java.util.Optional;

public interface ExclusionIdRepository extends Repository<ExclusionId> {
    Collection<ExclusionId> findByFlowId(String flowId);

    Optional<ExclusionId> find(String flowId, int exclusionId);

    /**
     * Find an exclusion id which is not assigned to any flow.
     * Use the provided defaultExclusionId as the first candidate.
     *
     * @param flowId             the flow defines where the exclusion id is applied on.
     * @param defaultExclusionId the potential exclusion id to be checked first.
     * @return an exclusion id or {@link Optional#empty()} if no exclusion id available.
     */
    Optional<Integer> findUnassignedExclusionId(String flowId, int defaultExclusionId);
}
