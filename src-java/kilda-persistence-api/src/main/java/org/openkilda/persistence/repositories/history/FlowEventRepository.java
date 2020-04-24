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

package org.openkilda.persistence.repositories.history;

import org.openkilda.model.history.FlowEvent;
import org.openkilda.persistence.repositories.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FlowEventRepository extends Repository<FlowEvent> {
    boolean existsByTaskId(String taskId);

    Optional<FlowEvent> findByTaskId(String taskId);

    List<FlowEvent> findByFlowIdAndTimeFrame(String flowId, Instant timeFrom, Instant timeTo);
}
