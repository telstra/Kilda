/* Copyright 2020 Telstra Open Source
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
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface FlowPathRepository extends Repository<FlowPath> {
    Collection<FlowPath> findAll();

    Optional<FlowPath> findById(PathId pathId);

    Optional<FlowPath> findByFlowIdAndCookie(String flowId, FlowSegmentCookie flowCookie);

    Collection<FlowPath> findByFlowId(String flowId);

    Collection<FlowPath> findByFlowGroupId(String flowGroupId);

    Collection<PathId> findPathIdsByFlowGroupId(String flowGroupId);

    Collection<PathId> findActualPathIdsByFlowIds(Set<String> flowIds);

    /**
     * Finds paths that starts with passed {@param switchId} switch.
     * NB. This method does not return protected paths with src {@param switchId} switch.
     *
     * @param switchId the src switch
     * @return collection of paths
     */
    default Collection<FlowPath> findBySrcSwitch(SwitchId switchId) {
        return findBySrcSwitch(switchId, false);
    }

    /**
     * Finds paths that starts with passed {@param switchId} switch.
     *
     * @param switchId the src switch
     * @param includeProtected if true, protected paths with src {@param switchId} switch will not be returned.
     * @return collection of paths
     */
    Collection<FlowPath> findBySrcSwitch(SwitchId switchId, boolean includeProtected);

    /**
     * Finds paths that have passed {@param switchId} switch in endpoints.
     * NB. This method does not return protected paths with src {@param switchId} switch.
     *
     * @param switchId the endpoint switch
     * @return collection of paths
     */
    default Collection<FlowPath> findByEndpointSwitch(SwitchId switchId) {
        return findByEndpointSwitch(switchId, false);
    }

    /**
     * Finds paths that have passed {@param switchId} switch in endpoints.
     *
     * @param switchId the endpoint switch
     * @param includeProtected if true, protected paths with src {@param switchId} switch will not be returned.
     * @return collection of paths
     */
    Collection<FlowPath> findByEndpointSwitch(SwitchId switchId, boolean includeProtected);

    Collection<FlowPath> findBySegmentSwitch(SwitchId switchId);

    Collection<FlowPath> findInactiveBySegmentSwitch(SwitchId switchId);

    /**
     * Finds flow paths which segments are goes through the switch in a multi-table mode.
     * @param switchId the endpoint switch
     * @param multiTable mode
     * @return collection of patch
     */
    Collection<FlowPath> findBySegmentSwitchWithMultiTable(SwitchId switchId, boolean multiTable);

    Collection<FlowPath> findBySegmentDestSwitch(SwitchId switchId);

    Collection<FlowPath> findWithPathSegment(SwitchId srcSwitchId, int srcPort,
                                             SwitchId dstSwitchId, int dstPort);

    Collection<FlowPath> findBySegmentEndpoint(SwitchId switchId, int port);

    long getUsedBandwidthBetweenEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort);

    void updateStatus(PathId pathId, FlowPathStatus pathStatus);

    Optional<FlowPath> remove(PathId pathId);
}
