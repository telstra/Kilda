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
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultFlowEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.info.rule.SwitchGroupEntries;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.switchmanager.error.OperationTimeoutException;
import org.openkilda.wfm.topology.switchmanager.error.SpeakerFailureException;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm.SwitchValidateContext;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm.SwitchValidateEvent;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchValidateFsm.SwitchValidateState;

import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class SwitchValidateService {

    private final Map<String, SwitchValidateFsm> fsms = new HashMap<>();

    private final ValidationService validationService;
    private final SwitchManagerCarrier carrier;
    private final StateMachineBuilder<
                SwitchValidateFsm, SwitchValidateState, SwitchValidateEvent, SwitchValidateContext> builder;
    private final FsmExecutor<
            SwitchValidateFsm, SwitchValidateState, SwitchValidateEvent, SwitchValidateContext> fsmExecutor;

    private final RepositoryFactory repositoryFactory;

    public SwitchValidateService(
            SwitchManagerCarrier carrier, PersistenceManager persistenceManager, ValidationService validationService) {
        this.carrier = carrier;
        this.builder = SwitchValidateFsm.builder();
        this.fsmExecutor = new FsmExecutor<>(SwitchValidateEvent.NEXT);
        this.validationService = validationService;
        this.repositoryFactory = persistenceManager.getRepositoryFactory();
    }

    /**
     * Entry point for SwitchValidateRequest processing.
     */
    public void handleSwitchValidateRequest(String key, SwitchValidateRequest request) {
        SwitchValidateFsm fsm =
                builder.newStateMachine(
                        SwitchValidateState.START, carrier, key, request, validationService,
                        repositoryFactory);
        fsms.put(key, fsm);

        fsm.start();
        handle(fsm, SwitchValidateEvent.NEXT, SwitchValidateContext.builder().build());
    }

    public void handleFlowEntriesResponse(String key, SwitchFlowEntries data) {
        handle(key, SwitchValidateEvent.RULES_RECEIVED,
                SwitchValidateContext.builder().flowEntries(data.getFlowEntries()).build());
    }

    public void handleGroupEntriesResponse(String key, SwitchGroupEntries data) {
        handle(key, SwitchValidateEvent.GROUPS_RECEIVED,
                SwitchValidateContext.builder().groupEntries(data.getGroupEntries()).build());
    }

    public void handleExpectedDefaultFlowEntriesResponse(String key, SwitchExpectedDefaultFlowEntries data) {
        handle(key, SwitchValidateEvent.EXPECTED_DEFAULT_RULES_RECEIVED,
                SwitchValidateContext.builder().flowEntries(data.getFlowEntries()).build());
    }

    public void handleExpectedDefaultMeterEntriesResponse(String key, SwitchExpectedDefaultMeterEntries data) {
        handle(key, SwitchValidateEvent.EXPECTED_DEFAULT_METERS_RECEIVED,
                SwitchValidateContext.builder().meterEntries(data.getMeterEntries()).build());
    }

    public void handleMeterEntriesResponse(String key, SwitchMeterEntries data) {
        handle(key, SwitchValidateEvent.METERS_RECEIVED,
                SwitchValidateContext.builder().meterEntries(data.getMeterEntries()).build());
    }

    public void handleMetersUnsupportedResponse(String key) {
        handle(key, SwitchValidateEvent.METERS_UNSUPPORTED, SwitchValidateContext.builder().build());
    }

    /**
     * Inject speaker error reseponse into FSM.
     */
    public void handleTaskError(String key, ErrorMessage message) {
        SwitchValidateContext context = SwitchValidateContext.builder()
                .error(new SpeakerFailureException(message.getData()))
                .build();
        handle(key, SwitchValidateEvent.ERROR, context);
    }

    /**
     * Inject global timeout error into FSM.
     */
    public void handleTaskTimeout(String key) {
        Optional<SwitchValidateFsm> potential = lookupFsm(key);
        if (potential.isPresent()) {
            SwitchValidateFsm fsm = potential.get();
            OperationTimeoutException error = new OperationTimeoutException(fsm.getSwitchId());
            handle(fsm, SwitchValidateEvent.ERROR, SwitchValidateContext.builder().error(error).build());
        }
    }

    private void handle(String key, SwitchValidateEvent event, SwitchValidateContext context) {
        Optional<SwitchValidateFsm> fsm = lookupFsm(key);
        if (!fsm.isPresent()) {
            log.warn("Switch validate FSM with key {} not found", key);
            return;
        }
        handle(fsm.get(), event, context);
    }

    private void handle(SwitchValidateFsm fsm, SwitchValidateEvent event, SwitchValidateContext context) {
        fsmExecutor.fire(fsm, event, context);
        removeIfCompleted(fsm);
    }

    private Optional<SwitchValidateFsm> lookupFsm(String key) {
        return Optional.ofNullable(fsms.get(key));
    }

    void removeIfCompleted(SwitchValidateFsm fsm) {
        if (fsm.isTerminated()) {
            log.info("Switch {} validation FSM have reached termination state (key={})",
                    fsm.getSwitchId(), fsm.getKey());
            fsms.remove(fsm.getKey());
        }
    }
}
