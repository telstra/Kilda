/* Copyright 2018 Telstra Open Source
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

import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;

import java.util.Collection;
import java.util.Optional;

public interface SwitchRepository extends Repository<Switch> {
    Collection<Switch> findAll();

    boolean exists(SwitchId switchId);

    Collection<Switch> findActive();

    Optional<Switch> findById(SwitchId switchId);

    Collection<Switch> findSwitchesInFlowPathByFlowId(String flowId);

    /**
     * Check the given entity is in actual state with the repository and return reloaded instance if not.
     *
     * @deprecated To be removed as does nothing in the current implementation.
     */
    @Deprecated
    Switch reload(Switch entity);

    /**
     * Put an exclusive lock on the given switch entities to avoid concurrent modifications and deadlocks.
     *
     * @deprecated To be removed as does nothing in the current implementation.
     */
    @Deprecated
    void lockSwitches(Switch... switches);

    boolean removeIfNoDependant(Switch sw);
}
