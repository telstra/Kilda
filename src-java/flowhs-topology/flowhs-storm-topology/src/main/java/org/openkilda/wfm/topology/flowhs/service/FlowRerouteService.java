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
import org.openkilda.messaging.info.reroute.error.RerouteInProgressError;
import org.openkilda.pce.PathComputer;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.model.FlowRerouteFact;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

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
    private final MeterRegistry meterRegistry;

    public FlowRerouteService(FlowRerouteHubCarrier carrier, PersistenceManager persistenceManager,
                              PathComputer pathComputer, FlowResourcesManager flowResourcesManager,
                              int pathAllocationRetriesLimit, int pathAllocationRetryDelay,
                              int speakerCommandRetriesLimit, MeterRegistry meterRegistry) {
        this.carrier = carrier;
        this.meterRegistry = meterRegistry;

        final RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.flowEventRepository = repositoryFactory.createFlowEventRepository();

        fsmFactory = new FlowRerouteFsm.Factory(carrier, persistenceManager, pathComputer, flowResourcesManager,
                pathAllocationRetriesLimit, pathAllocationRetryDelay,
                speakerCommandRetriesLimit, meterRegistry);
    }

    /**
     * Handles request for flow reroute.
     */
    public void handleRequest(FlowRerouteFact reroute) {
        log.debug("Handling flow reroute request with key {} and flow ID: {}", reroute.getKey(), reroute.getFlowId());
        final CommandContext commandContext = reroute.getCommandContext();

        try {
            checkRequestsCollision(reroute);
        } catch (Exception e) {
            log.error(e.getMessage());
            FlowRerouteFsm fsm = fsms.get(reroute.getKey());
            if (fsm != null) {
                removeIfFinished(fsm, reroute.getKey());
            }
            return;
        }

        final String flowId = reroute.getFlowId();
        if (isRerouteAlreadyInProgress(flowId)) {
            carrier.sendRerouteResultStatus(flowId, new RerouteInProgressError(),
                    reroute.getCommandContext().getCorrelationId());
            return;
        }

        final String key = reroute.getKey();
        FlowRerouteFsm fsm = fsmFactory.newInstance(commandContext, flowId);
        fsm.setTimer(LongTaskTimer.builder("fsm.active_execution")
                .tag("flow_id", flowId)
                .register(meterRegistry)
                .start());
        fsms.put(key, fsm);

        FlowRerouteContext context = FlowRerouteContext.builder()
                .flowId(flowId)
                .affectedIsl(reroute.getAffectedIsl())
                .forceReroute(reroute.isForceReroute())
                .effectivelyDown(reroute.isEffectivelyDown())
                .rerouteReason(reroute.getRerouteReason())
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

    private void checkRequestsCollision(FlowRerouteFact reroute) {
        if (fsms.containsKey(reroute.getKey())) {
            throw new IllegalStateException(String.format(
                    "Attempt to create a FSM with key %s, while there's another active FSM with the same key "
                            + "(flowId=\"%s\")", reroute.getKey(), reroute.getFlowId()));
        }

        String eventKey = reroute.getCommandContext().getCorrelationId();
        if (flowEventRepository.existsByTaskId(eventKey)) {
            throw new IllegalStateException(String.format(
                    "Attempt to reuse history key %s, but there's a history record(s) for it (flowId=\"%s\")",
                    eventKey, reroute.getFlowId()));
        }
    }

    private void removeIfFinished(FlowRerouteFsm fsm, String key) {
        if (fsm.isTerminated()) {
            log.debug("FSM with key {} is finished with state {}", key, fsm.getCurrentState());
            performHousekeeping(key);

            long duration = fsm.getTimer().stop();
            meterRegistry.timer("fsm.execution", "flow_id", fsm.getFlowId())
                    .record(duration, TimeUnit.NANOSECONDS);
        }
    }

    private void performHousekeeping(String key) {
        fsms.remove(key);
        carrier.cancelTimeoutCallback(key);
    }
}
