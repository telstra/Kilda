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
import org.openkilda.messaging.AbstractMessage;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt;
import org.openkilda.wfm.topology.flowhs.FlowHsTopology.ComponentId;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.DbResponse;
import org.openkilda.wfm.topology.flowhs.service.SpeakerCommandCarrier;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class SpeakerWorkerBolt extends WorkerBolt implements SpeakerCommandCarrier {

    public static final String ID = "speaker.worker.bolt";
    private transient SpeakerWorkerService service;
    private final PersistenceManager persistenceManager;
    private final PathComputerConfig pathComputerConfig;
    private final FlowResourcesConfig flowResourcesConfig;
    private final int pathAllocationRetriesLimit;
    private final int pathAllocationRetryDelay;


    public SpeakerWorkerBolt(Config config, PersistenceManager persistenceManager,
                             PathComputerConfig pathComputerConfig, FlowResourcesConfig flowResourcesConfig,
                             int pathAllocationRetriesLimit, int pathAllocationRetryDelay) {
        super(config);
        this.pathComputerConfig = pathComputerConfig;
        this.flowResourcesConfig = flowResourcesConfig;
        this.persistenceManager = persistenceManager;
        this.pathAllocationRetriesLimit = pathAllocationRetriesLimit;
        this.pathAllocationRetryDelay = pathAllocationRetryDelay;
        this.hsBoltName = "speaker_worker";
    }

    @Override
    protected void init() {
        super.init();
        FlowResourcesManager resourcesManager = new FlowResourcesManager(persistenceManager, flowResourcesConfig);
        AvailableNetworkFactory availableNetworkFactory =
                new AvailableNetworkFactory(pathComputerConfig, persistenceManager.getRepositoryFactory());
        PathComputer pathComputer =
                new PathComputerFactory(pathComputerConfig, availableNetworkFactory).getPathComputer();
        service = new SpeakerWorkerService(this, pathComputer, resourcesManager, persistenceManager,
                pathAllocationRetriesLimit, pathAllocationRetryDelay);
    }

    @Override
    protected void onHubRequest(Tuple input) throws PipelineException {


        AbstractMessage command = pullValue(input, FIELD_ID_PAYLOAD, AbstractMessage.class);
        long sendTime = -1;
        if (command instanceof FlowSegmentRequest) {
            FlowSegmentRequest segmentRequest = (FlowSegmentRequest) command;
            sendTime = segmentRequest.sendTime;
            if (workerConfig.getHubComponent().equals(ComponentId.FLOW_CREATE_HUB.name())) {
                log.warn("HSTIME spend in queue: (segment request) Hub -> Worker "
                        + (System.currentTimeMillis() - segmentRequest.sendTime));
            }
            service.sendCommand(pullKey(), segmentRequest);
        } else if (command instanceof DbCommand) {
            DbCommand dbCommand = (DbCommand) command;
            sendTime = dbCommand.sendTime;
            if (workerConfig.getHubComponent().equals(ComponentId.FLOW_CREATE_HUB.name())) {
                log.warn("HSTIME spend in queue: (db command) Hub -> Worker "
                        + (System.currentTimeMillis() - dbCommand.sendTime));
            }
            long time = System.currentTimeMillis();
            service.applyCommand(pullKey(), dbCommand);
            log.warn("HSTIME apply DB command " + (System.currentTimeMillis() - time));

        }
        log.warn("HSTIME spend in queue: Hub -> Worker "
                + (System.currentTimeMillis() - sendTime));
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

    @Override
    public void sendResponse(DbResponse response) {
        String key;
        try {
            key = pullKey();
        } catch (PipelineException e) {
            log.error(String.format("Unable to get key from current tuple '%s' during sending worker response '%s': %s",
                    getCurrentTuple(), response, e.getMessage()), e);
            return;
        }

        Values values = new Values(key, response, getCommandContext());
        response.sendTime = System.currentTimeMillis();
        emitResponseToHub(getCurrentTuple(), values);
    }
}
