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
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.info.reroute.error.RerouteInProgressError;
import org.openkilda.pce.PathComputer;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.LongTaskTimer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FlowRerouteService {
    @VisibleForTesting
    final Map<String, FlowRerouteFsm> fsms = new HashMap<>();

    private final FlowRerouteFsm.Factory fsmFactory;
    private final FsmExecutor<FlowRerouteFsm, State, Event, FlowRerouteContext> fsmExecutor
            = new FsmExecutor<>(Event.NEXT);

    private final FlowRerouteHubCarrier carrier;
    private final FlowEventRepository flowEventRepository;

    private boolean active;

    public FlowRerouteService(FlowRerouteHubCarrier carrier, PersistenceManager persistenceManager,
                              PathComputer pathComputer, FlowResourcesManager flowResourcesManager,
                              int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                              int speakerCommandRetriesLimit) {
        this.carrier = carrier;
        this.flowEventRepository = persistenceManager.getRepositoryFactory().createFlowEventRepository();
        fsmFactory = new FlowRerouteFsm.Factory(carrier, persistenceManager, pathComputer, flowResourcesManager,
                pathAllocationRetriesLimit, pathAllocationRetryDelay,
                speakerCommandRetriesLimit);
    }

    /**
     * Handles request for flow reroute.
     */
    public void handleRequest(String key, FlowRerouteRequest reroute, final CommandContext commandContext) {
        log.debug("Handling flow reroute request with key {} and flow ID: {}", key, reroute.getFlowId());

        MeterRegistryHolder.getRegistry().ifPresent(registry -> {
            if (reroute.getTimestamp() > 0) {
                Duration abs = Duration.between(Instant.ofEpochMilli(reroute.getTimestamp()),
                        Instant.now()).abs();
                registry.timer("nb_to_service")
                        .record(abs.toNanos(), TimeUnit.NANOSECONDS);
            }
        });

        try {
            checkRequestsCollision(key, reroute.getFlowId(), commandContext);
        } catch (Exception e) {
            log.error(e.getMessage());
            FlowRerouteFsm fsm = fsms.get(key);
            if (fsm != null) {
                removeIfFinished(fsm, key);
            }
            return;
        }

        final String flowId = reroute.getFlowId();
        if (isRerouteAlreadyInProgress(flowId)) {
            carrier.sendRerouteResultStatus(flowId, new RerouteInProgressError(),
                    commandContext.getCorrelationId());
            return;
        }

        FlowRerouteFsm fsm = fsmFactory.newInstance(commandContext, flowId);
        MeterRegistryHolder.getRegistry().ifPresent(registry ->
                fsm.setGlobalTimer(LongTaskTimer.builder("fsm.active_execution")
                        .register(registry)
                        .start()));
        fsms.put(key, fsm);

        FlowRerouteContext context = FlowRerouteContext.builder()
                .flowId(flowId)
                .affectedIsl(reroute.getAffectedIsl())
                .forceReroute(reroute.isForce())
                .ignoreBandwidth(reroute.isIgnoreBandwidth())
                .effectivelyDown(reroute.isEffectivelyDown())
                .rerouteReason(reroute.getReason())
                .build();
        fsmExecutor.fire(fsm, Event.NEXT, context);

        removeIfFinished(fsm, key);
    }

    private boolean isRerouteAlreadyInProgress(String flowId) {
        return fsms.values().stream()
                .map(FlowRerouteFsm::getFlowId)
                .anyMatch(id -> id.equals(flowId));
    }

    /**
     * Handles async response from worker.
     *
     * @param key command identifier.
     */
    public void handleAsyncResponse(String key, SpeakerFlowSegmentResponse flowResponse) {
        log.debug("Received flow command response {}", flowResponse);
        FlowRerouteFsm fsm = fsms.get(key);
        if (fsm == null) {
            log.warn("Failed to find a FSM: received response with key {} for non pending FSM", key);
            return;
        }

        FlowRerouteContext context = FlowRerouteContext.builder()
                .speakerFlowResponse(flowResponse)
                .build();

        if (flowResponse instanceof FlowErrorResponse) {
            fsmExecutor.fire(fsm, Event.ERROR_RECEIVED, context);
        } else {
            fsmExecutor.fire(fsm, Event.RESPONSE_RECEIVED, context);
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
        FlowRerouteFsm fsm = fsms.get(key);
        if (fsm == null) {
            log.warn("Failed to find a FSM: timeout event for non pending FSM with key {}", key);
            return;
        }

        fsmExecutor.fire(fsm, Event.TIMEOUT, null);

        removeIfFinished(fsm, key);
    }

    private void checkRequestsCollision(String key, String flowId, CommandContext commandContext) {
        if (fsms.containsKey(key)) {
            throw new IllegalStateException(String.format(
                    "Attempt to create a FSM with key %s, while there's another active FSM with the same key "
                            + "(flowId=\"%s\")", key, flowId));
        }

        String eventKey = commandContext.getCorrelationId();
        if (flowEventRepository.existsByTaskId(eventKey)) {
            throw new IllegalStateException(String.format(
                    "Attempt to reuse history key %s, but there's a history record(s) for it (flowId=\"%s\")",
                    eventKey, flowId));
        }
    }

    private void removeIfFinished(FlowRerouteFsm fsm, String key) {
        if (fsm.isTerminated()) {
            log.debug("FSM with key {} is finished with state {}", key, fsm.getCurrentState());
            performHousekeeping(key);

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

    private void performHousekeeping(String key) {
        fsms.remove(key);
        carrier.cancelTimeoutCallback(key);
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
