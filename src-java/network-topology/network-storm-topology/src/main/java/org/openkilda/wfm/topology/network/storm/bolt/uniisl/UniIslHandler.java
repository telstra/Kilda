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

package org.openkilda.wfm.topology.network.storm.bolt.uniisl;

import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;
import org.openkilda.wfm.topology.network.service.IUniIslCarrier;
import org.openkilda.wfm.topology.network.service.NetworkUniIslService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.BfdHub;
import org.openkilda.wfm.topology.network.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslBfdStatusUpdateCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslDownCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslMoveCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslRoundTripStatusCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslSetupFromHistoryCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslUpCommand;
import org.openkilda.wfm.topology.network.storm.bolt.port.PortHandler;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.command.UniIslCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListCommand;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.command.WatchListExhaustedPollModeUpdateCommand;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class UniIslHandler extends AbstractBolt implements IUniIslCarrier {
    public static final String BOLT_ID = ComponentId.UNI_ISL_HANDLER.toString();

    public static final String FIELD_ID_ISL_SOURCE = SpeakerRouter.FIELD_ID_ISL_SOURCE;
    public static final String FIELD_ID_ISL_DEST = SpeakerRouter.FIELD_ID_ISL_DEST;
    public static final String FIELD_ID_COMMAND = SpeakerRouter.FIELD_ID_COMMAND;
    public static final String FIELD_ID_DATAPATH = PortHandler.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = PortHandler.FIELD_ID_PORT_NUMBER;

    public static final Fields STREAM_FIELDS = SpeakerRouter.STREAM_ISL_FIELDS;

    public static final String STREAM_POLL_ID = "poll";
    public static final Fields STREAM_POLL_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
            FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    private transient NetworkUniIslService service;

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (PortHandler.BOLT_ID.equals(source)) {
            handlePortCommand(input);
        } else if (BfdHub.BOLT_ID.equals(source)) {
            handleBfdPortCommand(input);
        } else if (IslHandler.BOLT_ID.equals(source)) {
            handleIslHandlerCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handlePortCommand(Tuple input) throws PipelineException {
        handleCommand(input, PortHandler.FIELD_ID_COMMAND);
    }

    private void handleBfdPortCommand(Tuple input) throws PipelineException {
        handleCommand(input, BfdHub.FIELD_ID_COMMAND);
    }

    private void handleIslHandlerCommand(Tuple input) throws PipelineException {
        handleCommand(input, IslHandler.FIELD_ID_COMMAND);
    }

    private void handleCommand(Tuple input, String field) throws PipelineException {
        UniIslCommand command = pullValue(input, field, UniIslCommand.class);
        command.apply(this);
    }

    @Override
    protected void init() {
        service = new NetworkUniIslService(this);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        streamManager.declareStream(STREAM_POLL_ID, STREAM_POLL_FIELDS);
    }

    @Override
    public void setupIslFromHistory(Endpoint endpoint, IslReference islReference, Isl history) {
        emit(getCurrentTuple(), makeDefaultTuple(new IslSetupFromHistoryCommand(endpoint, islReference, history)));
    }

    @Override
    public void notifyIslUp(Endpoint endpoint, IslReference reference, IslDataHolder islData) {
        emit(getCurrentTuple(), makeDefaultTuple(new IslUpCommand(endpoint, reference, islData)));
    }

    @Override
    public void notifyIslDown(Endpoint endpoint, IslReference reference, IslDownReason reason) {
        emit(getCurrentTuple(), makeDefaultTuple(new IslDownCommand(endpoint, reference, reason)));
    }

    @Override
    public void notifyIslMove(Endpoint endpoint, IslReference reference) {
        emit(getCurrentTuple(), makeDefaultTuple(new IslMoveCommand(endpoint, reference)));
    }

    @Override
    public void notifyIslRoundTripStatus(IslReference reference, RoundTripStatus status) {
        emit(getCurrentTuple(), makeDefaultTuple(new IslRoundTripStatusCommand(reference, status)));
    }

    @Override
    public void notifyBfdStatus(Endpoint endpoint, IslReference reference, BfdStatusUpdate status) {
        emit(getCurrentTuple(), makeDefaultTuple(new IslBfdStatusUpdateCommand(endpoint, reference, status)));
    }

    @Override
    public void exhaustedPollModeUpdateRequest(Endpoint endpoint, boolean enableExhaustedPollMode) {
        WatchListCommand command = new WatchListExhaustedPollModeUpdateCommand(endpoint, enableExhaustedPollMode);
        // We emit without the anchor tuple because here we are generating a new event to change the mode.
        // Also, if a cycle appears in the future by the anchor tuple, it will be quite difficult to find it,
        // and we remove the possibility of this cycle appearing initially.
        emit(STREAM_POLL_ID, makePollTuple(command));
    }

    private Values makeDefaultTuple(IslCommand command) {
        IslReference reference = command.getReference();
        return new Values(reference.getSource(), reference.getDest(), command, getCommandContext());
    }

    private Values makePollTuple(WatchListCommand command) {
        Endpoint endpoint = command.getEndpoint();
        return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getCommandContext());
    }

    // UniIslCommand

    public void processBfdStatusUpdate(Endpoint endpoint, BfdStatusUpdate status) {
        service.uniIslBfdStatusUpdate(endpoint, status);
    }

    public void processUniIslRemove(Endpoint endpoint) {
        service.uniIslRemove(endpoint);
    }

    public void processUniIslSetup(Endpoint endpoint, Isl history) {
        service.uniIslSetup(endpoint, history);
    }

    public void processUniIslPhysicalDown(Endpoint endpoint) {
        service.uniIslPhysicalDown(endpoint);
    }

    public void processUniIslFail(Endpoint endpoint) {
        service.uniIslFail(endpoint);
    }

    public void processUniIslDiscovery(Endpoint endpoint, IslInfoData speakerDiscoveryEvent) {
        service.uniIslDiscovery(endpoint, speakerDiscoveryEvent);
    }

    public void processRoundTripStatus(RoundTripStatus status) {
        service.roundTripStatusNotification(status);
    }

    public void processIslRemovedNotification(Endpoint endpoint, IslReference reference) {
        service.islRemovedNotification(endpoint, reference);
    }
}
