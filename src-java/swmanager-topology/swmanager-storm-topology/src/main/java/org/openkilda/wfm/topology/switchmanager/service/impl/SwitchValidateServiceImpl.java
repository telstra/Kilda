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

package org.openkilda.wfm.topology.switchmanager.service.impl;

import org.openkilda.messaging.command.switches.SwitchValidateRequest;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultFlowEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm.SwitchValidateEvent;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm.SwitchValidateState;
import org.openkilda.wfm.topology.switchmanager.service.SwitchManagerCarrier;
import org.openkilda.wfm.topology.switchmanager.service.SwitchValidateService;
import org.openkilda.wfm.topology.switchmanager.service.ValidationService;
import java.util.LinkedHashMap;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class SwitchValidateServiceImpl implements SwitchValidateService {

    private Map<String, SwitchValidateFsm> fsms = new HashMap<>();

    @VisibleForTesting
    ValidationService validationService;
    private SwitchManagerCarrier carrier;
    private StateMachineBuilder<SwitchValidateFsm, SwitchValidateState, SwitchValidateEvent, Object> builder;
    private RepositoryFactory repositoryFactory;

    public SwitchValidateServiceImpl(SwitchManagerCarrier carrier, PersistenceManager persistenceManager) {
        this.carrier = carrier;
        this.builder = SwitchValidateFsm.builder();
        this.validationService = new ValidationServiceImpl(persistenceManager, carrier.getTopologyConfig());
        this.repositoryFactory = persistenceManager.getRepositoryFactory();
    }

    @Override
    public void handleSwitchValidateRequest(String key, SwitchValidateRequest request) {
        SwitchValidateFsm fsm =
                builder.newStateMachine(SwitchValidateState.INITIALIZED, carrier, key, request, validationService,
                        repositoryFactory);

        process(fsm);
    }

    @Override
    public void handleFlowEntriesResponse(String key, SwitchFlowEntries data) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchValidateEvent.RULES_RECEIVED, data.getFlowEntries());
        process(fsm);
    }

    @Override
    public void handleExpectedDefaultFlowEntriesResponse(String key, SwitchExpectedDefaultFlowEntries data) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchValidateEvent.EXPECTED_DEFAULT_RULES_RECEIVED, data.getFlowEntries());
        process(fsm);
    }

    @Override
    public void handleExpectedDefaultMeterEntriesResponse(String key, SwitchExpectedDefaultMeterEntries data) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchValidateEvent.EXPECTED_DEFAULT_METERS_RECEIVED, data.getMeterEntries());
        process(fsm);
    }

    @Override
    public void handleMeterEntriesResponse(String key, SwitchMeterEntries data) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchValidateEvent.METERS_RECEIVED, data.getMeterEntries());
        process(fsm);
    }

    @Override
    public void handleMetersUnsupportedResponse(String key) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            logFsmNotFound(key);
            return;
        }

        fsm.fire(SwitchValidateEvent.METERS_UNSUPPORTED);
        process(fsm);
    }

    @Override
    public void handleTaskTimeout(String key) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            return;
        }

        fsm.fire(SwitchValidateEvent.TIMEOUT);
        process(fsm);
    }

    @Override
    public void handleTaskError(String key, ErrorMessage message) {
        SwitchValidateFsm fsm = fsms.get(key);
        if (fsm == null) {
            return;
        }

        fsm.fire(SwitchValidateEvent.ERROR, message);
        process(fsm);
    }

    private void logFsmNotFound(String key) {
        log.warn("Switch validate FSM with key {} not found", key);
    }

    void process(SwitchValidateFsm fsm) {
        final List<SwitchValidateState> stopStates = Arrays.asList(
                SwitchValidateState.RECEIVE_DATA,
                SwitchValidateState.FINISHED,
                SwitchValidateState.FINISHED_WITH_ERROR
        );

        while (!stopStates.contains(fsm.getCurrentState())) {
            fsms.put(fsm.getKey(), fsm);
            fsm.fire(SwitchValidateEvent.NEXT);
        }

        final List<SwitchValidateState> exitStates = Arrays.asList(
                SwitchValidateState.FINISHED,
                SwitchValidateState.FINISHED_WITH_ERROR
        );

        if (exitStates.contains(fsm.getCurrentState())) {
            fsms.remove(fsm.getKey());
        }
    }
}
