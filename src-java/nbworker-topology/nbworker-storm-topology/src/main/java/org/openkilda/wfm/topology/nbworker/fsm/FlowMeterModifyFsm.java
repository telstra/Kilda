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

import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyEvent.ERROR;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyEvent.NEXT;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyEvent.RESPONSE_RECEIVED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyState.FINISHED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyState.FINISHED_WITH_ERROR;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyState.GET_FLOW;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyState.INITIALIZED;
import static org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyState.MODIFY_METERS;

import org.openkilda.messaging.command.flow.MeterModifyCommandRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.meter.FlowMeterEntries;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.nbtopology.request.MeterModifyRequest;
import org.openkilda.model.Flow;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.topology.nbworker.bolts.FlowHubCarrier;
import org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyEvent;
import org.openkilda.wfm.topology.nbworker.fsm.FlowMeterModifyFsm.FlowMeterModifyState;
import org.openkilda.wfm.topology.nbworker.services.FlowOperationsService;

import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class FlowMeterModifyFsm
        extends AbstractStateMachine<FlowMeterModifyFsm, FlowMeterModifyState, FlowMeterModifyEvent, Object> {
    private static final String FINISHED_WITH_ERROR_METHOD_NAME = "finishedWithError";
    private static final String FINISHED_METHOD_NAME = "finished";

    private final String key;
    private final MeterModifyRequest request;
    private final FlowHubCarrier carrier;
    private final PersistenceManager persistenceManager;
    private String flowId;
    private FlowOperationsService service;
    private int awaitingRequests = 2;
    private Flow flow;
    private List<SwitchMeterEntries> switchMeterEntriesList = new ArrayList<>();

    public FlowMeterModifyFsm(FlowHubCarrier carrier, String key, MeterModifyRequest request,
                              PersistenceManager persistenceManager) {
        this.carrier = carrier;
        this.key = key;
        this.request = request;
        this.persistenceManager = persistenceManager;
    }

    /**
     * FSM builder.
     */
    public static StateMachineBuilder<FlowMeterModifyFsm, FlowMeterModifyState, FlowMeterModifyEvent,
            Object> builder() {
        StateMachineBuilder<FlowMeterModifyFsm, FlowMeterModifyState, FlowMeterModifyEvent, Object> builder =
                StateMachineBuilderFactory.create(
                        FlowMeterModifyFsm.class,
                        FlowMeterModifyState.class,
                        FlowMeterModifyEvent.class,
                        Object.class,
                        FlowHubCarrier.class,
                        String.class,
                        MeterModifyRequest.class,
                        PersistenceManager.class);

        builder.onEntry(INITIALIZED).callMethod("initialized");
        builder.externalTransition().from(INITIALIZED).to(GET_FLOW).on(NEXT)
                .callMethod("getFlow");
        builder.externalTransition().from(GET_FLOW).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);

        builder.externalTransition().from(GET_FLOW).to(MODIFY_METERS).on(NEXT)
                .callMethod("modifyMeters");
        builder.internalTransition().within(MODIFY_METERS).on(RESPONSE_RECEIVED).callMethod("responseReceived");

        builder.externalTransition().from(MODIFY_METERS).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);
        builder.externalTransition().from(MODIFY_METERS).to(FINISHED).on(NEXT).callMethod(FINISHED_METHOD_NAME);

        return builder;
    }

    public String getKey() {
        return key;
    }

    protected void initialized(FlowMeterModifyState from, FlowMeterModifyState to,
                               FlowMeterModifyEvent event, Object context) {
        log.info("Key: {}; FSM initialized", key);
        flowId = request.getFlowId();
        service = new FlowOperationsService(persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager());
    }

    protected void getFlow(FlowMeterModifyState from, FlowMeterModifyState to,
                           FlowMeterModifyEvent event, Object context) {
        log.info("Key: {}; Get flow from database", key);
        try {
            flow = service.getFlow(flowId);

        } catch (FlowNotFoundException e) {
            log.error("Key: {}; Flow {} not found", key, flowId);
            sendException(e.getMessage(), "Getting flow in FlowMeterModifyFsm", ErrorType.NOT_FOUND);
        }
    }

    protected void modifyMeters(FlowMeterModifyState from, FlowMeterModifyState to,
                                FlowMeterModifyEvent event, Object context) {
        log.info("Key: {}; Send commands to modify meters", key);
        SwitchId forwardSwitchId = flow.getForwardPath().getSrcSwitchId();
        SwitchId reverseSwitchId = flow.getReversePath().getSrcSwitchId();
        long bandwidth = flow.getForwardPath().getBandwidth();
        MeterId forwardMeterId = flow.getForwardPath().getMeterId();
        MeterId reverseMeterId = flow.getReversePath().getMeterId();

        if (forwardMeterId == null || reverseMeterId == null) {
            sendException(String.format("Flow '%s' is unmetered", flowId), "Modify meters in FlowMeterModifyFsm ",
                    ErrorType.REQUEST_INVALID);
            return;
        }

        carrier.sendCommandToSpeakerWorker(key,
                new MeterModifyCommandRequest(forwardSwitchId, forwardMeterId.getValue(), bandwidth));
        carrier.sendCommandToSpeakerWorker(key,
                new MeterModifyCommandRequest(reverseSwitchId, reverseMeterId.getValue(), bandwidth));
    }

    protected void responseReceived(FlowMeterModifyState from, FlowMeterModifyState to,
                                    FlowMeterModifyEvent event, Object context) {
        SwitchMeterEntries switchMeterEntries = (SwitchMeterEntries) context;
        switchMeterEntriesList.add(switchMeterEntries);
        log.info("Key: {}; Switch meters received for switch {}", key, switchMeterEntries.getSwitchId());
        if (--awaitingRequests == 0) {
            fire(NEXT);
        }
    }

    protected void finished(FlowMeterModifyState from, FlowMeterModifyState to,
                            FlowMeterModifyEvent event, Object context) {
        FlowMeterEntries response = new FlowMeterEntries(switchMeterEntriesList.get(0), switchMeterEntriesList.get(1));
        log.info("Key: {}; FSM finished work", key);
        carrier.endProcessing(key);
        carrier.sendToResponseSplitterBolt(key, Collections.singletonList(response));
    }

    protected void finishedWithError(FlowMeterModifyState from, FlowMeterModifyState to,
                                     FlowMeterModifyEvent event, Object context) {
        ErrorMessage message = (ErrorMessage) context;
        ErrorData data = message.getData();
        log.error("Key: {}; Message: {}", key, data.getErrorMessage());
        carrier.endProcessing(key);
        carrier.sendToMessageEncoder(key, new ErrorData(data.getErrorType(),
                String.format("Can't update meter: %s", data.getErrorMessage()), data.getErrorDescription()));
    }

    private void sendException(String message, String description, ErrorType errorType) {
        ErrorData errorData = new ErrorData(errorType, message, description);
        ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);
        fire(ERROR, errorMessage);
    }

    public enum FlowMeterModifyState {
        INITIALIZED,
        GET_FLOW,
        MODIFY_METERS,
        FINISHED_WITH_ERROR,
        FINISHED
    }

    public enum FlowMeterModifyEvent {
        NEXT,
        RESPONSE_RECEIVED,
        ERROR
    }
}
