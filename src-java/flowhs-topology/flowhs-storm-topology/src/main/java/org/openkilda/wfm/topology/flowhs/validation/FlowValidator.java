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

package org.openkilda.wfm.topology.flowhs.validation;

import static java.lang.String.format;

import org.openkilda.adapter.FlowDestAdapter;
import org.openkilda.adapter.FlowSourceAdapter;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
        validate(flow, new HashSet<>());
    }

    /**
     * Validates the specified flow.
     *
     * @param flow a flow to be validated.
     * @param bulkUpdateFlowIds flows to be ignored when check endpoints.
     * @throws InvalidFlowException is thrown if a violation is found.
     */
    public void validate(RequestedFlow flow, Set<String> bulkUpdateFlowIds)
            throws InvalidFlowException, UnavailableFlowEndpointException {
        baseFlowValidate(flow, bulkUpdateFlowIds);

        checkFlags(flow);
        checkBandwidth(flow);
        checkSwitchesSupportLldpAndArpIfNeeded(flow);

        if (StringUtils.isNotBlank(flow.getDiverseFlowId())) {
            checkDiverseFlow(flow);
        }
    }

    private void baseFlowValidate(RequestedFlow flow, Set<String> bulkUpdateFlowIds)
            throws InvalidFlowException, UnavailableFlowEndpointException {
        final FlowEndpoint source = RequestedFlowMapper.INSTANCE.mapSource(flow);
        final FlowEndpoint destination = RequestedFlowMapper.INSTANCE.mapDest(flow);

        checkOneSwitchFlowConflict(source, destination);
        checkSwitchesExistsAndActive(flow);

        for (EndpointDescriptor descriptor : new EndpointDescriptor[]{
                EndpointDescriptor.makeSource(source),
                EndpointDescriptor.makeDestination(destination)}) {
            checkForMultiTableRequirement(descriptor);
            checkFlowForIslConflicts(descriptor);
            checkFlowForFlowConflicts(flow.getFlowId(), descriptor, bulkUpdateFlowIds);
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

    /**
     * Validates the specified flow when swap endpoint operation.
     */
    public void validateForSwapEndpoints(RequestedFlow firstFlow, RequestedFlow secondFlow)
            throws InvalidFlowException, UnavailableFlowEndpointException {
        baseFlowValidate(firstFlow, Sets.newHashSet(secondFlow.getFlowId()));
        baseFlowValidate(secondFlow, Sets.newHashSet(firstFlow.getFlowId()));

        checkForEqualsEndpoints(firstFlow, secondFlow);
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

    private void checkFlowForIslConflicts(EndpointDescriptor descriptor) throws InvalidFlowException {
        FlowEndpoint endpoint = descriptor.getEndpoint();
        if (!islRepository.findByEndpoint(endpoint.getSwitchId(), endpoint.getPortNumber()).isEmpty()) {
            String errorMessage = format(
                    "The port %d on the switch '%s' is occupied by an ISL (%s endpoint collision).",
                    endpoint.getPortNumber(), endpoint.getSwitchId(), descriptor.getName());
            throw new InvalidFlowException(errorMessage, ErrorType.PARAMETERS_INVALID);
        }
    }

    /**
     * Checks a flow for endpoints' conflicts.
     *
     * @throws InvalidFlowException is thrown in a case when flow endpoints conflict with existing flows.
     */
    private void checkFlowForFlowConflicts(String flowId, EndpointDescriptor descriptor, Set<String> bulkUpdateFlowIds)
            throws InvalidFlowException {
        final FlowEndpoint endpoint = descriptor.getEndpoint();

        for (Flow entry : flowRepository.findByEndpoint(endpoint.getSwitchId(), endpoint.getPortNumber())) {
            if (flowId.equals(entry.getFlowId()) || bulkUpdateFlowIds.contains(entry.getFlowId())) {
                continue;
            }

            FlowEndpoint source = new FlowSourceAdapter(entry).getEndpoint();
            FlowEndpoint destination = new FlowDestAdapter(entry).getEndpoint();

            EndpointDescriptor conflict = null;
            if (endpoint.isSwitchPortVlanEquals(source)) {
                conflict = EndpointDescriptor.makeSource(source);
            } else if (endpoint.isSwitchPortVlanEquals(destination)) {
                conflict = EndpointDescriptor.makeDestination(destination);
            }

            if (conflict != null) {
                String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                                + "Details: requested flow '%s' %s: %s, "
                                + "existing flow '%s' %s: %s",
                        flowId, entry.getFlowId(),
                        flowId, descriptor.getName(), endpoint,
                        entry.getFlowId(), conflict.getName(), conflict.getEndpoint());
                throw new InvalidFlowException(errorMessage, ErrorType.ALREADY_EXISTS);
            }
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
    private void checkOneSwitchFlowConflict(FlowEndpoint source, FlowEndpoint destination) throws InvalidFlowException {
        if (source.isSwitchPortVlanEquals(destination)) {
            throw new InvalidFlowException(
                    "It is not allowed to create one-switch flow for the same ports and vlans", ErrorType.DATA_INVALID);
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
    // TODO: switch to per endpoint based strategy (same as other enpoint related checks)
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

    /**
     * Check for equals endpoints.
     *
     * @param firstFlow a first flow.
     * @param secondFlow a second flow.
     */
    @VisibleForTesting
    void checkForEqualsEndpoints(RequestedFlow firstFlow, RequestedFlow secondFlow) throws InvalidFlowException {
        String message = "New requested endpoint for '%s' conflicts with existing endpoint for '%s'";

        Set<FlowEndpoint> firstFlowEndpoints = validateFlowEqualsEndpoints(firstFlow, message);
        Set<FlowEndpoint> secondFlowEndpoints = validateFlowEqualsEndpoints(secondFlow, message);

        for (FlowEndpoint endpoint : secondFlowEndpoints) {
            if (firstFlowEndpoints.contains(endpoint)) {
                message = String.format(message, secondFlow.getFlowId(), firstFlow.getFlowId());
                throw new InvalidFlowException(message, ErrorType.DATA_INVALID);
            }
        }
    }

    private Set<FlowEndpoint> validateFlowEqualsEndpoints(RequestedFlow flow, String errorMessage)
            throws InvalidFlowException {

        Set<FlowEndpoint> flowEndpoints = new HashSet<>();
        flowEndpoints.add(RequestedFlowMapper.INSTANCE.mapSource(flow));
        flowEndpoints.add(RequestedFlowMapper.INSTANCE.mapDest(flow));

        if (flowEndpoints.size() != 2) {
            errorMessage = String.format(errorMessage, flow.getFlowId(), flow.getFlowId());
            throw new InvalidFlowException(errorMessage, ErrorType.DATA_INVALID);
        }

        return flowEndpoints;
    }

    private void checkForMultiTableRequirement(EndpointDescriptor descriptor) throws InvalidFlowException {
        FlowEndpoint endpoint = descriptor.getEndpoint();
        if (endpoint.getVlanStack().size() < 2) {
            return;
        }

        SwitchProperties switchProperties = switchPropertiesRepository.findBySwitchId(
                endpoint.getSwitchId())
                .orElseGet(() -> SwitchProperties.builder().build());
        if (! switchProperties.isMultiTable()) {
            final String errorMessage = format(
                    "Flow's %s endpoint is double VLAN tagged, switch %s is not capable to support such endpoint "
                            + "encapsulation.",
                    descriptor.getName(), endpoint.getSwitchId());
            throw new InvalidFlowException(errorMessage, ErrorType.PARAMETERS_INVALID);
        }
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class EndpointDescriptor {
        private final FlowEndpoint endpoint;
        private final String name;

        private static EndpointDescriptor makeSource(FlowEndpoint endpoint) {
            return new EndpointDescriptor(endpoint, "source");
        }

        private static EndpointDescriptor makeDestination(FlowEndpoint endpoint) {
            return new EndpointDescriptor(endpoint, "destination");
        }
    }
}
