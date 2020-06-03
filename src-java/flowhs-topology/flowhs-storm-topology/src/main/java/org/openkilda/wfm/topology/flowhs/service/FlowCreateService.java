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

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.pce.PathComputer;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Config;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import io.micrometer.core.instrument.LongTaskTimer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FlowCreateService {

    private final Map<String, FlowCreateFsm> fsms = new HashMap<>();

    private final FlowCreateFsm.Factory fsmFactory;
    private final FlowCreateHubCarrier carrier;
    private final FlowEventRepository flowEventRepository;
    private final KildaConfigurationRepository kildaConfigurationRepository;
    private boolean active;

    public FlowCreateService(FlowCreateHubCarrier carrier, PersistenceManager persistenceManager,
                             PathComputer pathComputer, FlowResourcesManager flowResourcesManager,
                             int genericRetriesLimit, int pathAllocationRetriesLimit,
                             int pathAllocationRetryDelay, int speakerCommandRetriesLimit) {
        this.carrier = carrier;
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        flowEventRepository = repositoryFactory.createFlowEventRepository();
        kildaConfigurationRepository = repositoryFactory.createKildaConfigurationRepository();

        Config fsmConfig = Config.builder()
                .flowCreationRetriesLimit(genericRetriesLimit)
                .pathAllocationRetriesLimit(pathAllocationRetriesLimit)
                .pathAllocationRetryDelay(pathAllocationRetryDelay)
                .speakerCommandRetriesLimit(speakerCommandRetriesLimit)
                .build();
        fsmFactory = FlowCreateFsm.factory(persistenceManager, carrier, fsmConfig, flowResourcesManager, pathComputer);
    }

    /**
     * Handles request for flow creation.
     *
     * @param key     command identifier.
     * @param request request data.
     */
    public void handleRequest(String key, CommandContext commandContext, FlowRequest request) {
        log.debug("Handling flow create request with key {} and flow ID: {}", key, request.getFlowId());

        if (fsms.containsKey(key)) {
            log.error("Attempt to create a FSM with key {}, while there's another active FSM with the same key.", key);
            return;
        }

        String eventKey = commandContext.getCorrelationId();
        if (flowEventRepository.existsByTaskId(eventKey)) {
            log.error("Attempt to reuse key {}, but there's a history record(s) for it.", eventKey);
            return;
        }

        FlowCreateFsm fsm = fsmFactory.produce(request.getFlowId(), commandContext);
        MeterRegistryHolder.getRegistry().ifPresent(registry ->
                fsm.setGlobalTimer(LongTaskTimer.builder("fsm.active_execution")
                .register(registry)
                .start()));
        fsms.put(key, fsm);

        RequestedFlow requestedFlow = RequestedFlowMapper.INSTANCE.toRequestedFlow(request);
        if (requestedFlow.getFlowEncapsulationType() == null) {
            requestedFlow.setFlowEncapsulationType(
                    kildaConfigurationRepository.getOrDefault().getFlowEncapsulationType());
        }
        if (requestedFlow.getPathComputationStrategy() == null) {
            requestedFlow.setPathComputationStrategy(
                    kildaConfigurationRepository.getOrDefault().getPathComputationStrategy());
        }
        FlowCreateContext context = FlowCreateContext.builder()
                .targetFlow(requestedFlow)
                .build();
        fsm.setTargetFlow(requestedFlow);
        fsm.start();
        processNext(fsm, context);
        removeIfFinished(fsm, key);
    }

    /**
     * Handles async response from worker.
     *
     * @param key command identifier.
     */
    public void handleAsyncResponse(String key, SpeakerFlowSegmentResponse flowResponse) {
        log.debug("Received flow command response {}", flowResponse);
        FlowCreateFsm fsm = fsms.get(key);
        if (fsm == null) {
            log.warn("Failed to find a FSM: received response with key {} for non pending FSM", key);
            return;
        }

        FlowCreateContext context = FlowCreateContext.builder()
                .speakerFlowResponse(flowResponse)
                .build();
        fsm.fire(Event.RESPONSE_RECEIVED, context);

        processNext(fsm, null);
        removeIfFinished(fsm, key);
    }

    /**
     * Handles timeout case.
     *
     * @param key command identifier.
     */
    public void handleTimeout(String key) {
        log.debug("Handling timeout for {}", key);
        FlowCreateFsm fsm = fsms.get(key);
        if (fsm == null) {
            log.warn("Failed to find a FSM: timeout event for non pending FSM with key {}", key);
            return;
        }

        fsm.fireTimeout();
        removeIfFinished(fsm, key);
    }

    private void processNext(FlowCreateFsm fsm, FlowCreateContext context) {
        while (!fsm.getCurrentState().isBlocked()) {
            fsm.fireNext(context);
        }
    }

    private void removeIfFinished(FlowCreateFsm fsm, String key) {
        if (fsm.isTerminated()) {
            log.debug("FSM with key {} is finished with state {}", key, fsm.getCurrentState());
            fsms.remove(key);

            carrier.cancelTimeoutCallback(key);

            MeterRegistryHolder.getRegistry().ifPresent(registry -> {
                long duration = fsm.getGlobalTimer().stop();
                registry.timer("fsm.execution")
                        .record(duration, TimeUnit.NANOSECONDS);
                if (fsm.getCurrentState() == State.FINISHED) {
                    registry.timer("fsm.execution.success")
                            .record(duration, TimeUnit.NANOSECONDS);
                } else if (fsm.getCurrentState() == State.FINISHED_WITH_ERROR) {
                    registry.timer("fsm.execution.failed")
                            .record(duration, TimeUnit.NANOSECONDS);
                }
            });
        }
        if (!active && fsms.isEmpty()) {
            carrier.sendInactive();
        }
    }

    /**
     * Handles deactivate command.
     */
    public boolean deactivate() {
        active = false;
        if (fsms.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * Handles activate command.
     */
    public void activate() {
        active = true;
    }
}
