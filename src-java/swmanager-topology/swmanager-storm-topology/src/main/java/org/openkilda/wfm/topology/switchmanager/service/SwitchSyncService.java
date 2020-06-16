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

package org.openkilda.wfm.topology.switchmanager.service;

import org.openkilda.messaging.command.switches.SwitchValidateRequest;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.info.flow.FlowReinstallResponse;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState;
import org.openkilda.wfm.topology.switchmanager.model.ValidationResult;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class SwitchSyncService {
    private Map<String, SwitchSyncFsm> fsms = new HashMap<>();

    @VisibleForTesting
    CommandBuilder commandBuilder;
    private SwitchManagerCarrier carrier;
    private StateMachineBuilder<SwitchSyncFsm, SwitchSyncState, SwitchSyncEvent, Object> builder;

    public SwitchSyncService(SwitchManagerCarrier carrier, PersistenceManager persistenceManager,
                             FlowResourcesConfig flowResourcesConfig) {
        this.carrier = carrier;
        this.commandBuilder = new CommandBuilder(persistenceManager, flowResourcesConfig);
        this.builder = SwitchSyncFsm.builder();
    }

    /**
     * The entry point for switch sync request processing.
     */
    public void handleSwitchSync(String key, SwitchValidateRequest request, ValidationResult validationResult) {
        SwitchSyncFsm fsm =
                builder.newStateMachine(SwitchSyncState.INITIALIZED, carrier, key, commandBuilder, request,
                        validationResult);

        process(fsm);
    }

    /**
     * Inject install rules response into FSM.
     */
    public void handleInstallRulesResponse(String key) {
        SwitchSyncFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchSyncEvent.RULES_INSTALLED);
        process(fsm);
    }

    /**
     * Inject remove rules response into FSM.
     */
    public void handleRemoveRulesResponse(String key) {
        SwitchSyncFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchSyncEvent.RULES_REMOVED);
        process(fsm);
    }

    /**
     * Inject reinstall default rules response into FSM.
     */
    public void handleReinstallDefaultRulesResponse(String key, FlowReinstallResponse response) {
        SwitchSyncFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchSyncEvent.RULES_REINSTALLED, response);
        process(fsm);
    }

    /**
     * Inject remove meters response into FSM.
     */
    public void handleRemoveMetersResponse(String key) {
        SwitchSyncFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchSyncEvent.METERS_REMOVED);
        process(fsm);
    }

    /**
     * Inject "global" timeout error into FSM.
     */
    public void handleTaskTimeout(String key) {
        SwitchSyncFsm fsm = fsms.get(key);
        if (fsm == null) {
            return;
        }

        fsm.fire(SwitchSyncEvent.TIMEOUT);
        process(fsm);
    }

    /**
     * Inject error into FSM.
     */
    public void handleTaskError(String key, ErrorMessage message) {
        SwitchSyncFsm fsm = fsms.get(key);
        if (fsm == null) {
            return;
        }

        fsm.fire(SwitchSyncEvent.ERROR, message);
        process(fsm);
    }

    private void logFsmNotFound(String key) {
        log.warn("Switch sync FSM with key {} not found", key);
    }

    void process(SwitchSyncFsm fsm) {
        final List<SwitchSyncState> stopStates = Arrays.asList(
                SwitchSyncState.RULES_COMMANDS_SEND,
                SwitchSyncState.METERS_COMMANDS_SEND,
                SwitchSyncState.FINISHED,
                SwitchSyncState.FINISHED_WITH_ERROR
        );

        while (!stopStates.contains(fsm.getCurrentState())) {
            fsms.put(fsm.getKey(), fsm);
            fsm.fire(SwitchSyncEvent.NEXT);
        }

        final List<SwitchSyncState> exitStates = Arrays.asList(
                SwitchSyncState.FINISHED,
                SwitchSyncState.FINISHED_WITH_ERROR
        );

        if (exitStates.contains(fsm.getCurrentState())) {
            fsms.remove(fsm.getKey());
        }
    }
}
