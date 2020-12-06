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

import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;

import java.util.List;
import java.util.Optional;

public interface PathSegmentRepository extends Repository<PathSegment> {
    void updateFailedStatus(FlowPath path, PathSegment segment, boolean failed);

    List<PathSegment> findByPathId(PathId pathId);

    /**
     * Add a segment and update the available bandwidth of the corresponding ISL.
     * Note: the method adds a segment as detached entity, which means the provided object is kept as is.
     * @param segment a segment to add.
     * @return the available bandwidth of the updated ISL.
     */
    Optional<Long> addSegmentAndUpdateIslAvailableBandwidth(PathSegment segment);
}
