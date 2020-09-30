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

package org.openkilda.wfm.topology.network.storm.bolt.isl;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.reroute.RerouteFlows;
import org.openkilda.messaging.info.discovery.InstallIslDefaultRulesResult;
import org.openkilda.messaging.info.discovery.RemoveIslDefaultRulesResult;
import org.openkilda.messaging.info.event.IslStatusUpdateNotification;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.bolt.KafkaEncoder;
import org.openkilda.wfm.share.hubandspoke.TaskIdBasedKeyFactory;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.error.ControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;
import org.openkilda.wfm.topology.network.service.IIslCarrier;
import org.openkilda.wfm.topology.network.service.NetworkIslService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.bfdport.command.BfdPortCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfdport.command.BfdPortDisableCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfdport.command.BfdPortEnableCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRulesWorker;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.command.SpeakerRulesIslInstallCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.command.SpeakerRulesIslRemoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.command.SpeakerRulesWorkerCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.UniIslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslCommand;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslNotifyIslRemovedCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListAuxiliaryPollModeUpdateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListCommand;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class IslHandler extends AbstractBolt implements IIslCarrier {
    public static final String BOLT_ID = ComponentId.ISL_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = UniIslHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = UniIslHandler.FIELD_ID_PORT_NUMBER;
    public static final String FIELD_ID_COMMAND = UniIslHandler.FIELD_ID_COMMAND;

    public static final String STREAM_BFD_PORT_ID = "bfd-port";
    public static final Fields STREAM_BFD_PORT_FIELDS = new Fields(FIELD_ID_DATAPATH,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_REROUTE_ID = "reroute";
    public static final Fields STREAM_REROUTE_FIELDS = new Fields(
            KafkaEncoder.FIELD_ID_KEY, KafkaEncoder.FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public static final String STREAM_STATUS_ID = "status";
    public static final Fields STREAM_STATUS_FIELDS = new Fields(
            KafkaEncoder.FIELD_ID_KEY, KafkaEncoder.FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public static final String STREAM_SPEAKER_RULES_ID = "speaker.rules";
    public static final Fields STREAM_SPEAKER_RULES_FIELDS = new Fields(
            KafkaEncoder.FIELD_ID_KEY, FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_POLL_ID = "poll";
    public static final Fields STREAM_POLL_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_UNIISL_ID = "uni-isl";
    public static final Fields STREAM_UNIISL_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;
    private final NetworkOptions options;

    private transient NetworkIslService service;
    // FIXME(surabujin) the keys produced by this factory are not used
    private transient TaskIdBasedKeyFactory keyFactory;

    public IslHandler(PersistenceManager persistenceManager, NetworkOptions options) {
        this.persistenceManager = persistenceManager;
        this.options = options;
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (UniIslHandler.BOLT_ID.equals(source)) {
            handleUniIslCommand(input);
        } else if (SpeakerRouter.BOLT_ID.equals(source)) {
            handleSpeakerInput(input);
        } else if (SpeakerRulesWorker.BOLT_ID.equals(source)) {
            handleSpeakerRulesWorkerInput(input);
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

    private void handleUniIslCommand(Tuple input) throws PipelineException {
        IslCommand command = pullValue(input, UniIslHandler.FIELD_ID_COMMAND, IslCommand.class);
        command.apply(this);
    }

    private void handleSpeakerInput(Tuple input) throws PipelineException {
        IslCommand command = pullValue(input, SpeakerRouter.FIELD_ID_COMMAND, IslCommand.class);
        command.apply(this);
    }

    private void handleSpeakerRulesWorkerInput(Tuple input) throws PipelineException {
        IslCommand command = pullValue(input, SpeakerRulesWorker.FIELD_ID_PAYLOAD, IslCommand.class);
        command.apply(this);
    }

    @Override
    protected void init() {
        service = new NetworkIslService(this, persistenceManager, options);
        keyFactory = new TaskIdBasedKeyFactory(getTaskId());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_BFD_PORT_ID, STREAM_BFD_PORT_FIELDS);
        streamManager.declareStream(STREAM_REROUTE_ID, STREAM_REROUTE_FIELDS);
        streamManager.declareStream(STREAM_STATUS_ID, STREAM_STATUS_FIELDS);
        streamManager.declareStream(STREAM_SPEAKER_RULES_ID, STREAM_SPEAKER_RULES_FIELDS);
        streamManager.declareStream(STREAM_POLL_ID, STREAM_POLL_FIELDS);
        streamManager.declareStream(STREAM_UNIISL_ID, STREAM_UNIISL_FIELDS);
    }

    @Override
    public void bfdPropertiesApplyRequest(Endpoint physicalEndpoint, IslReference reference, BfdProperties properties) {
        emit(STREAM_BFD_PORT_ID, getCurrentTuple(),
                makeBfdPortTuple(new BfdPortEnableCommand(physicalEndpoint, reference, properties)));
    }

    @Override
    public void bfdDisableRequest(Endpoint physicalEndpoint) {
        emit(STREAM_BFD_PORT_ID, getCurrentTuple(),
                makeBfdPortTuple(new BfdPortDisableCommand(physicalEndpoint)));
    }

    @Override
    public void triggerReroute(RerouteFlows trigger) {
        emit(STREAM_REROUTE_ID, getCurrentTuple(), makeRerouteTuple(trigger));
    }

    @Override
    public void islStatusUpdateNotification(IslStatusUpdateNotification trigger) {
        emit(STREAM_STATUS_ID, getCurrentTuple(), makeStatusUpdateTuple(trigger));
    }

    @Override
    public void islDefaultRulesInstall(Endpoint source, Endpoint destination) {
        emit(STREAM_SPEAKER_RULES_ID, getCurrentTuple(), makeSpeakerRulesTuple(
                new SpeakerRulesIslInstallCommand(keyFactory.next(), source, destination)));
    }

    @Override
    public void islDefaultRulesDelete(Endpoint source, Endpoint destination) {
        emit(STREAM_SPEAKER_RULES_ID, getCurrentTuple(), makeSpeakerRulesTuple(
                new SpeakerRulesIslRemoveCommand(keyFactory.next(), source, destination)));
    }

    @Override
    public void auxiliaryPollModeUpdateRequest(Endpoint endpoint, boolean enableAuxiliaryPollMode) {
        WatchListCommand command = new WatchListAuxiliaryPollModeUpdateCommand(endpoint, enableAuxiliaryPollMode);
        // We emit without the anchor tuple because here we are generating a new event to change the mode.
        // Also, if a cycle appears in the future by the anchor tuple, it will be quite difficult to find it,
        // and we remove the possibility of this cycle appearing initially.
        emit(STREAM_POLL_ID, makePollTuple(command));
    }

    @Override
    public void islRemovedNotification(Endpoint srcEndpoint, IslReference reference) {
        // We emit without the anchor tuple to break the loop.
        emit(STREAM_UNIISL_ID, makeUniIslTuple(new UniIslNotifyIslRemovedCommand(srcEndpoint, reference)));
    }

    private Values makeSpeakerRulesTuple(SpeakerRulesWorkerCommand command) {
        return new Values(command.getKey(), command, getCommandContext());
    }

    private Values makeBfdPortTuple(BfdPortCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), command,
                getCommandContext().fork(String.format("ISL-2-BFD %s", endpoint)));
    }

    private Values makeRerouteTuple(CommandData payload) {
        return new Values(null, payload, getCommandContext());
    }

    private Values makeStatusUpdateTuple(IslStatusUpdateNotification payload) {
        return new Values(null, payload, getCommandContext());
    }

    private Values makePollTuple(WatchListCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }

    private Values makeUniIslTuple(UniIslCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }

    public void processIslSetupFromHistory(Endpoint endpoint, IslReference reference, Isl history) {
        service.islSetupFromHistory(endpoint, reference, history);
    }

    public void processIslUp(Endpoint endpoint, IslReference reference, IslDataHolder islData) {
        service.islUp(endpoint, reference, islData);
    }

    public void processIslMove(Endpoint endpoint, IslReference reference) {
        service.islMove(endpoint, reference);
    }

    public void processIslDown(Endpoint endpoint, IslReference reference, IslDownReason reason) {
        service.islDown(endpoint, reference, reason);
    }

    public void processRoundTripStatus(IslReference reference, RoundTripStatus status) {
        service.roundTripStatusNotification(reference, status);
    }

    public void processBfdStatusUpdate(Endpoint endpoint, IslReference reference, BfdStatusUpdate status) {
        service.bfdStatusUpdate(endpoint, reference, status);
    }

    public void processBfdPropertiesUpdate(IslReference reference) {
        service.bfdPropertiesUpdate(reference);
    }

    public void processIslRuleInstalled(IslReference reference, InstallIslDefaultRulesResult payload) {
        service.islDefaultRuleInstalled(reference, payload);
    }

    public void processIslRuleDeleted(IslReference reference, RemoveIslDefaultRulesResult payload) {
        service.islDefaultRuleDeleted(reference, payload);
    }

    public void processIslRuleTimeout(IslReference reference, Endpoint endpoint) {
        service.islDefaultTimeout(reference, endpoint);
    }

    public void processIslRemove(IslReference reference) {
        service.remove(reference);
    }
}
