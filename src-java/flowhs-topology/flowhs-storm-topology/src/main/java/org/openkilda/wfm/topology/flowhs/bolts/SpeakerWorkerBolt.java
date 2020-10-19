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

package org.openkilda.wfm.topology.flowhs.bolts;

import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_REQUEST_SENDER;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt;
import org.openkilda.wfm.topology.flowhs.FlowHsTopology.ComponentId;
import org.openkilda.wfm.topology.flowhs.service.SpeakerCommandCarrier;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class SpeakerWorkerBolt extends WorkerBolt implements SpeakerCommandCarrier {

    public static final String ID = "speaker.worker.bolt";
    private transient SpeakerWorkerService service;

    public SpeakerWorkerBolt(Config config) {
        super(config);
        this.hsBoltName = "speaker_worker";
    }

    @Override
    public Config getWorkerConfig() {
        return workerConfig;
    }

    @Override
    protected void init() {
        super.init();
        service = new SpeakerWorkerService(this);
    }

    @Override
    protected void onHubRequest(Tuple input) throws PipelineException {
        FlowSegmentRequest command = pullValue(input, FIELD_ID_PAYLOAD, FlowSegmentRequest.class);
        if (workerConfig.getHubComponent().equals(ComponentId.FLOW_CREATE_HUB.name())) {
            log.warn("HSTIME spend in queue: Hub -> Worker " + (System.currentTimeMillis() - command.sendTime));
        }
        if (workerConfig.getHubComponent().equals(ComponentId.FLOW_REROUTE_HUB.name())) {
            log.warn("HSTIME reroute spend in queue: Hub -> Worker " + (System.currentTimeMillis() - command.sendTime));
        }
        service.sendCommand(pullKey(), command);
    }

    @Override
    protected void onAsyncResponse(Tuple request, Tuple response) throws PipelineException {
        SpeakerFlowSegmentResponse message = pullValue(response, FIELD_ID_PAYLOAD, SpeakerFlowSegmentResponse.class);
        service.handleResponse(pullKey(response), message);
    }

    @Override
    protected void onRequestTimeout(Tuple tuple) throws PipelineException {
        service.handleTimeout(pullKey(tuple));
    }

    @Override
    protected void unhandledInput(String key, Tuple input) {
        log.info("{} drop worker async response. because {} key is not listed in pending response list [{}]",
                getComponentId(), key, formatTuplePayload(input));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);

        declarer.declareStream(SPEAKER_WORKER_REQUEST_SENDER.name(), MessageKafkaTranslator.STREAM_FIELDS);
    }

    @Override
    public void sendCommand(String key, FlowSegmentRequest command) {
        command.sendTime = System.currentTimeMillis();
        emitWithContext(SPEAKER_WORKER_REQUEST_SENDER.name(), getCurrentTuple(), new Values(key, command));
    }

    @Override
    public void sendResponse(String key, SpeakerFlowSegmentResponse response) {
        Values values = new Values(key, response, getCommandContext());
        response.setTime(System.currentTimeMillis());
        emitResponseToHub(getCurrentTuple(), values);
    }
}
