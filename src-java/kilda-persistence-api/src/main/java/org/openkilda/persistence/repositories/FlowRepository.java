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
import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.FetchStrategy;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface FlowRepository extends Repository<Flow> {
    long countFlows();

    /**
     * Fetches all flows.
     * <p/>
     * IMPORTANT: the method doesn't complete the flow and flow path entities with related path segments!
     */
    Collection<Flow> findAll();

    boolean exists(String flowId);

    Optional<Flow> findById(String flowId);

    Optional<Flow> findById(String flowId, FetchStrategy fetchStrategy);

    /**
     * Find flow by flow ID.
     * <p/>
     * IMPORTANT: the method completes the flow entity only with Switch objects (Flow paths will be null)
     */
    Optional<Flow> findByIdWithEndpoints(String flowId);

    Collection<Flow> findByGroupId(String flowGroupId);

    Collection<String> findFlowsIdByGroupId(String flowGroupId);

    Collection<Flow> findWithPeriodicPingsEnabled();

    Collection<Flow> findByEndpoint(SwitchId switchId, int port);

    /**
     * Find flow by endpoint (SwitchId, port and vlan).
     * <p/>
     * IMPORTANT: the method completes the flow entity only with Switch objects (Flow paths will be null)
     */
    Optional<Flow> findByEndpointAndVlan(SwitchId switchId, int port, int vlan);

    /**
     * Find one switch flow by SwitchId, input port and output vlan.
     * <p/>
     * IMPORTANT: the method completes the flow entity only with Switch objects (Flow paths will be null)
     */
    Optional<Flow> findOneSwitchFlowBySwitchIdInPortAndOutVlans(
            SwitchId switchId, int inputPort, int outputOuterVlan, int outputInnerVlan);

    Collection<Flow> findOneSwitchFlows(SwitchId switchId);

    Collection<Flow> findByEndpointWithMultiTableSupport(SwitchId switchId, int port);

    Collection<String> findFlowsIdsByEndpointWithMultiTableSupport(SwitchId switchId, int port);

    Collection<String> findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(SwitchId switchId, int port);

    Collection<Flow> findByEndpointSwitch(SwitchId switchId);

    Collection<Flow> findByEndpointSwitchWithMultiTableSupport(SwitchId switchId);

    Collection<Flow> findByEndpointSwitchWithEnabledLldp(SwitchId switchId);

    Collection<Flow> findByEndpointSwitchWithEnabledArp(SwitchId switchId);

    Collection<Flow> findDownFlows();

    Optional<String> getOrCreateFlowGroupId(String flowId);

    void updateStatus(String flowId, FlowStatus flowStatus);

    void updateStatus(String flowId, FlowStatus flowStatus, String flowStatusInfo);

    void updateStatusInfo(String flowId, String flowStatusInfo);

    /**
     * Flow in "IN_PROGRESS" status can be switched to other status only inside flow CRUD handlers topology. All other
     * components must use this method, which guarantee safety such flows status.
     */
    void updateStatusSafe(String flowId, FlowStatus flowStatus, String flowStatusInfo);

    long computeFlowsBandwidthSum(Set<String> flowIds);
}
