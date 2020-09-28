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

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.FeatureToggles;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.TaskIdBasedKeyFactory;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.error.ControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.service.IBfdGlobalToggleCarrier;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;
import org.openkilda.wfm.topology.network.service.NetworkBfdGlobalToggleService;
import org.openkilda.wfm.topology.network.service.NetworkBfdSessionService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.BfdWorker;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerSessionRemoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerSessionSetupCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.bcast.ISpeakerBcastConsumer;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.bcast.SpeakerBcast;
import org.openkilda.wfm.topology.network.storm.bolt.sw.SwitchHandler;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslBfdStatusUpdateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslCommand;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class BfdHub extends AbstractBolt
        implements IBfdSessionCarrier, IBfdGlobalToggleCarrier, ISpeakerBcastConsumer {
    public static final String BOLT_ID = ComponentId.BFD_PORT_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = SwitchHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = SwitchHandler.FIELD_ID_PORT_NUMBER;
    public static final String FIELD_ID_COMMAND_KEY = MessageKafkaTranslator.KEY_FIELD;
    public static final String FIELD_ID_COMMAND = SwitchHandler.FIELD_ID_COMMAND;

    public static final String STREAM_SPEAKER_ID = "speaker";
    public static final Fields STREAM_SPEAKER_FIELDS = new Fields(FIELD_ID_COMMAND_KEY, FIELD_ID_COMMAND,
                                                                  FIELD_ID_CONTEXT);

    public static final String STREAM_UNIISL_ID = "uni-isl";
    public static final Fields STREAM_UNIISL_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
                                                                 FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;

    private transient NetworkBfdSessionService sessionService;
    private transient NetworkBfdGlobalToggleService globalToggleService;
    private transient TaskIdBasedKeyFactory keyFactory;

    public BfdHub(PersistenceManager persistenceManager) {
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

    // -- carrier implementation --

    @Override
    public String addBfdSession(NoviBfdSession bfdSession) {
        String key = keyFactory.next();
        emit(STREAM_SPEAKER_ID, getCurrentTuple(),
                makeSpeakerTuple(new BfdWorkerSessionSetupCommand(key, bfdSession)));
        return key;
    }

    @Override
    public String deleteBfdSession(NoviBfdSession bfdSession) {
        String key = keyFactory.next();
        emit(STREAM_SPEAKER_ID, getCurrentTuple(),
                makeSpeakerTuple(new BfdWorkerSessionRemoveCommand(key, bfdSession)));
        return key;
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
        emit(STREAM_UNIISL_ID, getCurrentTuple(), makeUniIslTuple(command));
    }

    @Override
    public void filteredBfdDownNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.DOWN);
        emit(STREAM_UNIISL_ID, getCurrentTuple(), makeUniIslTuple(command));
    }

    @Override
    public void filteredBfdKillNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.KILL);
        emit(STREAM_UNIISL_ID, getCurrentTuple(), makeUniIslTuple(command));
    }

    @Override
    public void filteredBfdFailNotification(Endpoint physicalEndpoint) {
        UniIslBfdStatusUpdateCommand command = new UniIslBfdStatusUpdateCommand(physicalEndpoint, BfdStatusUpdate.FAIL);
        emit(STREAM_UNIISL_ID, getCurrentTuple(), makeUniIslTuple(command));
    }

    // -- commands processing --

    public void processSetup(Endpoint endpoint, int physicalPortNumber) {
        sessionService.setup(endpoint, physicalPortNumber);
        globalToggleService.setup(Endpoint.of(endpoint.getDatapath(), physicalPortNumber));
    }

    public void processRemove(Endpoint endpoint) {
        Endpoint physicalEndpoint = sessionService.delete(endpoint);
        globalToggleService.remove(physicalEndpoint);
    }

    public void processEnableUpdate(Endpoint endpoint, IslReference reference, BfdProperties properties) {
        sessionService.enableUpdate(endpoint, reference, properties);
    }

    public void processDisable(Endpoint endpoint) {
        sessionService.disable(endpoint);
    }

    public void processLinkStatusUpdate(Endpoint endpoint, LinkStatus status) {
        sessionService.updateLinkStatus(endpoint, status);
    }

    public void processOnlineModeUpdate(Endpoint endpoint, boolean isOnline) {
        sessionService.updateOnlineStatus(endpoint, isOnline);
    }

    public void processSpeakerSetupResponse(String key, Endpoint endpoint, BfdSessionResponse response) {
        sessionService.speakerResponse(key, endpoint, response);
    }

    public void processSpeakerTimeout(String key, Endpoint endpoint) {
        sessionService.speakerTimeout(key, endpoint);
    }

    @Override
    public void processFeatureTogglesUpdate(FeatureToggles toggles) {
        globalToggleService.toggleUpdate(toggles);
    }

    // -- setup --

    @Override
    protected void init() {
        sessionService = new NetworkBfdSessionService(this, persistenceManager);
        globalToggleService = new NetworkBfdGlobalToggleService(this, persistenceManager);
        keyFactory = new TaskIdBasedKeyFactory(getTaskId());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_SPEAKER_ID, STREAM_SPEAKER_FIELDS);
        streamManager.declareStream(STREAM_UNIISL_ID, STREAM_UNIISL_FIELDS);
    }

    // -- private/bfdPortService methods --

    private Values makeSpeakerTuple(BfdWorkerCommand command) {
        return new Values(command.getKey(), command, getCommandContext());
    }

    private Values makeUniIslTuple(UniIslCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }
}
