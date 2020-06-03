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

package org.openkilda.wfm.topology.nbworker.fsm;

import static java.lang.String.format;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationEvent.ERROR;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationEvent.METERS_RECEIVED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationEvent.NEXT;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationEvent.RULES_RECEIVED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState.FINISHED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState.FINISHED_WITH_ERROR;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState.INITIALIZED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState.RECEIVE_DATA;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState.VALIDATE_FLOW;

import org.openkilda.messaging.command.switches.DumpMetersForNbworkerRequest;
import org.openkilda.messaging.command.switches.DumpRulesForNbworkerRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.nbtopology.request.FlowValidationRequest;
import org.openkilda.messaging.nbtopology.response.FlowValidationResponse;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.IllegalFlowStateException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.topology.nbworker.bolts.FlowValidationHubCarrier;
import org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationEvent;
import org.openkilda.wfm.topology.nbworker.fsm.FlowValidationFsm.FlowValidationState;
import org.openkilda.wfm.topology.nbworker.services.FlowValidationService;

import io.micrometer.core.instrument.LongTaskTimer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FlowValidationFsm
        extends AbstractStateMachine<FlowValidationFsm, FlowValidationState, FlowValidationEvent, Object> {
    private static final String FINISHED_WITH_ERROR_METHOD_NAME = "finishedWithError";
    private static final String FINISHED_METHOD_NAME = "finished";

    private final String key;
    @Getter
    private final FlowValidationRequest request;
    private final FlowValidationHubCarrier carrier;
    private final PersistenceManager persistenceManager;
    private final FlowResourcesConfig flowResourcesConfig;
    private String flowId;
    private FlowValidationService service;
    private int awaitingRules;
    private int awaitingMeters;
    private List<SwitchFlowEntries> receivedRules = new ArrayList<>();
    private List<SwitchMeterEntries> receivedMeters = new ArrayList<>();
    private List<FlowValidationResponse> response;

    @Setter @Getter
    private LongTaskTimer.Sample timer;

    public FlowValidationFsm(FlowValidationHubCarrier carrier, String key, FlowValidationRequest request,
                             PersistenceManager persistenceManager, FlowResourcesConfig flowResourcesConfig) {
        this.carrier = carrier;
        this.key = key;
        this.request = request;
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    /**
     * FSM builder.
     */
    public static StateMachineBuilder<FlowValidationFsm, FlowValidationState, FlowValidationEvent, Object> builder() {
        StateMachineBuilder<FlowValidationFsm, FlowValidationState, FlowValidationEvent, Object> builder =
                StateMachineBuilderFactory.create(
                        FlowValidationFsm.class,
                        FlowValidationState.class,
                        FlowValidationEvent.class,
                        Object.class,
                        FlowValidationHubCarrier.class,
                        String.class,
                        FlowValidationRequest.class,
                        PersistenceManager.class,
                        FlowResourcesConfig.class);

        builder.onEntry(INITIALIZED).callMethod("initialized");
        builder.externalTransition().from(INITIALIZED).to(RECEIVE_DATA).on(NEXT)
                .callMethod("receiveData");
        builder.internalTransition().within(RECEIVE_DATA).on(RULES_RECEIVED).callMethod("receivedRules");
        builder.internalTransition().within(RECEIVE_DATA).on(METERS_RECEIVED).callMethod("receivedMeters");

        builder.externalTransition().from(RECEIVE_DATA).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);
        builder.externalTransition().from(RECEIVE_DATA).to(VALIDATE_FLOW).on(NEXT)
                .callMethod("validateFlow");

        builder.externalTransition().from(VALIDATE_FLOW).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);
        builder.externalTransition().from(VALIDATE_FLOW).to(FINISHED).on(NEXT).callMethod(FINISHED_METHOD_NAME);

        return builder;
    }

    public String getKey() {
        return key;
    }

    protected void initialized(FlowValidationState from, FlowValidationState to,
                               FlowValidationEvent event, Object context) {
        flowId = request.getFlowId();
        log.info("Key: {}, flow: {}; FSM initialized", key, flowId);

        service = new FlowValidationService(persistenceManager, flowResourcesConfig,
                carrier.getFlowMeterMinBurstSizeInKbits(), carrier.getFlowMeterBurstCoefficient());
    }

    protected void receiveData(FlowValidationState from, FlowValidationState to,
                               FlowValidationEvent event, Object context) {
        try {
            service.checkFlowStatus(flowId);
        } catch (FlowNotFoundException e) {
            log.error("Key: {}; Flow {} not found when sending commands to SpeakerWorkerBolt", key, flowId, e);
            sendException(e.getMessage(), "Receiving rules operation in FlowValidationFsm", ErrorType.NOT_FOUND);
            return;
        } catch (IllegalFlowStateException e) {
            log.error("Key: {}; Could not validate flow: Flow {} is in DOWN state", key, flowId, e);
            sendException("Could not validate flow",
                    format("Could not validate flow: Flow %s is in DOWN state", flowId),
                    ErrorType.UNPROCESSABLE_REQUEST);
            return;
        }

        List<SwitchId> switchIds = service.getSwitchIdListByFlowId(flowId);

        awaitingRules = switchIds.size();
        log.debug("Key: {}; Send commands to get rules on the switches", key);
        switchIds.forEach(switchId ->
                carrier.sendCommandToSpeakerWorker(key, new DumpRulesForNbworkerRequest(switchId)));

        log.debug("Key: {}; Send commands to get meters on the switches", key);
        awaitingMeters = switchIds.size();
        // FIXME(surabujin): - should we request meters only for termination switches?..
        switchIds.forEach(switchId ->
                carrier.sendCommandToSpeakerWorker(key, new DumpMetersForNbworkerRequest(switchId)));
    }

    protected void receivedRules(FlowValidationState from, FlowValidationState to,
                                 FlowValidationEvent event, Object context) {
        SwitchFlowEntries switchFlowEntries = (SwitchFlowEntries) context;
        log.info("Key: {}; Switch rules received for switch {}", key, switchFlowEntries.getSwitchId());
        receivedRules.add(switchFlowEntries);
        awaitingRules--;
        checkOfCompleteDataCollection();
    }

    protected void receivedMeters(FlowValidationState from, FlowValidationState to,
                                  FlowValidationEvent event, Object context) {
        SwitchMeterEntries switchMeterEntries = (SwitchMeterEntries) context;
        log.info("Key: {}; Switch meters received for switch {}", key, switchMeterEntries.getSwitchId());
        receivedMeters.add(switchMeterEntries);
        awaitingMeters--;
        checkOfCompleteDataCollection();
    }

    private void checkOfCompleteDataCollection() {
        if (awaitingRules == 0 && awaitingMeters == 0) {
            fire(NEXT);
        }
    }

    protected void validateFlow(FlowValidationState from, FlowValidationState to,
                                FlowValidationEvent event, Object context) {
        try {
            response = service.validateFlow(flowId, receivedRules, receivedMeters);
        } catch (FlowNotFoundException e) {
            log.error("Key: {}; Flow {} not found during flow validation", key, flowId, e);
            sendException(e.getMessage(), "Flow validation operation in FlowValidationFsm", ErrorType.NOT_FOUND);
        } catch (SwitchNotFoundException e) {
            log.error("Key: {}; {}", key, e.getMessage(), e);
            sendException(e.getMessage(), "Flow validation operation in FlowValidationFsm", ErrorType.NOT_FOUND);
        } catch (Exception e) {
            log.error("Key: {}; {}", key, e.getMessage(), e);
            sendException(e.getMessage(), "Flow validation operation in FlowValidationFsm", ErrorType.INTERNAL_ERROR);
        }
    }

    protected void finished(FlowValidationState from, FlowValidationState to,
                            FlowValidationEvent event, Object context) {
        log.info("Key: {}; FSM finished work", key);
        carrier.endProcessing(key);
        carrier.sendToResponseSplitterBolt(key, response);
    }

    protected void finishedWithError(FlowValidationState from, FlowValidationState to,
                                     FlowValidationEvent event, Object context) {
        ErrorMessage message = (ErrorMessage) context;
        ErrorData data = message.getData();
        log.error("Key: {}; Message: {}", key, data.getErrorMessage());
        carrier.endProcessing(key);
        carrier.sendToMessageEncoder(key, new ErrorData(data.getErrorType(),
                format("Could not validate flow: %s", data.getErrorMessage()), data.getErrorDescription()));
    }

    private void sendException(String message, String description, ErrorType errorType) {
        ErrorData errorData = new ErrorData(errorType, message, description);
        ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);
        fire(ERROR, errorMessage);
    }

    public enum FlowValidationState {
        INITIALIZED,
        RECEIVE_DATA,
        VALIDATE_FLOW,
        FINISHED_WITH_ERROR,
        FINISHED
    }

    public enum FlowValidationEvent {
        NEXT,
        RULES_RECEIVED,
        METERS_RECEIVED,
        ERROR
    }
}
