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

package org.openkilda.wfm.topology.applications.bolt;

import static java.lang.String.format;

import org.openkilda.applications.AppData;
import org.openkilda.applications.AppMessage;
import org.openkilda.applications.command.CommandAppMessage;
import org.openkilda.applications.command.apps.CreateExclusion;
import org.openkilda.applications.command.apps.RemoveExclusion;
import org.openkilda.applications.error.ErrorAppData;
import org.openkilda.applications.error.ErrorAppType;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.MessageData;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.apps.FlowAddAppRequest;
import org.openkilda.messaging.command.apps.FlowAppsReadRequest;
import org.openkilda.messaging.command.apps.FlowRemoveAppRequest;
import org.openkilda.messaging.command.switches.RemoveExclusionRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.error.SwitchPropertiesNotFoundException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.topology.applications.AppsManagerCarrier;
import org.openkilda.wfm.topology.applications.AppsTopology.ComponentId;
import org.openkilda.wfm.topology.applications.service.AppsManagerService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class AppsManager extends AbstractBolt {
    public static final String BOLT_ID = ComponentId.APPS_MANAGER.toString();

    public static final String FIELD_ID_KEY = MessageKafkaTranslator.FIELD_ID_KEY;
    public static final String FIELD_ID_PAYLOAD = MessageKafkaTranslator.FIELD_ID_PAYLOAD;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;
    private final FlowResourcesConfig flowResourcesConfig;

    private transient AppsManagerService service;
    private transient AppsManagerCarrier carrier;

    public AppsManager(PersistenceManager persistenceManager, FlowResourcesConfig flowResourcesConfig) {
        this.persistenceManager = persistenceManager;
        this.flowResourcesConfig = flowResourcesConfig;
    }

    @Override
    protected void init() {
        carrier = new AppsManagerCarrierImpl();
        service = new AppsManagerService(carrier, persistenceManager, flowResourcesConfig);
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (ComponentId.APPS_NB_SPOUT.toString().equals(source)) {
            processRequestInput(input);
        } else if (ComponentId.APPS_SPOUT.toString().equals(source)) {
            processInput(input);
        } else {
            unhandledInput(input);
        }
    }

    private void processRequestInput(Tuple input) throws PipelineException {
        Message message = pullValue(input, FIELD_ID_PAYLOAD, Message.class);
        try {
            processMessage(message);
        } catch (FlowNotFoundException e) {
            log.error("Flow not found", e);
            carrier.emitErrorMessage(ErrorType.NOT_FOUND, e.getMessage());
        } catch (SwitchPropertiesNotFoundException e) {
            log.error("Switch properties not found", e);
            carrier.emitErrorMessage(ErrorType.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("Invalid request parameters", e);
            carrier.emitErrorMessage(ErrorType.PARAMETERS_INVALID, e.getMessage());
        } catch (Exception e) {
            log.error("Unhandled exception", e);
            carrier.emitErrorMessage(ErrorType.INTERNAL_ERROR, e.getMessage());
        }
    }

    private void processInput(Tuple input) throws PipelineException {
        AppMessage message = pullValue(input, FIELD_ID_PAYLOAD, AppMessage.class);
        try {
            processAppMessage(message);
        } catch (FlowNotFoundException e) {
            log.error("Flow not found", e);
            carrier.emitAppError(ErrorAppType.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("Invalid request parameters", e);
            carrier.emitAppError(ErrorAppType.PARAMETERS_INVALID, e.getMessage());
        } catch (Exception e) {
            log.error("Unhandled exception", e);
            carrier.emitAppError(ErrorAppType.INTERNAL_ERROR, e.getMessage());
        }
    }

    private void processMessage(Message message) throws FlowNotFoundException {
        if (message instanceof CommandMessage) {
            processCommandData(((CommandMessage) message).getData());
        } else {
            throw new UnsupportedOperationException(format("Unexpected message type \"%s\"", message.getClass()));
        }
    }

    private void processAppMessage(AppMessage message) throws FlowNotFoundException {

        if (message instanceof CommandAppMessage) {
            processCommandAppData(message.getPayload());
        } else {
            throw new UnsupportedOperationException(format("Unexpected message type \"%s\"", message.getClass()));
        }
    }

    private void processCommandData(CommandData payload)
            throws FlowNotFoundException, SwitchPropertiesNotFoundException {

        if (payload instanceof FlowAppsReadRequest) {
            service.getEnabledFlowApplications(((FlowAppsReadRequest) payload).getFlowId());
        } else if (payload instanceof FlowAddAppRequest) {
            service.addFlowApplication(getCommandContext().getCorrelationId(), (FlowAddAppRequest) payload);
        } else if (payload instanceof FlowRemoveAppRequest) {
            service.removeFlowApplication(getCommandContext().getCorrelationId(),
                    (FlowRemoveAppRequest) payload);
        } else if (payload instanceof RemoveExclusionRequest) {
            service.processRemoveExclusion((RemoveExclusionRequest) payload);
        } else {
            throw new UnsupportedOperationException(format("Unexpected message payload \"%s\"", payload.getClass()));
        }
    }

    private void processCommandAppData(AppData payload) throws FlowNotFoundException {

        if (payload instanceof CreateExclusion) {
            service.processCreateExclusion((CreateExclusion) payload);
        } else if (payload instanceof RemoveExclusion) {
            service.processRemoveExclusion((RemoveExclusion) payload);
        } else {
            throw new UnsupportedOperationException(format("Unexpected app message payload \"%s\"",
                    payload.getClass()));
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(NorthboundEncoder.INPUT_STREAM_ID, STREAM_FIELDS);
        streamManager.declareStream(SpeakerEncoder.INPUT_STREAM_ID, STREAM_FIELDS);
        streamManager.declareStream(NotificationsEncoder.INPUT_STREAM_ID, STREAM_FIELDS);
    }

    private class AppsManagerCarrierImpl implements AppsManagerCarrier {

        @Override
        public void emitErrorMessage(ErrorType errorType, String errorMessage) {
            ErrorData errorData = new ErrorData(errorType, errorMessage, "Error in the Apps Manager");
            emitNorthboundResponse(errorData);
        }

        @Override
        public void emitNorthboundResponse(MessageData payload) {
            String key = getCommandContext().getCorrelationId();
            emit(NorthboundEncoder.INPUT_STREAM_ID, getCurrentTuple(), makeTuple(key, payload));
        }

        @Override
        public void emitSpeakerCommand(CommandData payload) {
            String key = getCommandContext().getCorrelationId();
            emit(SpeakerEncoder.INPUT_STREAM_ID, getCurrentTuple(), makeTuple(key, payload));
        }

        @Override
        public void emitAppError(ErrorAppType errorType, String errorMessage) {
            ErrorAppData errorData = new ErrorAppData(errorType, errorMessage, "Error in the Apps Manager");
            emitNotification(errorData);
        }

        @Override
        public void emitNotification(AppData payload) {
            String key = getCommandContext().getCorrelationId();
            emit(NotificationsEncoder.INPUT_STREAM_ID, getCurrentTuple(), makeAppTuple(key, payload));
        }

        private Values makeTuple(String key, MessageData payload) {
            return new Values(key, payload, getCommandContext());
        }

        private Values makeAppTuple(String key, AppData payload) {
            return new Values(key, payload, getCommandContext());
        }
    }
}
