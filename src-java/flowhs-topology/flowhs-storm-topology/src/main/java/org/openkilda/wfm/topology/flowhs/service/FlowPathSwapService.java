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

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.messaging.command.flow.FlowPathSwapRequest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapContext;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.State;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.LongTaskTimer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FlowPathSwapService {
    @VisibleForTesting
    final Map<String, FlowPathSwapFsm> fsms = new HashMap<>();

    private final FlowPathSwapFsm.Factory fsmFactory;
    private final FsmExecutor<FlowPathSwapFsm, State, Event, FlowPathSwapContext> fsmExecutor
            = new FsmExecutor<>(Event.NEXT);
    private final FlowPathSwapHubCarrier carrier;
    private final FlowEventRepository flowEventRepository;

    private boolean active;

    public FlowPathSwapService(FlowPathSwapHubCarrier carrier,
                               PersistenceManager persistenceManager,
                               int speakerCommandRetriesLimit, FlowResourcesManager flowResourcesManager) {
        fsmFactory = new FlowPathSwapFsm.Factory(carrier,
                persistenceManager, flowResourcesManager, speakerCommandRetriesLimit);
        this.carrier = carrier;
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.flowEventRepository = repositoryFactory.createFlowEventRepository();
    }

    /**
     * Handles request for flow update.
     *
     * @param key     command identifier.
     * @param request request data.
     */
    public void handleRequest(String key, CommandContext commandContext, FlowPathSwapRequest request) {
        log.debug("Handling flow path swap request with key {} and flow ID: {}", key, request.getFlowId());

        if (fsms.containsKey(key)) {
            log.error("Attempt to create a FSM with key {}, while there's another active FSM with the same key.", key);
            return;
        }

        String eventKey = commandContext.getCorrelationId();
        if (flowEventRepository.existsByTaskId(eventKey)) {
            log.error("Attempt to reuse key {}, but there's a history record(s) for it.", eventKey);
            return;
        }

        FlowPathSwapFsm fsm = fsmFactory.newInstance(commandContext, request.getFlowId());
        MeterRegistryHolder.getRegistry().ifPresent(registry ->
                fsm.setGlobalTimer(LongTaskTimer.builder("fsm.active_execution")
                        .register(registry)
                        .start()));
        fsms.put(key, fsm);

        FlowPathSwapContext context = FlowPathSwapContext.builder()
                .build();
        fsmExecutor.fire(fsm, Event.NEXT, context);

        removeIfFinished(fsm, key);
    }

    /**
     * Handles async response from worker.
     *
     * @param key command identifier.
     */
    public void handleAsyncResponse(String key, SpeakerFlowSegmentResponse flowResponse) {
        log.debug("Received flow command response {}", flowResponse);
        FlowPathSwapFsm fsm = fsms.get(key);
        if (fsm == null) {
            log.warn("Failed to find a FSM: received response with key {} for non pending FSM", key);
            return;
        }

        FlowPathSwapContext context = FlowPathSwapContext.builder()
                .speakerFlowResponse(flowResponse)
                .build();

        if (flowResponse instanceof FlowErrorResponse) {
            fsmExecutor.fire(fsm, FlowPathSwapFsm.Event.ERROR_RECEIVED, context);
        } else {
            fsmExecutor.fire(fsm, FlowPathSwapFsm.Event.RESPONSE_RECEIVED, context);
        }

        removeIfFinished(fsm, key);
    }

    /**
     * Handles timeout case.
     *
     * @param key command identifier.
     */
    public void handleTimeout(String key) {
        log.debug("Handling timeout for {}", key);
        FlowPathSwapFsm fsm = fsms.get(key);
        if (fsm == null) {
            log.warn("Failed to find a FSM: timeout event for non pending FSM with key {}", key);
            return;
        }

        fsmExecutor.fire(fsm, FlowPathSwapFsm.Event.TIMEOUT, null);

        removeIfFinished(fsm, key);
    }

    private void removeIfFinished(FlowPathSwapFsm fsm, String key) {
        if (fsm.isTerminated()) {
            log.debug("FSM with key {} is finished with state {}", key, fsm.getCurrentState());
            fsms.remove(key);

            carrier.cancelTimeoutCallback(key);

            if (!active && fsms.isEmpty()) {
                carrier.sendInactive();
            }

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
