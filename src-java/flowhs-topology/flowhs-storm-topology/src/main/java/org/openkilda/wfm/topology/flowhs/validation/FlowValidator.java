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

package org.openkilda.wfm.topology.flowhs.validation;

import static java.lang.String.format;

import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks whether flow can be created and has no conflicts with already created ones.
 */
public class FlowValidator {

    private final FlowRepository flowRepository;
    private final SwitchRepository switchRepository;
    private final IslRepository islRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;

    public FlowValidator(FlowRepository flowRepository, SwitchRepository switchRepository,
                         IslRepository islRepository, SwitchPropertiesRepository switchPropertiesRepository) {
        this.flowRepository = flowRepository;
        this.switchRepository = switchRepository;
        this.islRepository = islRepository;
        this.switchPropertiesRepository = switchPropertiesRepository;
    }

    /**
     * Validates the specified flow.
     *
     * @param flow a flow to be validated.
     * @throws InvalidFlowException is thrown if a violation is found.
     */
    public void validate(RequestedFlow flow) throws InvalidFlowException, UnavailableFlowEndpointException {
        checkFlags(flow);
        checkBandwidth(flow);
        checkFlowForIslConflicts(flow);
        checkFlowForEndpointConflicts(flow);
        checkOneSwitchFlowHasNoConflicts(flow);
        checkSwitchesExistsAndActive(flow);
        checkSwitchesSupportLldpAndArpIfNeeded(flow);

        if (StringUtils.isNotBlank(flow.getDiverseFlowId())) {
            checkDiverseFlow(flow);
        }
    }

    @VisibleForTesting
    void checkBandwidth(RequestedFlow flow) throws InvalidFlowException {
        if (flow.getBandwidth() < 0) {
            throw new InvalidFlowException(
                    format("The flow '%s' has invalid bandwidth %d provided.",
                            flow.getFlowId(),
                            flow.getBandwidth()),
                    ErrorType.DATA_INVALID);
        }
    }

    @VisibleForTesting
    void checkFlowForIslConflicts(RequestedFlow requestedFlow) throws InvalidFlowException {
        // Check the source
        if (!islRepository.findByEndpoint(requestedFlow.getSrcSwitch(),
                requestedFlow.getSrcPort()).isEmpty()) {
            String errorMessage = format("The port %d on the switch '%s' is occupied by an ISL.",
                    requestedFlow.getSrcPort(), requestedFlow.getSrcSwitch());
            throw new InvalidFlowException(errorMessage, ErrorType.PARAMETERS_INVALID);
        }

        // Check the destination
        if (!islRepository.findByEndpoint(requestedFlow.getDestSwitch(), requestedFlow.getDestPort()).isEmpty()) {
            String errorMessage = format("The port %d on the switch '%s' is occupied by an ISL.",
                    requestedFlow.getDestPort(), requestedFlow.getDestSwitch());
            throw new InvalidFlowException(errorMessage, ErrorType.PARAMETERS_INVALID);
        }
    }

    /**
     * Checks a flow for endpoints' conflicts.
     *
     * @param flow a flow to be validated.
     * @throws InvalidFlowException is thrown in a case when flow endpoints conflict with existing flows.
     */
    @VisibleForTesting
    void checkFlowForEndpointConflicts(RequestedFlow flow) throws InvalidFlowException {
        checkEndpoint(flow.getFlowId(), flow.getSrcSwitch(), flow.getSrcPort(), flow.getSrcVlan(), true);
        checkEndpoint(flow.getFlowId(), flow.getDestSwitch(), flow.getDestPort(), flow.getDestVlan(), false);
    }

    private void checkEndpoint(String flowId, SwitchId switchId, int portNo, int vlanId, boolean isSource)
            throws InvalidFlowException {
        Collection<Flow> conflicts = flowRepository.findByEndpoint(switchId, portNo);
        Optional<Flow> conflictOnSource = conflicts.stream()
                .filter(flow -> !flowId.equals(flow.getFlowId()))
                .filter(flow -> (flow.getSrcSwitchId().equals(switchId)
                        && flow.getSrcPort() == portNo
                        && (flow.getSrcVlan() == vlanId)))
                .findAny();
        if (conflictOnSource.isPresent()) {
            Flow existingFlow = conflictOnSource.get();
            String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                            + "Details: "
                            + "requested flow '%s' "
                            + (isSource ? "source" : "destination")
                            + ": switch=%s port=%d vlan=%d, "
                            + "existing flow '%s' source: switch=%s port=%d vlan=%d",
                    flowId, existingFlow.getFlowId(),
                    flowId, switchId, portNo, vlanId,
                    existingFlow.getFlowId(),
                    existingFlow.getSrcSwitchId().toString(),
                    existingFlow.getSrcPort(), existingFlow.getSrcVlan());
            throw new InvalidFlowException(errorMessage, ErrorType.ALREADY_EXISTS);
        }

        Optional<Flow> conflictOnDest = conflicts.stream()
                .filter(flow -> !flowId.equals(flow.getFlowId()))
                .filter(flow -> flow.getDestSwitchId().equals(switchId)
                        && flow.getDestPort() == portNo
                        && (flow.getDestVlan() == vlanId))
                .findAny();
        if (conflictOnDest.isPresent()) {
            Flow existingFlow = conflictOnDest.get();
            String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                            + "Details: "
                            + "requested flow '%s' "
                            + (isSource ? "source" : "destination")
                            + ": switch=%s port=%d vlan=%d, "
                            + "existing flow '%s' destination: switch=%s port=%d vlan=%d",
                    flowId, existingFlow.getFlowId(),
                    flowId, switchId.toString(), portNo, vlanId,
                    existingFlow.getFlowId(),
                    existingFlow.getDestSwitchId().toString(),
                    existingFlow.getDestPort(), existingFlow.getDestVlan());
            throw new InvalidFlowException(errorMessage, ErrorType.ALREADY_EXISTS);
        }
    }

    /**
     * Ensure switches are exists.
     *
     * @param flow a flow to be validated.
     * @throws UnavailableFlowEndpointException if switch not found.
     */
    @VisibleForTesting
    void checkSwitchesExistsAndActive(RequestedFlow flow) throws UnavailableFlowEndpointException {
        final SwitchId sourceId = flow.getSrcSwitch();
        final SwitchId destinationId = flow.getDestSwitch();

        boolean sourceSwitchAvailable;
        boolean destinationSwitchAvailable;

        if (Objects.equals(sourceId, destinationId)) {
            Optional<Switch> sw = switchRepository.findById(sourceId);
            sourceSwitchAvailable = destinationSwitchAvailable = sw.map(Switch::isActive)
                    .orElse(false);
        } else {
            sourceSwitchAvailable = switchRepository.findById(sourceId)
                    .map(Switch::isActive)
                    .orElse(false);
            destinationSwitchAvailable = switchRepository.findById(destinationId)
                    .map(Switch::isActive)
                    .orElse(false);
        }

        if (!sourceSwitchAvailable && !destinationSwitchAvailable) {
            throw new UnavailableFlowEndpointException(
                    String.format("Source switch %s and Destination switch %s are not connected to the controller",
                            sourceId, destinationId));
        } else if (!sourceSwitchAvailable) {
            throw new UnavailableFlowEndpointException(
                    String.format("Source switch %s is not connected to the controller", sourceId));
        } else if (!destinationSwitchAvailable) {
            throw new UnavailableFlowEndpointException(
                    String.format("Destination switch %s is not connected to the controller", destinationId));
        }
    }

    /**
     * Ensure vlans are not equal in the case when there is an attempt to create one-switch flow for a single port.
     */
    @VisibleForTesting
    void checkOneSwitchFlowHasNoConflicts(RequestedFlow requestedFlow) throws InvalidFlowException {
        if (requestedFlow.getSrcSwitch().equals(requestedFlow.getDestSwitch())
                && requestedFlow.getSrcPort() == requestedFlow.getDestPort()
                && requestedFlow.getSrcVlan() == requestedFlow.getDestVlan()) {

            throw new InvalidFlowException(
                    "It is not allowed to create one-switch flow for the same ports and vlans", ErrorType.DATA_INVALID);
        }
    }

    @VisibleForTesting
    void checkFlags(RequestedFlow flow) throws InvalidFlowException  {
        if (flow.isPinned() && flow.isAllocateProtectedPath()) {
            throw new InvalidFlowException("Flow flags are not valid, unable to process pinned protected flow",
                    ErrorType.DATA_INVALID);
        }

        if (flow.isAllocateProtectedPath() && flow.getSrcSwitch().equals(flow.getDestSwitch())) {
            throw new InvalidFlowException("Couldn't setup protected path for one-switch flow",
                    ErrorType.PARAMETERS_INVALID);
        }
    }

    @VisibleForTesting
    void checkDiverseFlow(RequestedFlow targetFlow) throws InvalidFlowException {
        if (targetFlow.getSrcSwitch().equals(targetFlow.getDestSwitch())) {
            throw new InvalidFlowException("Couldn't add one-switch flow into diverse group",
                    ErrorType.PARAMETERS_INVALID);
        }

        Flow diverseFlow = flowRepository.findById(targetFlow.getDiverseFlowId())
                .orElseThrow(() ->
                        new InvalidFlowException(format("Failed to find diverse flow id %s",
                                targetFlow.getDiverseFlowId()), ErrorType.PARAMETERS_INVALID));

        if (diverseFlow.isOneSwitchFlow()) {
            throw new InvalidFlowException("Couldn't create diverse group with one-switch flow",
                    ErrorType.PARAMETERS_INVALID);
        }
    }

    /**
     * Ensure switches support LLDP/ARP.
     *
     * @param requestedFlow a flow to be validated.
     */
    @VisibleForTesting
    void checkSwitchesSupportLldpAndArpIfNeeded(RequestedFlow requestedFlow) throws InvalidFlowException {
        SwitchId sourceId = requestedFlow.getSrcSwitch();
        SwitchId destinationId = requestedFlow.getDestSwitch();

        List<String> errorMessages = new ArrayList<>();

        if (requestedFlow.getDetectConnectedDevices().isSrcLldp()
                || requestedFlow.getDetectConnectedDevices().isSrcArp()) {
            validateMultiTableProperty(sourceId, errorMessages);
        }

        if (requestedFlow.getDetectConnectedDevices().isDstLldp()
                || requestedFlow.getDetectConnectedDevices().isDstArp()) {
            validateMultiTableProperty(destinationId, errorMessages);
        }

        if (!errorMessages.isEmpty()) {
            throw new InvalidFlowException(String.join(" ", errorMessages), ErrorType.DATA_INVALID);
        }
    }

    private void validateMultiTableProperty(SwitchId switchId, List<String> errorMessages) {
        Optional<SwitchProperties> switchProperties = switchPropertiesRepository.findBySwitchId(switchId);
        if (!switchProperties.isPresent()) {
            errorMessages.add(String.format("Couldn't get switch properties for switch %s.", switchId));
        } else {
            if (!switchProperties.get().isMultiTable()) {
                errorMessages.add(String.format("Catching of LLDP/ARP packets supported only on switches with "
                                + "enabled 'multiTable' switch feature. This feature is disabled on switch %s.",
                        switchId));
            }
        }
    }
}
