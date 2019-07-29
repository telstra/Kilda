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

package org.openkilda.wfm.topology.flow.validation;

import static java.lang.String.format;

import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import com.google.common.annotations.VisibleForTesting;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code FlowValidator} performs checks against the flow validation rules.
 */
public class FlowValidator {

    private final FlowRepository flowRepository;
    private final SwitchRepository switchRepository;
    private final IslRepository islRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;

    public FlowValidator(RepositoryFactory repositoryFactory) {
        this.flowRepository = repositoryFactory.createFlowRepository();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.islRepository = repositoryFactory.createIslRepository();
        this.switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
    }

    /**
     * Validates the specified flow.
     *
     * @param flow a flow to be validated.
     * @throws FlowValidationException is thrown if a violation is found.
     */
    public void validate(Flow flow) throws FlowValidationException, SwitchValidationException {
        checkBandwidth(flow);
        checkFlowForIslConflicts(flow);
        checkFlowForEndpointConflicts(flow);
        checkOneSwitchFlowHasNoConflicts(flow);
        checkSwitchesExists(flow);
        checkSwitchesSupportLldpAndArpIfNeeded(flow);
    }

    /**
     * Validates flows for swap.
     * @param firstFlow a first flow.
     * @param secondFlow a second flow.
     * @throws FlowValidationException is thrown if a violation is found.
     */
    public void validateFowSwap(Flow firstFlow, Flow secondFlow) throws FlowValidationException {
        checkFlowForIslConflicts(firstFlow);
        checkFlowForIslConflicts(secondFlow);
        checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
        checkFlowForEndpointConflicts(secondFlow, Collections.singleton(firstFlow.getFlowId()));
        checkForEqualsEndpoints(firstFlow, secondFlow);
    }

    @VisibleForTesting
    void checkBandwidth(Flow flow) throws FlowValidationException {
        if (flow.getBandwidth() < 0) {
            throw new FlowValidationException(
                    format("The flow '%s' has invalid bandwidth %d provided.",
                            flow.getFlowId(),
                            flow.getBandwidth()),
                    ErrorType.DATA_INVALID);
        }
    }

    /**
     * Checks a flow for conflicts with ISL ports.
     *
     * @param requestedFlow a flow to be validated.
     * @throws FlowValidationException is thrown in a case when flow endpoints conflict with existing ISL ports.
     */
    @VisibleForTesting
    void checkFlowForIslConflicts(Flow requestedFlow) throws FlowValidationException {
        // Check the source
        if (!islRepository.findByEndpoint(requestedFlow.getSrcSwitchId(),
                requestedFlow.getSrcPort()).isEmpty()) {
            String errorMessage = format("The port %d on the switch '%s' is occupied by an ISL.",
                    requestedFlow.getSrcPort(), requestedFlow.getSrcSwitchId());
            throw new FlowValidationException(errorMessage, ErrorType.PARAMETERS_INVALID);
        }

        // Check the destination
        if (!islRepository.findByEndpoint(requestedFlow.getDestSwitchId(),
                requestedFlow.getDestPort()).isEmpty()) {
            String errorMessage = format("The port %d on the switch '%s' is occupied by an ISL.",
                    requestedFlow.getDestPort(), requestedFlow.getDestSwitchId());
            throw new FlowValidationException(errorMessage, ErrorType.PARAMETERS_INVALID);
        }
    }

    /**
     * Checks a flow for endpoints' conflicts.
     *
     * @param subject a flow to be validated.
     * @throws FlowValidationException is thrown in a case when flow endpoints conflict with existing flows.
     */
    @VisibleForTesting
    void checkFlowForEndpointConflicts(Flow subject) throws FlowValidationException {
        checkFlowForEndpointConflicts(subject, new HashSet<>());
    }

    @VisibleForTesting
    void checkFlowForEndpointConflicts(Flow subject, Set<String> flowIdsConflictToBeIgnored)
            throws FlowValidationException {
        // Check the source
        Collection<Flow> conflictsOnSource = flowRepository.findByEndpoint(subject.getSrcSwitchId(),
                subject.getSrcPort());

        Set<String> processingFlowIds = new HashSet<>(flowIdsConflictToBeIgnored);
        processingFlowIds.add(subject.getFlowId());

        Optional<Flow> conflictSrcSrc = conflictsOnSource.stream()
                .filter(flow -> !processingFlowIds.contains(flow.getFlowId()))
                .filter(flow -> flow.getSrcSwitchId().equals(subject.getSrcSwitchId())
                        && flow.getSrcPort() == subject.getSrcPort()
                        && (flow.getSrcVlan() == subject.getSrcVlan()))
                .findAny();

        if (conflictSrcSrc.isPresent()) {
            String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                            + "Details: "
                            + "requested flow '%s' source: switch=%s port=%d vlan=%d, "
                            + "existing flow '%s' source: switch=%s port=%d vlan=%d",
                    subject.getFlowId(), conflictSrcSrc.get().getFlowId(),
                    subject.getFlowId(), subject.getSrcSwitchId().toString(),
                    subject.getSrcPort(), subject.getSrcVlan(),
                    conflictSrcSrc.get().getFlowId(),
                    conflictSrcSrc.get().getSrcSwitchId().toString(),
                    conflictSrcSrc.get().getSrcPort(), conflictSrcSrc.get().getSrcVlan());
            throw new FlowValidationException(errorMessage, ErrorType.ALREADY_EXISTS);
        }

        Optional<Flow> conflictDstSrc = conflictsOnSource.stream()
                .filter(flow -> !processingFlowIds.contains(flow.getFlowId()))
                .filter(flow -> flow.getDestSwitchId().equals(subject.getSrcSwitchId())
                        && flow.getDestPort() == subject.getSrcPort()
                        && (flow.getDestVlan() == subject.getSrcVlan()))
                .findAny();

        if (conflictDstSrc.isPresent()) {
            String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                            + "Details: "
                            + "requested flow '%s' source: switch=%s port=%d vlan=%d, "
                            + "existing flow '%s' destination: switch=%s port=%d vlan=%d",
                    subject.getFlowId(), conflictDstSrc.get().getFlowId(),
                    subject.getFlowId(), subject.getSrcSwitchId().toString(),
                    subject.getSrcPort(), subject.getSrcVlan(),
                    conflictDstSrc.get().getFlowId(),
                    conflictDstSrc.get().getDestSwitchId().toString(),
                    conflictDstSrc.get().getDestPort(), conflictDstSrc.get().getDestVlan());
            throw new FlowValidationException(errorMessage, ErrorType.ALREADY_EXISTS);
        }

        // Check the destination
        Collection<Flow> conflictsOnDest = flowRepository.findByEndpoint(
                subject.getDestSwitchId(),
                subject.getDestPort());


        Optional<Flow> conflictSrcDst = conflictsOnDest.stream()
                .filter(flow -> !processingFlowIds.contains(flow.getFlowId()))
                .filter(flow -> flow.getSrcSwitchId().equals(subject.getDestSwitchId())
                        && flow.getSrcPort() == subject.getDestPort()
                        && (flow.getSrcVlan() == subject.getDestVlan()))
                .findAny();

        if (conflictSrcDst.isPresent()) {
            String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                            + "Details: "
                            + "requested flow '%s' destination: switch=%s port=%d vlan=%d, "
                            + "existing flow '%s' source: switch=%s port=%d vlan=%d",
                    subject.getFlowId(), conflictSrcDst.get().getFlowId(),
                    subject.getFlowId(), subject.getDestSwitchId().toString(),
                    subject.getDestPort(), subject.getDestVlan(),
                    conflictSrcDst.get().getFlowId(),
                    conflictSrcDst.get().getSrcSwitchId().toString(),
                    conflictSrcDst.get().getSrcPort(), conflictSrcDst.get().getSrcVlan());
            throw new FlowValidationException(errorMessage, ErrorType.ALREADY_EXISTS);
        }

        Optional<Flow> conflictDstDst = conflictsOnDest.stream()
                .filter(flow -> !processingFlowIds.contains(flow.getFlowId()))
                .filter(flow -> flow.getDestSwitchId().equals(subject.getDestSwitchId())
                        && flow.getDestPort() == subject.getDestPort()
                        && (flow.getDestVlan() == subject.getDestVlan()))
                .findAny();

        if (conflictDstDst.isPresent()) {
            String errorMessage = format("Requested flow '%s' conflicts with existing flow '%s'. "
                            + "Details: "
                            + "requested flow '%s' destination: switch=%s port=%d vlan=%d, "
                            + "existing flow '%s' destination: switch=%s port=%d vlan=%d",
                    subject.getFlowId(), conflictDstDst.get().getFlowId(),
                    subject.getFlowId(), subject.getDestSwitchId().toString(),
                    subject.getDestPort(), subject.getDestVlan(),
                    conflictDstDst.get().getFlowId(),
                    conflictDstDst.get().getDestSwitchId().toString(),
                    conflictDstDst.get().getDestPort(), conflictDstDst.get().getDestVlan());
            throw new FlowValidationException(errorMessage, ErrorType.ALREADY_EXISTS);
        }
    }

    /**
     * Check for equals endpoints.
     *
     * @param firstFlow a first flow.
     * @param secondFlow a second flow.
     */
    void checkForEqualsEndpoints(Flow firstFlow, Flow secondFlow) throws FlowValidationException {
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(new Endpoint(firstFlow.getSrcSwitchId(),
                firstFlow.getSrcPort(), firstFlow.getSrcVlan()));
        endpoints.add(new Endpoint(firstFlow.getDestSwitchId(),
                firstFlow.getDestPort(), firstFlow.getDestVlan()));
        endpoints.add(new Endpoint(secondFlow.getSrcSwitchId(),
                secondFlow.getSrcPort(), secondFlow.getSrcVlan()));
        endpoints.add(new Endpoint(secondFlow.getDestSwitchId(),
                secondFlow.getDestPort(), secondFlow.getDestVlan()));

        Set<Endpoint> checkSet = new HashSet<>();
        for (Endpoint endpoint : endpoints) {
            if (!checkSet.contains(endpoint)) {
                checkSet.add(endpoint);
            } else {
                String message = "New requested endpoint for '%s' conflicts with existing endpoint for '%s'";
                if (checkSet.size() <= 1) {
                    message = String.format(message, firstFlow.getFlowId(), firstFlow.getFlowId());
                } else {
                    if (endpoints.indexOf(endpoint) <= 1) {
                        message = String.format(message, secondFlow.getFlowId(), firstFlow.getFlowId());
                    } else {
                        message = String.format(message, secondFlow.getFlowId(), secondFlow.getFlowId());
                    }
                }
                throw new FlowValidationException(message, ErrorType.REQUEST_INVALID);
            }
        }
    }

    /**
     * Ensure switches are exists.
     *
     * @param requestedFlow a flow to be validated.
     * @throws SwitchValidationException if switch not found.
     */
    @VisibleForTesting
    void checkSwitchesExists(Flow requestedFlow) throws SwitchValidationException {
        final SwitchId sourceId = requestedFlow.getSrcSwitchId();
        final SwitchId destinationId = requestedFlow.getDestSwitchId();

        boolean source;
        boolean destination;

        if (Objects.equals(sourceId, destinationId)) {
            source = destination = switchRepository.exists(sourceId);
        } else {
            source = switchRepository.exists(sourceId);
            destination = switchRepository.exists(destinationId);
        }

        if (!source && !destination) {
            throw new SwitchValidationException(
                    String.format("Source switch %s and Destination switch %s are not connected to the controller",
                            sourceId, destinationId));
        } else if (!source) {
            throw new SwitchValidationException(
                    String.format("Source switch %s is not connected to the controller", sourceId));
        } else if (!destination) {
            throw new SwitchValidationException(
                    String.format("Destination switch %s is not connected to the controller", destinationId));
        }
    }

    /**
     * Ensure switches support LLDP/ARP.
     *
     * @param requestedFlow a flow to be validated.
     * @throws SwitchValidationException if LLDP/ARP is enabled for switch but switch doesn't support it.
     */
    @VisibleForTesting
    void checkSwitchesSupportLldpAndArpIfNeeded(Flow requestedFlow) throws SwitchValidationException {
        SwitchId sourceId = requestedFlow.getSrcSwitchId();
        SwitchId destinationId = requestedFlow.getDestSwitchId();

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
            throw new SwitchValidationException(String.join(" ", errorMessages));
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
     * Ensure vlans are not equal in the case when there is an attempt to create one-switch flow for a single port.
     */
    @VisibleForTesting
    void checkOneSwitchFlowHasNoConflicts(Flow requestedFlow) throws SwitchValidationException {
        if (requestedFlow.isOneSwitchFlow()
                && requestedFlow.getSrcPort() == requestedFlow.getDestPort()
                && requestedFlow.getSrcVlan() == requestedFlow.getDestVlan()) {

            throw new SwitchValidationException(
                    "It is not allowed to create one-switch flow for the same ports and vlans");
        }
    }

    @Value
    private class Endpoint {
        private SwitchId switchId;
        private Integer portNumber;
        private Integer vlanId;
    }
}
