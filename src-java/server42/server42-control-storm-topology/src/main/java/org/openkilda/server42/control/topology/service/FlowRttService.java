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


package org.openkilda.server42.control.topology.service;

import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Flow;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class FlowRttService {

    private final IFlowCarrier carrier;
    private final FeatureTogglesRepository featureTogglesRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;
    private final FlowRepository flowRepository;

    public FlowRttService(IFlowCarrier carrier, PersistenceManager persistenceManager) {
        this.carrier = carrier;
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        flowRepository = repositoryFactory.createFlowRepository();
    }

    /**
     * Check if rtt global feature enabled and rtt enabled for switch then ask carrier to activate flow.
     *
     * @param flowId flow id
     * @param port switch customer port
     * @param vlan switch customer vlan id
     * @param isForward is endpoint forward
     */
    public void activateFlowMonitoring(String flowId, SwitchId switchId, Integer port, Integer vlan, Integer innerVlan,
                                       boolean isForward) {
        if (isFlowRttFeatureToggle() && isFlowRttFeatureEnabledFor(switchId)) {
            carrier.notifyActivateFlowMonitoring(flowId, switchId, port, vlan, innerVlan, isForward);
        } else {
            log.info("skip activation of flow RTT for flow: {} and switch:{}", flowId, switchId);
        }
    }

    /**
     * Activate monitoring for all existed flows on provided switch.
     *
     * @param switchId switch id
     */
    public void activateFlowMonitoringForSwitch(SwitchId switchId) {
        Map<Boolean, List<Flow>> flowByDirection =
                flowRepository.findByEndpointSwitch(switchId).stream()
                        .filter(f -> !f.isOneSwitchFlow())
                        .collect(Collectors.partitioningBy(
                                f -> f.getSrcSwitchId().equals(switchId)));


        flowByDirection.getOrDefault(true, Collections.emptyList())
                .forEach(flow -> carrier.notifyActivateFlowMonitoring(flow.getFlowId(),
                        switchId, flow.getSrcPort(), flow.getSrcVlan(), flow.getSrcInnerVlan(),
                        true));

        flowByDirection.getOrDefault(false, Collections.emptyList())
                .forEach(flow -> carrier.notifyActivateFlowMonitoring(flow.getFlowId(),
                        switchId, flow.getDestPort(), flow.getDestVlan(), flow.getDestInnerVlan(),
                        false));
    }

    /**
     * Send list with flow ids on provided switch.
     *
     * @param switchId switch id
     */
    public void sendFlowListOnSwitchCommand(SwitchId switchId) {
        Set<String> flowOnSwitch =
                flowRepository.findByEndpointSwitch(switchId).stream()
                        .filter(f -> !f.isOneSwitchFlow())
                        .map(Flow::getFlowId)
                        .collect(Collectors.toSet());

        carrier.sendListOfFlowBySwitchId(switchId, flowOnSwitch);
    }

    private boolean isFlowRttFeatureToggle() {
        return featureTogglesRepository.find().map(FeatureToggles::getServer42FlowRtt)
                .orElse(FeatureToggles.DEFAULTS.getServer42FlowRtt());
    }

    private boolean isFlowRttFeatureEnabledFor(SwitchId switchId) {
        return switchPropertiesRepository.findBySwitchId(switchId).map(SwitchProperties::isServer42FlowRtt)
                .orElse(false);
    }

}
