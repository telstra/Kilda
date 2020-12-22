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

package org.openkilda.wfm.topology.switchmanager.service.impl;

import static java.lang.String.format;

import org.openkilda.messaging.command.switches.SwitchRulesDeleteRequest;
import org.openkilda.messaging.command.switches.SwitchRulesInstallRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.switches.SwitchRulesResponse;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.FlowPath;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.topology.switchmanager.service.SwitchManagerCarrier;
import org.openkilda.wfm.topology.switchmanager.service.SwitchRuleService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class SwitchRuleServiceImpl implements SwitchRuleService {

    private SwitchManagerCarrier carrier;
    private FlowPathRepository flowPathRepository;
    private SwitchPropertiesRepository switchPropertiesRepository;
    private FeatureTogglesRepository featureTogglesRepository;
    private IslRepository islRepository;
    private SwitchRepository switchRepository;

    public SwitchRuleServiceImpl(SwitchManagerCarrier carrier, RepositoryFactory repositoryFactory) {
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        islRepository = repositoryFactory.createIslRepository();
        switchRepository = repositoryFactory.createSwitchRepository();
        this.carrier = carrier;
    }

    @Override
    public void deleteRules(String key, SwitchRulesDeleteRequest data) {
        SwitchId switchId = data.getSwitchId();
        if (!switchRepository.exists(switchId)) {
            ErrorData errorData = new ErrorData(ErrorType.NOT_FOUND, format("Switch %s not found", switchId),
                    "Error when deleting switch rules");
            ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);

            carrier.response(key, errorMessage);
            return;
        }
        Optional<SwitchProperties> switchProperties = switchPropertiesRepository.findBySwitchId(switchId);
        boolean server42FeatureToggle = featureTogglesRepository.find()
                .map(FeatureToggles::getServer42FlowRtt).orElse(false);
        data.setServer42FlowRttFeatureToggle(server42FeatureToggle);

        if (switchProperties.isPresent()) {
            data.setMultiTable(switchProperties.get().isMultiTable());
            data.setSwitchLldp(switchProperties.get().isSwitchLldp());
            data.setSwitchArp(switchProperties.get().isSwitchArp());
            data.setServer42FlowRttSwitchProperty(switchProperties.get().isServer42FlowRtt());
            data.setServer42Port(switchProperties.get().getServer42Port());
            data.setServer42Vlan(switchProperties.get().getServer42Vlan());
            data.setServer42MacAddress(switchProperties.get().getServer42MacAddress());
            Collection<FlowPath> flowPaths = flowPathRepository.findBySrcSwitch(switchId);
            List<Integer> flowPorts = new ArrayList<>();
            Set<Integer> flowLldpPorts = new HashSet<>();
            Set<Integer> flowArpPorts = new HashSet<>();
            Set<Integer> server42FlowPorts = new HashSet<>();
            fillFlowPorts(switchProperties.get(), flowPaths, flowPorts, flowLldpPorts, flowArpPorts, server42FlowPorts,
                    server42FeatureToggle && switchProperties.get().isServer42FlowRtt());

            data.setFlowPorts(flowPorts);
            data.setFlowLldpPorts(flowLldpPorts);
            data.setFlowArpPorts(flowArpPorts);
            data.setServer42FlowRttPorts(server42FlowPorts);
            List<Integer> islPorts = islRepository.findBySrcSwitch(switchId).stream()
                    .map(isl -> isl.getSrcPort())
                    .collect(Collectors.toList());
            data.setIslPorts(islPorts);
        }
        carrier.sendCommandToSpeaker(key, data);
    }

    @Override
    public void installRules(String key, SwitchRulesInstallRequest data) {
        SwitchId switchId = data.getSwitchId();
        if (!switchRepository.exists(switchId)) {
            ErrorData errorData = new ErrorData(ErrorType.NOT_FOUND, format("Switch %s not found", switchId),
                    "Error when installing switch rules");
            ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);

            carrier.response(key, errorMessage);
            return;
        }
        Optional<SwitchProperties> switchProperties = switchPropertiesRepository.findBySwitchId(switchId);

        boolean server42FeatureToggle = featureTogglesRepository.find()
                .map(FeatureToggles::getServer42FlowRtt).orElse(false);
        data.setServer42FlowRttFeatureToggle(server42FeatureToggle);

        if (switchProperties.isPresent()) {
            data.setMultiTable(switchProperties.get().isMultiTable());
            data.setSwitchLldp(switchProperties.get().isSwitchLldp());
            data.setSwitchArp(switchProperties.get().isSwitchArp());
            data.setServer42FlowRttSwitchProperty(switchProperties.get().isServer42FlowRtt());
            data.setServer42Port(switchProperties.get().getServer42Port());
            data.setServer42Vlan(switchProperties.get().getServer42Vlan());
            data.setServer42MacAddress(switchProperties.get().getServer42MacAddress());
            Collection<FlowPath> flowPaths = flowPathRepository.findBySrcSwitch(switchId);
            List<Integer> flowPorts = new ArrayList<>();
            Set<Integer> flowLldpPorts = new HashSet<>();
            Set<Integer> flowArpPorts = new HashSet<>();
            Set<Integer> server42FlowPorts = new HashSet<>();
            fillFlowPorts(switchProperties.get(), flowPaths, flowPorts, flowLldpPorts, flowArpPorts, server42FlowPorts,
                    server42FeatureToggle && switchProperties.get().isServer42FlowRtt());
            data.setFlowPorts(flowPorts);
            data.setFlowLldpPorts(flowLldpPorts);
            data.setFlowArpPorts(flowArpPorts);
            data.setServer42FlowRttPorts(server42FlowPorts);
            List<Integer> islPorts = islRepository.findBySrcSwitch(switchId).stream()
                    .map(isl -> isl.getSrcPort())
                    .collect(Collectors.toList());
            data.setIslPorts(islPorts);
        }
        carrier.sendCommandToSpeaker(key, data);
    }

    private void fillFlowPorts(SwitchProperties switchProperties, Collection<FlowPath> flowPaths,
                               List<Integer> flowPorts, Set<Integer> flowLldpPorts, Set<Integer> flowArpPorts,
                               Set<Integer> server42FlowPorts, boolean server42Rtt) {
        for (FlowPath flowPath : flowPaths) {
            if (flowPath.isForward()) {
                if (flowPath.isSrcWithMultiTable()) {
                    flowPorts.add(flowPath.getFlow().getSrcPort());
                    if (server42Rtt && !flowPath.getFlow().isOneSwitchFlow()) {
                        server42FlowPorts.add(flowPath.getFlow().getSrcPort());
                    }
                }
                if (flowPath.getFlow().getDetectConnectedDevices().isSrcLldp()
                        || switchProperties.isSwitchLldp()) {
                    flowLldpPorts.add(flowPath.getFlow().getSrcPort());
                }
                if (flowPath.getFlow().getDetectConnectedDevices().isSrcArp()
                        || switchProperties.isSwitchArp()) {
                    flowArpPorts.add(flowPath.getFlow().getSrcPort());
                }
            } else {
                if (flowPath.isDestWithMultiTable()) {
                    flowPorts.add(flowPath.getFlow().getDestPort());
                    if (server42Rtt && !flowPath.getFlow().isOneSwitchFlow()) {
                        server42FlowPorts.add(flowPath.getFlow().getDestPort());
                    }
                }
                if (flowPath.getFlow().getDetectConnectedDevices().isDstLldp()
                        || switchProperties.isSwitchLldp()) {
                    flowLldpPorts.add(flowPath.getFlow().getDestPort());
                }
                if (flowPath.getFlow().getDetectConnectedDevices().isDstArp()
                        || switchProperties.isSwitchArp()) {
                    flowArpPorts.add(flowPath.getFlow().getDestPort());
                }
            }
        }
    }

    @Override
    public void rulesResponse(String key, SwitchRulesResponse response) {
        carrier.cancelTimeoutCallback(key);
        InfoMessage message = new InfoMessage(response, System.currentTimeMillis(), key);

        carrier.response(key, message);
    }
}
