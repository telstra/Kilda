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

package org.openkilda.wfm.topology.network.storm.bolt.bfd.worker;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.grpc.CreateLogicalPortRequest;
import org.openkilda.messaging.command.grpc.DeleteLogicalPortRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.floodlight.request.RemoveBfdSession;
import org.openkilda.messaging.floodlight.request.SetupBfdSession;
import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.info.grpc.CreateLogicalPortResponse;
import org.openkilda.messaging.info.grpc.DeleteLogicalPortResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.grpc.LogicalPortType;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.storm.bolt.GrpcRouter;
import org.openkilda.wfm.topology.network.storm.bolt.SpeakerEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.BfdHub;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubGrpcErrorResponseCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubPortCreateResponseCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubPortDeleteResponseCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubSessionResponseCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command.BfdHubSessionTimeoutCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command.BfdWorkerCommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.response.BfdWorkerAsyncResponse;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Optional;

@Slf4j
public class BfdWorker extends WorkerBolt {
    public static final String BOLT_ID = WorkerBolt.ID + ".bfd";

    public static final String FIELD_ID_PAYLOAD = SpeakerEncoder.FIELD_ID_PAYLOAD;
    public static final String FIELD_ID_KEY = SpeakerEncoder.FIELD_ID_KEY;

    public static final String STREAM_HUB_ID = "hub";

    private static final Fields STREAM_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public static final String STREAM_SPEAKER_ID = "speaker";
    public static final Fields STREAM_SPEAKER_FIELDS = STREAM_FIELDS;

    public static final String STREAM_GRPC_ID = "grpc";
    public static final Fields STREAM_GRPC_FIELDS = STREAM_FIELDS;

    private final PersistenceManager persistenceManager;

    private transient SwitchRepository switchRepository;

    public BfdWorker(Config config, PersistenceManager persistenceManager) {
        super(config);
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void dispatch(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (GrpcRouter.BOLT_ID.equals(source)) {
            dispatchResponse(input);
        } else {
            super.dispatch(input);
        }
    }

    @Override
    protected void onHubRequest(Tuple input) throws PipelineException {
        BfdWorkerCommand command = pullHubCommand(input);
        command.apply(this);
    }

    @Override
    protected void onAsyncResponse(Tuple requestTuple, Tuple responseTuple) throws PipelineException {
        String source = responseTuple.getSourceComponent();
        BfdWorkerAsyncResponse response;
        if (SpeakerRouter.BOLT_ID.equals(source)) {
            response = pullAsyncResponse(responseTuple, SpeakerRouter.FIELD_ID_INPUT);
        } else if (GrpcRouter.BOLT_ID.equals(source)) {
            response = pullAsyncResponse(responseTuple, GrpcRouter.FIELD_ID_PAYLOAD);
        } else {
            unhandledInput(responseTuple);
            return;
        }

        BfdWorkerCommand request = pullHubCommand(requestTuple);
        response.consume(this, request);
    }

    @Override
    protected void onRequestTimeout(Tuple request) {
        try {
            handleTimeout(request, BfdHub.FIELD_ID_COMMAND);
        } catch (PipelineException e) {
            log.error("Unable to unpack original tuple in timeout processing - {}", e.getMessage());
        }
    }

    // -- commands processing --

    public void processBfdSetupRequest(String key, NoviBfdSession bfdSession) {
        SetupBfdSession payload = new SetupBfdSession(bfdSession);
        emitSpeakerRequest(key, payload);
    }

    public void processBfdRemoveRequest(String key, NoviBfdSession bfdSession) {
        RemoveBfdSession payload = new RemoveBfdSession(bfdSession);
        emitSpeakerRequest(key, payload);
    }

    public void processBfdSessionResponse(String requestId, Endpoint logical, BfdSessionResponse response) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(
                requestId, new BfdHubSessionResponseCommand(requestId, logical, response)));
    }

    /**
     * Send logical port (BFD) create request.
     */
    public void processPortCreateRequest(String requestId, Endpoint logical, int physicalPortNumber) {
        Optional<String> address = lookupSwitchAddress(logical.getDatapath());
        if (! address.isPresent()) {
            processPortCrudErrorResponse(requestId, logical, makeSwitchAddressNotFoundError(logical.getDatapath()));
            return;
        }

        CreateLogicalPortRequest request = new CreateLogicalPortRequest(
                address.get(), Collections.singletonList(physicalPortNumber), logical.getPortNumber(),
                LogicalPortType.BFD);
        emit(STREAM_GRPC_ID, getCurrentTuple(), makeGrpcTuple(requestId, request));
    }

    /**
     * Send logical port (BFD) delete request.
     */
    public void processPortDeleteRequest(String requestId, Endpoint logical) {
        Optional<String> address = lookupSwitchAddress(logical.getDatapath());
        if (!address.isPresent()) {
            processPortCrudErrorResponse(requestId, logical, makeSwitchAddressNotFoundError(logical.getDatapath()));
            return;
        }

        DeleteLogicalPortRequest request = new DeleteLogicalPortRequest(address.get(), logical.getPortNumber());
        emit(STREAM_GRPC_ID, getCurrentTuple(), makeGrpcTuple(requestId, request));
    }

    public void processPortCreateResponse(String requestId, Endpoint logical, CreateLogicalPortResponse response) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(requestId, new BfdHubPortCreateResponseCommand(
                requestId, logical, response)));
    }

    public void processPortDeleteResponse(String requestId, Endpoint logical, DeleteLogicalPortResponse response) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(requestId, new BfdHubPortDeleteResponseCommand(
                requestId, logical, response)));
    }

    public void processPortCrudErrorResponse(String requestId, Endpoint logical, ErrorData response) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(requestId, new BfdHubGrpcErrorResponseCommand(
                requestId, logical, response)));
    }

    public void processSessionRequestTimeout(String requestId, Endpoint logical) {
        emitResponseToHub(
                getCurrentTuple(), makeHubTuple(requestId, new BfdHubSessionTimeoutCommand(requestId, logical)));
    }

    public void processPortRequestTimeout(String requestID, Endpoint logical) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(requestID, new BfdHubGrpcErrorResponseCommand(
                requestID, logical, null)));
    }

    // -- setup --

    @Override
    protected void init() {
        super.init();

        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        super.declareOutputFields(streamManager);  // it will define HUB stream

        streamManager.declareStream(STREAM_SPEAKER_ID, STREAM_SPEAKER_FIELDS);
        streamManager.declareStream(STREAM_GRPC_ID, STREAM_GRPC_FIELDS);
    }

    // -- private/service methods --

    private void handleTimeout(Tuple request, String field) throws PipelineException {
        BfdWorkerCommand command = pullValue(request, field, BfdWorkerCommand.class);
        command.timeout(this);
    }

    public void emitSpeakerRequest(String key, CommandData payload) {
        emit(STREAM_SPEAKER_ID, getCurrentTuple(), makeSpeakerTuple(key, payload));
    }

    private Optional<String> lookupSwitchAddress(SwitchId switchId) {
        Optional<Switch> sw = switchRepository.findById(switchId);
        if (! sw.isPresent()) {
            return Optional.empty();
        }

        InetSocketAddress address = sw.get().getSocketAddress();
        return Optional.of(address.getAddress().getHostAddress());
    }

    private ErrorData makeSwitchAddressNotFoundError(SwitchId switchId) {
        String message = String.format("Can't determine switch %s ip address", switchId);
        return new ErrorData(ErrorType.INTERNAL_ERROR, message, "");
    }

    private BfdWorkerCommand pullHubCommand(Tuple tuple) throws PipelineException {
        return pullValue(tuple, BfdHub.FIELD_ID_COMMAND, BfdWorkerCommand.class);
    }

    private BfdWorkerAsyncResponse pullAsyncResponse(Tuple tuple, String field) throws PipelineException {
        return pullValue(tuple, field, BfdWorkerAsyncResponse.class);
    }

    private Values makeHubTuple(String key, BfdHubCommand command) {
        return new Values(key, command, getCommandContext());
    }

    private Values makeSpeakerTuple(String key, CommandData payload) {
        return makeGenericKafkaProduceTuple(key, payload);
    }

    private Values makeGrpcTuple(String key, CommandData payload) {
        return makeGenericKafkaProduceTuple(key, payload);
    }

    private Values makeGenericKafkaProduceTuple(String key, CommandData payload) {
        return new Values(key, payload, getCommandContext());
    }
}
