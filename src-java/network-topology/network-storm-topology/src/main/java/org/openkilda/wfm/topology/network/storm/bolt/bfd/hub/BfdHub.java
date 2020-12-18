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

package org.openkilda.wfm.topology.network.storm.bolt.bfd.hub;

import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.info.grpc.CreateLogicalPortResponse;
import org.openkilda.messaging.info.grpc.DeleteLogicalPortResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.TaskIdBasedKeyFactory;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.error.ControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.BfdSessionData;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.service.IBfdGlobalToggleCarrier;
import org.openkilda.wfm.topology.network.service.IBfdLogicalPortCarrier;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;
import org.openkilda.wfm.topology.network.service.NetworkBfdGlobalToggleService;
import org.openkilda.wfm.topology.network.service.NetworkBfdLogicalPortService;
import org.openkilda.wfm.topology.network.service.NetworkBfdSessionService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.BfdWorker;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerPortCreateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerPortDeleteCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerSessionCreateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerSessionRemoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.bcast.ISpeakerBcastConsumer;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.bcast.SpeakerBcast;
import org.openkilda.wfm.topology.network.storm.bolt.sw.SwitchHandler;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslBfdStatusUpdateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslCommand;
import org.openkilda.wfm.topology.network.utils.EndpointStatusMonitor;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusMonitor;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class BfdHub extends AbstractBolt
        implements IBfdLogicalPortCarrier, IBfdSessionCarrier, IBfdGlobalToggleCarrier, ISpeakerBcastConsumer {
    public static final String BOLT_ID = ComponentId.BFD_PORT_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = SwitchHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = SwitchHandler.FIELD_ID_PORT_NUMBER;
    public static final String FIELD_ID_COMMAND_KEY = MessageKafkaTranslator.KEY_FIELD;
    public static final String FIELD_ID_COMMAND = SwitchHandler.FIELD_ID_COMMAND;

    public static final String STREAM_WORKER_ID = "worker";
    public static final Fields STREAM_WORKER_FIELDS = new Fields(
            FIELD_ID_COMMAND_KEY, FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_UNIISL_ID = "uni-isl";
    public static final Fields STREAM_UNIISL_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
                                                                 FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    private final NetworkOptions options;
    private final PersistenceManager persistenceManager;

    private transient SwitchOnlineStatusMonitor switchOnlineStatusMonitor;
    private transient EndpointStatusMonitor endpointStatusMonitor;

    private transient NetworkBfdLogicalPortService logicalPortService;
    private transient NetworkBfdSessionService sessionService;
    private transient NetworkBfdGlobalToggleService globalToggleService;
    private transient TaskIdBasedKeyFactory requestIdFactory;

    public BfdHub(NetworkOptions options, PersistenceManager persistenceManager) {
        this.options = options;
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (SwitchHandler.BOLT_ID.equals(source)) {
            handleSwitchCommand(input);
        } else if (IslHandler.BOLT_ID.equals(source)) {
            handleIslCommand(input);
        } else if (BfdWorker.BOLT_ID.equals(source)) {
            handleWorkerCommand(input);
        } else if (SpeakerRouter.BOLT_ID.equals(source)) {
            handleSpeakerBcast(input);
        } else {
            unhandledInput(input);
        }
    }

    @Override
    protected void handleException(Exception error) throws Exception {
        try {
            super.handleException(error);
        } catch (ControllerNotFoundException e) {
            log.error(e.getMessage());
        }
    }

    private void handleSwitchCommand(Tuple input) throws PipelineException {
        handleCommand(input, SwitchHandler.FIELD_ID_COMMAND);
    }

    private void handleIslCommand(Tuple input) throws PipelineException {
        handleCommand(input, IslHandler.FIELD_ID_COMMAND);
    }

    private void handleWorkerCommand(Tuple input) throws PipelineException {
        handleCommand(input, BfdWorker.FIELD_ID_PAYLOAD);
    }

    private void handleCommand(Tuple input, String fieldName) throws PipelineException {
        BfdHubCommand command = pullValue(input, fieldName, BfdHubCommand.class);
        command.apply(this);
    }

    private void handleSpeakerBcast(Tuple input) throws PipelineException {
        SpeakerBcast command = pullValue(input, SpeakerRouter.FIELD_ID_COMMAND, SpeakerBcast.class);
        command.apply(this);
    }

    // -- carrier IBfdPortLcmCarrier implementation --

    @Override
    public String createLogicalPort(Endpoint logical, int physicalPortNumber) {
        BfdWorkerPortCreateCommand command = new BfdWorkerPortCreateCommand(
                requestIdFactory.next(), logical, physicalPortNumber);
        emit(STREAM_WORKER_ID, getCurrentTuple(), makeWorkerTuple(command));
        return command.getRequestId();
    }

    @Override
    public String deleteLogicalPort(Endpoint logical) {
        BfdWorkerPortDeleteCommand command = new BfdWorkerPortDeleteCommand(requestIdFactory.next(), logical);
        emit(STREAM_WORKER_ID, getCurrentTuple(), makeWorkerTuple(command));
        return command.getRequestId();
    }

    @Override
    public void enableUpdateSession(Endpoint logical, int physicalPortNumber, BfdSessionData sessionData) {
        sessionService.enableUpdate(logical, physicalPortNumber, sessionData);
    }

    @Override
    public void disableSession(Endpoint logical) {
        sessionService.disable(logical);
    }

    @Override
    public void logicalPortControllerAddNotification(Endpoint physical) {
        globalToggleService.create(physical);
    }

    @Override
    public void logicalPortControllerDelNotification(Endpoint physical) {
        globalToggleService.delete(physical);
    }

    // -- carrier implementation --

    @Override
    public String sendWorkerBfdSessionCreateRequest(NoviBfdSession bfdSession) {
        String requestId = requestIdFactory.next();
        emit(STREAM_WORKER_ID, getCurrentTuple(),
                makeWorkerTuple(new BfdWorkerSessionCreateCommand(requestId, bfdSession)));
        return requestId;
    }

    @Override
    public String sendWorkerBfdSessionDeleteRequest(NoviBfdSession bfdSession) {
        String requestId = requestIdFactory.next();
        emit(STREAM_WORKER_ID, getCurrentTuple(),
                makeWorkerTuple(new BfdWorkerSessionRemoveCommand(requestId, bfdSession)));
        return requestId;
    }

    public void sessionRotateRequest(Endpoint logical, boolean error) {
        sessionService.rotate(logical, error);
    }

    public void sessionCompleteNotification(Endpoint physical) {
        sessionService.sessionCompleteNotification(physical);
        logicalPortService.sessionCompleteNotification(physical);
    }

    @Override
    public void bfdUpNotification(Endpoint physicalEndpoint) {
        globalToggleService.bfdStateChange(physicalEndpoint, LinkStatus.UP);
    }

    @Override
    public void bfdDownNotification(Endpoint physicalEndpoint) {
        globalToggleService.bfdStateChange(physicalEndpoint, LinkStatus.DOWN);
    }

    @Override
    public void bfdKillNotification(Endpoint physicalEndpoint) {
        globalToggleService.bfdKillNotification(physicalEndpoint);
    }

    @Override
    public void bfdFailNotification(Endpoint physicalEndpoint) {
        globalToggleService.bfdFailNotification(physicalEndpoint);
    }

    @Override
    public void filteredBfdUpNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.UP);
        // prevent potential tuples loop by emitting not anchored tuple
        emit(STREAM_UNIISL_ID, makeUniIslTuple(command));
    }

    @Override
    public void filteredBfdDownNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.DOWN);
        // prevent potential tuples loop by emitting not anchored tuple
        emit(STREAM_UNIISL_ID, makeUniIslTuple(command));
    }

    @Override
    public void filteredBfdKillNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.KILL);
        // prevent potential tuples loop by emitting not anchored tuple
        emit(STREAM_UNIISL_ID, makeUniIslTuple(command));
    }

    @Override
    public void filteredBfdFailNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.FAIL);
        // prevent potential tuples loop by emitting not anchored tuple
        emit(STREAM_UNIISL_ID, makeUniIslTuple(command));
    }

    // -- commands processing --

    public void processPortAdd(Endpoint logical, int physicalPortNumber) {
        logicalPortService.portAdd(logical, physicalPortNumber);
    }

    public void processPortDelete(Endpoint logical) {
        logicalPortService.portDel(logical);
    }

    public void processEnableUpdate(Endpoint endpoint, IslReference reference, BfdProperties properties) {
        logicalPortService.apply(endpoint, reference, properties);
    }

    public void processDisable(Endpoint endpoint) {
        logicalPortService.disable(endpoint);
    }

    public void processIslRemoveNotification(Endpoint physical, IslReference reference) {
        // reference argument will be used for "delete protection" to filter out stale delete requests
        logicalPortService.disableIfExists(physical);
    }

    public void processLinkStatusUpdate(Endpoint logical, LinkStatus status) {
        endpointStatusMonitor.update(logical, status);
    }

    public void processOnlineStatusUpdate(SwitchId switchId, boolean isOnline) {
        switchOnlineStatusMonitor.update(switchId, isOnline);
    }

    public void processLogicalPortCreateResponse(
            String requestId, Endpoint logical, CreateLogicalPortResponse response) {
        logicalPortService.workerSuccess(requestId, logical, response);
    }

    public void processLogicalPortDeleteResponse(
            String requestId, Endpoint logical, DeleteLogicalPortResponse response) {
        logicalPortService.workerSuccess(requestId, logical, response);
    }

    public void processLogicalPortErrorResponse(String requestId, Endpoint logical, ErrorData response) {
        logicalPortService.workerError(requestId, logical, response);
    }

    public void processSessionResponse(String key, Endpoint endpoint, BfdSessionResponse response) {
        sessionService.speakerResponse(endpoint, key, response);
    }

    public void processSessionRequestTimeout(String key, Endpoint endpoint) {
        sessionService.speakerTimeout(endpoint, key);
    }

    public void processSwitchRemovedNotification(SwitchId switchId) {
        switchOnlineStatusMonitor.cleanup(switchId);
        endpointStatusMonitor.cleanup(switchId);
    }

    @Override
    public void processFeatureTogglesUpdate(FeatureToggles toggles) {
        globalToggleService.updateToggle(toggles);
    }

    @Override
    public void activationStatusUpdate(boolean isActive) {
        if (isActive) {
            globalToggleService.synchronizeToggle();
        } else {
            globalToggleService.updateToggle(false);
        }
    }

    // -- setup --

    @Override
    protected void init() {
        switchOnlineStatusMonitor = new SwitchOnlineStatusMonitor();
        endpointStatusMonitor = new EndpointStatusMonitor();

        logicalPortService = new NetworkBfdLogicalPortService(
                this, switchOnlineStatusMonitor, options.getBfdLogicalPortOffset());
        sessionService = new NetworkBfdSessionService(
                persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, this);
        globalToggleService = new NetworkBfdGlobalToggleService(this, persistenceManager);
        requestIdFactory = new TaskIdBasedKeyFactory(getTaskId());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_WORKER_ID, STREAM_WORKER_FIELDS);
        streamManager.declareStream(STREAM_UNIISL_ID, STREAM_UNIISL_FIELDS);
    }

    // -- private methods --

    private Values makeWorkerTuple(BfdWorkerCommand command) {
        return new Values(command.getRequestId(), command, getCommandContext());
    }

    private Values makeUniIslTuple(UniIslCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }
}
