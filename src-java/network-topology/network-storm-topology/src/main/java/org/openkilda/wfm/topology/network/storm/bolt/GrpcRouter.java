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

package org.openkilda.wfm.topology.network.storm.bolt;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.grpc.CreateLogicalPortResponse;
import org.openkilda.messaging.info.grpc.DeleteLogicalPortResponse;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.response.BfdWorkerAsyncResponse;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.response.BfdWorkerGrpcErrorResponse;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.response.BfdWorkerLogicalPortCreateResponse;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.response.BfdWorkerLogicalPortDeleteResponse;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class GrpcRouter extends AbstractBolt {
    public static final String BOLT_ID = ComponentId.GRPC_ROUTER.toString();

    public static final String FIELD_ID_KEY = MessageKafkaTranslator.FIELD_ID_KEY;
    public static final String FIELD_ID_PAYLOAD = MessageKafkaTranslator.FIELD_ID_PAYLOAD;

    public static final String STREAM_BFD_WORKER_ID = "bfd.worker";
    public static final Fields STREAM_BFD_WORKER_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (ComponentId.INPUT_GRPC.toString().equals(source)) {
            route(input, pullValue(input, FIELD_ID_PAYLOAD, Message.class));
        } else {
            unhandledInput(input);
        }
    }

    private void route(Tuple input, Message message) throws PipelineException {
        if (message instanceof InfoMessage) {
            route(input, (InfoMessage) message);
        } else if (message instanceof ErrorMessage) {
            route(input, (ErrorMessage) message);
        } else {
            unhandledInput(input);
        }
    }

    private void route(Tuple input, InfoMessage message) throws PipelineException {
        InfoData payload = message.getData();
        String key = pullKey(input);
        if (payload instanceof CreateLogicalPortResponse) {
            emit(STREAM_BFD_WORKER_ID, input, makeBfdWorkerTuple(
                    key, new BfdWorkerLogicalPortCreateResponse((CreateLogicalPortResponse) payload)));
        } else if (payload instanceof DeleteLogicalPortResponse) {
            emit(STREAM_BFD_WORKER_ID, input, makeBfdWorkerTuple(
                    key, new BfdWorkerLogicalPortDeleteResponse((DeleteLogicalPortResponse) payload)));
        } else {
            log.warn("Ignore GRPC message {}: {}", payload.getClass().getName(), payload);
        }
    }

    private void route(Tuple input, ErrorMessage message) throws PipelineException {
        ErrorData payload = message.getData();
        emit(STREAM_BFD_WORKER_ID, input, makeBfdWorkerTuple(pullKey(input), new BfdWorkerGrpcErrorResponse(payload)));
    }

    private String pullKey(Tuple tuple) throws PipelineException {
        return pullValue(tuple, FIELD_ID_KEY, String.class);
    }

    // -- output tuple format --

    private Values makeBfdWorkerTuple(String key, BfdWorkerAsyncResponse envelope) {
        return new Values(key, envelope, getCommandContext());
    }

    // -- setup --

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_BFD_WORKER_ID, STREAM_BFD_WORKER_FIELDS);
    }
}
