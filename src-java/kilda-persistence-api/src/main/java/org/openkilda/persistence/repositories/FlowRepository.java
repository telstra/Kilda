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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowFilter;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface FlowRepository extends Repository<Flow> {
    long countFlows();

    /**
     * Fetches all flows.
     */
    Collection<Flow> findAll();

    boolean exists(String flowId);

    Optional<Flow> findById(String flowId);

    Collection<Flow> findByGroupId(String flowGroupId);

    Collection<String> findFlowsIdByGroupId(String flowGroupId);

    Collection<Flow> findWithPeriodicPingsEnabled();

    Collection<Flow> findByEndpoint(SwitchId switchId, int port);

    /**
     * Find flow by endpoint (SwitchId, port and vlan).
     */
    Optional<Flow> findByEndpointAndVlan(SwitchId switchId, int port, int vlan);

    /**
     * Find flow by SwitchId, input port and output vlan.
     */
    Optional<Flow> findOneSwitchFlowBySwitchIdInPortAndOutVlan(SwitchId switchId, int inPort, int outVlan);

    Collection<Flow> findOneSwitchFlows(SwitchId switchId);

    Collection<Flow> findByEndpointWithMultiTableSupport(SwitchId switchId, int port);

    Collection<String> findFlowsIdsByEndpointWithMultiTableSupport(SwitchId switchId, int port);

    Collection<String> findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(SwitchId switchId, int port);

    Collection<String> findFlowIdsForMultiSwitchFlowsBySwitchIdAndVlanWithMultiTableSupport(
            SwitchId switchId, int otherVlan);

    Collection<Flow> findByEndpointSwitch(SwitchId switchId);

    Collection<Flow> findByEndpointSwitchWithMultiTableSupport(SwitchId switchId);

    Collection<Flow> findByEndpointSwitchWithEnabledLldp(SwitchId switchId);

    Collection<Flow> findByEndpointSwitchWithEnabledArp(SwitchId switchId);

    Collection<Flow> findInactiveFlows();

    /**
     * Find flows by flow status.
     * <p/>
     * IMPORTANT: the method completes the flow entity only with Switch objects (Flow paths will be null)
     */
    Collection<Flow> findByFlowFilter(FlowFilter flowFilter);

    Optional<String> getOrCreateFlowGroupId(String flowId);

    void updateStatus(String flowId, FlowStatus flowStatus);

    void updateStatus(String flowId, FlowStatus flowStatus, String flowStatusInfo);

    void updateStatusInfo(String flowId, String flowStatusInfo);

    /**
     * Flow in "IN_PROGRESS" status can be switched to other status only inside flow CRUD handlers topology. All other
     * components must use this method, which guarantee safety such flows status.
     */
    void updateStatusSafe(Flow flow, FlowStatus flowStatus, String flowStatusInfo);

    long computeFlowsBandwidthSum(Set<String> flowIds);

    Optional<Flow> remove(String flowId);

    Collection<Flow> findLoopedByFlowIdAndLoopSwitchId(String flowId, SwitchId switchId);

    Collection<Flow> findLoopedByLoopSwitchId(SwitchId switchId);
}
