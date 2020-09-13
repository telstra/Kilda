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

package org.openkilda.wfm.topology.flowhs.service;

import static java.lang.String.format;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.pce.PathComputer;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext;
import org.openkilda.wfm.share.model.SpeakerRequestBuildContext.PathContext;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SpeakerWorkerService {
    private final SpeakerCommandCarrier carrier;

    @Getter
    private final PathComputer pathComputer;
    @Getter
    private final FlowResourcesManager resourcesManager;
    @Getter
    private final TransactionManager transactionManager;
    @Getter
    private final SwitchRepository switchRepository;
    @Getter
    private final IslRepository islRepository;
    @Getter
    private final FlowRepository flowRepository;
    @Getter
    private final FlowPathRepository flowPathRepository;
    @Getter
    private final FlowPathBuilder flowPathBuilder;
    @Getter
    private final int transactionRetries;
    private final SwitchPropertiesRepository switchPropertiesRepository;
    private final FeatureTogglesRepository featureTogglesRepository;

    private final Map<String, FlowSegmentRequest> keyToRequest = new HashMap<>();

    public SpeakerWorkerService(SpeakerCommandCarrier carrier, PathComputer pathComputer,
                                FlowResourcesManager resourcesManager, PersistenceManager persistenceManager,
                                int transactionRetries) {
        this.carrier = carrier;
        this.pathComputer = pathComputer;
        this.resourcesManager = resourcesManager;
        this.transactionManager = persistenceManager.getTransactionManager();
        this.switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        this.flowPathRepository = persistenceManager.getRepositoryFactory().createFlowPathRepository();
        this.flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
        this.flowPathBuilder = new FlowPathBuilder(
                switchRepository, persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository());
        this.transactionRetries = transactionRetries;
        this.switchPropertiesRepository = persistenceManager.getRepositoryFactory().createSwitchPropertiesRepository();
        this.featureTogglesRepository = persistenceManager.getRepositoryFactory().createFeatureTogglesRepository();
    }

    /**
     * Sends command to speaker.
     * @param key unique operation's key.
     * @param command command to be executed.
     */
    public void sendCommand(String key, FlowSegmentRequest command) throws PipelineException {
        log.debug("Got a request from hub bolt {}", command);
        keyToRequest.put(key, command);
        carrier.sendCommand(key, command);
    }

    public void applyCommand(String key, DbCommand command) {
        command.apply(this);
    }

    public void sendResponse(DbResponse response) {
        carrier.sendResponse(response);
    }

    /**
     * Processes received response and forwards it to the hub component.
     * @param key operation's key.
     * @param response response payload.
     */
    public void handleResponse(String key, SpeakerFlowSegmentResponse response)
            throws PipelineException {
        log.debug("Got a response from speaker {}", response);
        FlowSegmentRequest pendingRequest = keyToRequest.remove(key);
        if (pendingRequest != null) {
            if (pendingRequest.getCommandId().equals(response.getCommandId())) {
                carrier.sendResponse(key, response);
            } else {
                log.warn("Pending request's command id and received response's command id mismatch");
            }
        }
    }

    /**
     * Handles operation timeout.
     * @param key operation identifier.
     */
    public void handleTimeout(String key) throws PipelineException {
        FlowSegmentRequest failedRequest = keyToRequest.remove(key);

        SpeakerFlowSegmentResponse response = FlowErrorResponse.errorBuilder()
                .commandId(failedRequest.getCommandId())
                .switchId(failedRequest.getSwitchId())
                .metadata(failedRequest.getMetadata())
                .errorCode(ErrorCode.OPERATION_TIMED_OUT)
                .messageContext(failedRequest.getMessageContext())
                .build();
        carrier.sendResponse(key, response);
    }


    protected SwitchProperties getSwitchProperties(SwitchId ingressSwitchId) {
        return switchPropertiesRepository.findBySwitchId(ingressSwitchId)
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Properties for switch %s not found", ingressSwitchId)));
    }

    /**
     * Build Speaker Request Context for FL install commands.cd
     * @param srcSwitchId source switch ID
     * @param dstSwitchId destination switch ID
     * @return SpeakerRequestBuildContext
     */
    public SpeakerRequestBuildContext buildBaseSpeakerContextForInstall(SwitchId srcSwitchId, SwitchId dstSwitchId) {
        return SpeakerRequestBuildContext.builder()
                .forward(buildBasePathContextForInstall(srcSwitchId))
                .reverse(buildBasePathContextForInstall(dstSwitchId))
                .build();
    }

    protected PathContext buildBasePathContextForInstall(SwitchId switchId) {
        SwitchProperties switchProperties = getSwitchProperties(switchId);
        boolean serverFlowRtt = switchProperties.isServer42FlowRtt() && isServer42FlowRttFeatureToggle();
        return PathContext.builder()
                .installServer42InputRule(serverFlowRtt && switchProperties.isMultiTable())
                .installServer42IngressRule(serverFlowRtt)
                .server42Port(switchProperties.getServer42Port())
                .server42MacAddress(switchProperties.getServer42MacAddress())
                .build();
    }

    protected boolean isServer42FlowRttFeatureToggle() {
        return featureTogglesRepository.find().map(FeatureToggles::getServer42FlowRtt).orElse(false);
    }
}
