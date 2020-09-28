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

package org.openkilda.wfm.topology.network.storm.bolt.speaker;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.info.discovery.InstallIslDefaultRulesResult;
import org.openkilda.messaging.info.discovery.RemoveIslDefaultRulesResult;
import org.openkilda.messaging.payload.switches.InstallIslDefaultRulesCommand;
import org.openkilda.messaging.payload.switches.RemoveIslDefaultRulesCommand;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.storm.bolt.SpeakerRulesEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.BfdHub;
import org.openkilda.wfm.topology.network.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslDefaultRuleCreatedCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslDefaultRuleRemovedCommand;
import org.openkilda.wfm.topology.network.storm.bolt.isl.command.IslDefaultRuleTimeoutCommand;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.command.SpeakerRulesWorkerCommand;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

@Slf4j
public class SpeakerRulesWorker extends WorkerBolt {
    public static final String BOLT_ID = WorkerBolt.ID + ".speaker.rules";

    public static final String FIELD_ID_PAYLOAD = SpeakerRulesEncoder.FIELD_ID_PAYLOAD;
    public static final String FIELD_ID_KEY = SpeakerRulesEncoder.FIELD_ID_KEY;

    public static final String STREAM_HUB_ID = "hub";

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public SpeakerRulesWorker(Config config) {
        super(config);
    }

    @Override
    protected void onHubRequest(Tuple input) throws PipelineException {
        // At this moment only one bolt(BfdPortHandler) can write into this worker so can rely on routing performed
        // in our superclass. Once this situation changed we will need to make our own request routing or extend
        // routing in superclass.
        handleCommand(input, IslHandler.FIELD_ID_COMMAND);
    }

    @Override
    protected void onAsyncResponse(Tuple request, Tuple response) throws Exception {
        handleCommand(response, SpeakerRouter.FIELD_ID_INPUT);

    }

    @Override
    public void onRequestTimeout(Tuple request) {
        try {
            handleTimeout(request, BfdHub.FIELD_ID_COMMAND);
        } catch (PipelineException e) {
            log.error("Unable to unpack original tuple in timeout processing - {}", e.getMessage());
        }
    }

    // -- commands processing --

    /**
     * Process request to speaker.
     *
     * @param key key
     * @param source endpoint
     * @param destination endpoint
     */
    public void processSetupIslDefaultRulesRequest(String key, Endpoint source, Endpoint destination) {
        emitSpeakerRequest(key, InstallIslDefaultRulesCommand.builder().srcSwitch(source.getDatapath())
                .srcPort(source.getPortNumber()).dstSwitch(destination.getDatapath())
                .dstPort(destination.getPortNumber()).build());
    }

    /**
     * Process request to speaker.
     *
     * @param key key
     * @param source endpoint
     * @param destination endpoint
     */
    public void processRemoveIslDefaultRulesRequest(String key, Endpoint source, Endpoint destination) {
        emitSpeakerRequest(key, RemoveIslDefaultRulesCommand.builder().srcSwitch(source.getDatapath())
                .srcPort(source.getPortNumber()).dstSwitch(destination.getDatapath())
                .dstPort(destination.getPortNumber()).build());
    }

    public void processSetupIslDefaultRulesResponse(String key, InstallIslDefaultRulesResult response) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(
                key, new IslDefaultRuleCreatedCommand(response)));
    }

    public void processRemoveIslDefaultRulesResponse(String key, RemoveIslDefaultRulesResult response) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(
                key, new IslDefaultRuleRemovedCommand(response)));
    }

    public void timeoutIslRuleRequest(String key, Endpoint source, Endpoint destination) {
        emitResponseToHub(getCurrentTuple(), makeHubTuple(key, new IslDefaultRuleTimeoutCommand(source, destination)));
    }

    // -- setup --

    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        super.declareOutputFields(streamManager);  // it will define HUB stream
        streamManager.declare(STREAM_FIELDS);
    }

    // -- private/service methods --

    private void handleCommand(Tuple input, String field) throws PipelineException {
        SpeakerRulesWorkerCommand command = pullValue(input, field, SpeakerRulesWorkerCommand.class);
        command.apply(this);
    }

    private void handleTimeout(Tuple input, String field) throws PipelineException {
        SpeakerRulesWorkerCommand command = pullValue(input, field, SpeakerRulesWorkerCommand.class);
        command.timeout(this);
    }

    public void emitSpeakerRequest(String key, CommandData payload) {
        emit(getCurrentTuple(), makeSpeakerTuple(key, payload));
    }

    private Values makeSpeakerTuple(String key, CommandData payload) {
        return new Values(key, payload, getCommandContext());
    }

    private Values makeHubTuple(String key, IslCommand command) {
        return new Values(key, command, getCommandContext());
    }
}
