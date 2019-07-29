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

package org.openkilda.persistence.ferma.repositories;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.model.Switch;

import java.util.Collection;
import java.util.Optional;

public interface SwitchRepository {
    Collection<Switch> findAll();

    boolean exists(SwitchId switchId);

    Optional<Switch> findById(SwitchId switchId);

    Switch create(Switch entity);

    void delete(Switch entity);
}
