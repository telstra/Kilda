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

package org.openkilda.wfm.topology.nbworker.services;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.meter.SwitchMeterUnsupported;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.nbtopology.request.FlowValidationRequest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.topology.nbworker.bolts.FlowValidationHubCarrier;
import org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm;
import org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationEvent;
import org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState;

import io.micrometer.core.instrument.LongTaskTimer;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FlowValidationHubService {
    private Map<String, FlowValidationFsm> fsms = new HashMap<>();
    private boolean active = true;
    private FlowValidationHubCarrier defaultCarrier;
    private PersistenceManager persistenceManager;
    private FlowResourcesConfig flowResourcesConfig;
    private StateMachineBuilder<FlowValidationFsm, FlowValidationState, FlowValidationEvent, Object> builder;

    public FlowValidationHubService(PersistenceManager persistenceManager, FlowResourcesConfig flowResourcesConfig,
                                    FlowValidationHubCarrier defaultCarrier) {
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;
        this.builder = FlowValidationFsm.builder();
        this.defaultCarrier = defaultCarrier;
    }

    /**
     * Handle flow validation request.
     */
    public void handleFlowValidationRequest(String key, FlowValidationRequest request,
                                            FlowValidationHubCarrier carrier) {
        FlowValidationFsm fsm =
                builder.newStateMachine(FlowValidationState.INITIALIZED, carrier, key, request,
                        persistenceManager, flowResourcesConfig);
        MeterRegistryHolder.getRegistry().ifPresent(registry ->
                fsm.setTimer(LongTaskTimer.builder("fsm.active_execution")
                        .register(registry)
                        .start()));
        process(fsm);
    }

    /**
     * Handle response from speaker worker.
     */
    public void handleAsyncResponse(String key, Message message) {
        FlowValidationFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        if (message instanceof InfoMessage) {
            InfoData data = ((InfoMessage) message).getData();
            if (data instanceof SwitchFlowEntries) {
                fsm.fire(FlowValidationEvent.RULES_RECEIVED, data);
            } else if (data instanceof SwitchMeterEntries) {
                fsm.fire(FlowValidationEvent.METERS_RECEIVED, data);
            } else if (data instanceof SwitchMeterUnsupported) {
                SwitchMeterUnsupported meterUnsupported = (SwitchMeterUnsupported) data;
                log.info("Key: {}; Meters unsupported for switch '{};", key, meterUnsupported.getSwitchId());
                fsm.fire(FlowValidationEvent.METERS_RECEIVED, SwitchMeterEntries.builder()
                        .switchId(meterUnsupported.getSwitchId())
                        .meterEntries(Collections.emptyList())
                        .build());
            } else {
                log.warn("Key: {}; Unhandled message {}", key, message);
            }
        } else if (message instanceof ErrorMessage) {
            fsm.fire(FlowValidationEvent.ERROR, message);
        }

        process(fsm);
    }

    /**
     * Handle timeout event.
     */
    public void handleTaskTimeout(String key) {
        FlowValidationFsm fsm = fsms.get(key);
        if (fsm == null) {
            return;
        }

        ErrorData errorData = new ErrorData(ErrorType.OPERATION_TIMED_OUT, "Flow validation failed by timeout",
                "Error in FlowValidationHubService");
        ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);
        fsm.fire(FlowValidationEvent.ERROR, errorMessage);

        process(fsm);
    }

    private void logFsmNotFound(String key) {
        log.warn("Flow validate FSM with key {} not found", key);
    }

    private void process(FlowValidationFsm fsm) {
        final List<FlowValidationState> stopStates = Arrays.asList(
                FlowValidationState.RECEIVE_DATA,
                FlowValidationState.FINISHED,
                FlowValidationState.FINISHED_WITH_ERROR
        );

        while (!stopStates.contains(fsm.getCurrentState())) {
            fsms.put(fsm.getKey(), fsm);
            fsm.fire(FlowValidationEvent.NEXT);
        }

        final List<FlowValidationState> exitStates = Arrays.asList(
                FlowValidationState.FINISHED,
                FlowValidationState.FINISHED_WITH_ERROR
        );

        if (exitStates.contains(fsm.getCurrentState())) {
            fsms.remove(fsm.getKey());
            if (fsms.isEmpty() && !active) {
                defaultCarrier.sendInactive();
            }

            MeterRegistryHolder.getRegistry().ifPresent(registry -> {
                long duration = fsm.getTimer().stop();
                registry.timer("fsm.execution")
                        .record(duration, TimeUnit.NANOSECONDS);
                if (fsm.getCurrentState() == FlowValidationState.FINISHED) {
                    registry.timer("fsm.execution.success")
                            .record(duration, TimeUnit.NANOSECONDS);
                } else if (fsm.getCurrentState() == FlowValidationState.FINISHED_WITH_ERROR) {
                    registry.timer("fsm.execution.failed")
                            .record(duration, TimeUnit.NANOSECONDS);
                }
            });
        }
    }


    /**
     * Deactivates service.
     */
    public boolean deactivate() {
        active = false;
        if (fsms.isEmpty()) {
            return true;
        }
        return false;
    }

    public void activate() {
        active = true;
    }
}
