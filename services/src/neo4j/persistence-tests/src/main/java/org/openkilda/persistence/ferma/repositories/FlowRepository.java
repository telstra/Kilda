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


import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.model.Flow;

import java.util.Collection;
import java.util.Optional;

public interface FlowRepository {
    Collection<Flow> findAll();

    long countFlows();

    boolean exists(String flowId);

    Optional<Flow> findById(String flowId);

    Collection<Flow> findByGroupId(String flowGroupId);

    Collection<String> findFlowsIdByGroupId(String flowGroupId);

    Collection<Flow> findWithPeriodicPingsEnabled();

    Collection<Flow> findByEndpoint(SwitchId switchId, int port);

    Collection<Flow> findByEndpointSwitch(SwitchId switchId);

    Collection<Flow> findDownFlows();

    Flow create(Flow entity);

    void delete(Flow entity);

    void updateStatus(String flowId, FlowStatus flowStatus);
}
