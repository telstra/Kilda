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

package org.openkilda.wfm.share.hubandspoke;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import lombok.Builder;
import lombok.Getter;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * This class provides main methods for classes acting as a worker with asynchronous operations.
 * </p>
 * Note: Additional bolt, spout and streams are required for the topology to work this worker bolt properly:
 * {@link CoordinatorBolt} and {@link CoordinatorSpout} should be declared in a topology definition.
 * </p>
 * Following streams are mandatory:
 * {@code}WorkerBolt{@code} must have income stream with directGrouping from {@code}CoordinatorBolt.ID{@code}.
 * {@link CoordinatorBolt} must have following income streams:
 * <ul>
 *     <il>allGrouping stream from {@code}CoordinatorSpout.ID{@code}</il>
 *     <il>fieldsGrouping stream from hub with grouping by {@code}MessageTranslator.KEY_FIELD{@code}</il>
 * </ul>
 */
public abstract class WorkerBolt extends CoordinatedBolt {
    public static final String ID = "worker.bolt";

    private final Config workerConfig;

    private transient Map<String, Tuple> pendingTasks;

    public WorkerBolt(Config config) {
        super(config.isAutoAck(), config.getDefaultTimeout());

        requireNonNull(config.getStreamToHub(), "Stream to hub bolt cannot be null");
        requireNonNull(config.getHubComponent(), "Hub bolt id cannot be null");
        requireNonNull(config.getWorkerSpoutComponent(), "Worker's spout id cannot be null");
        this.workerConfig = config;
    }

    @Override
    protected void dispatch(Tuple input) throws Exception {
        String sourceComponent = input.getSourceComponent();
        if (workerConfig.getHubComponent().equals(sourceComponent)) {
            dispatchHub(input);
        } else if (workerConfig.getWorkerSpoutComponent().equals(sourceComponent)) {
            dispatchResponse(input);
        } else {
            super.dispatch(input);
        }
    }

    private void dispatchHub(Tuple input) throws Exception {
        String key = pullKey(input);
        pendingTasks.put(key, input);
        registerCallback(key);

        onHubRequest(input);
    }

    protected void dispatchResponse(Tuple input) throws Exception {
        String key = pullKey();
        Tuple request = pendingTasks.get(key);
        if (request != null) {
            onAsyncResponse(request, input);
        } else {
            unhandledInput(key, input);
        }
    }

    @Override
    protected final void handleInput(Tuple input) {
        // all possible branching was done into `dispatch` nothing should hit here
        unhandledInput(input);
    }

    @Override
    protected final void unhandledInput(Tuple input) {
        String key = null;
        try {
            key = pullKey(input);
        } catch (PipelineException ex) {
            log.error("Unable to fetch a key from the tuple", ex);
        }
        unhandledInput(key, input);
    }

    protected void unhandledInput(String key, Tuple input) {
        log.error("{} drop worker async response. because {} key is not listed in pending response list [{}]",
                getComponentId(), key, formatTuplePayload(input));
    }

    /**
     * Send response to the hub bolt. Note: the operation's key is required.
     * @param input request or response tuple (use key to lookup original request).
     * @param values response to be sent to the hub.
     */
    protected void emitResponseToHub(Tuple input, Values values) {
        String key;
        try {
            key = pullKey(input);
        } catch (PipelineException e) {
            throw new IllegalStateException(String.format("Can't extract request key from %s: %s", input, e), e);
        }
        cancelCallback(key);

        Tuple processingRequest = pendingTasks.remove(key);
        if (processingRequest == null) {
            throw new IllegalStateException(format("Attempt to send response for non pending task with id %s", key));
        }
        getOutput().emitDirect(processingRequest.getSourceTask(), workerConfig.getStreamToHub(), getCurrentTuple(),
                               values);
    }

    protected abstract void onHubRequest(Tuple input) throws Exception;

    protected abstract void onAsyncResponse(Tuple request, Tuple response) throws Exception;

    @Override
    protected final void onTimeout(String key, Tuple tuple) throws PipelineException {
        Tuple request = pendingTasks.get(key);
        if (request != null) {
            log.warn("Timeout occurred while waiting for a response for {}", key);
            onRequestTimeout(request);

            // Do not remove request from pendingTask until now, because emitResponseToHub(most likely called by
            // onRequestTimeout) can query pendingTasks
            pendingTasks.remove(key);
        } else {
            log.debug("Receive timeout notification for {}, but there is no pending request by this key", key);
        }
    }

    protected abstract void onRequestTimeout(Tuple request) throws PipelineException;

    protected String pullKey() throws PipelineException {
        return this.pullKey(getCurrentTuple());
    }

    @Override
    protected void init() {
        super.init();
        pendingTasks = new HashMap<>();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(workerConfig.getStreamToHub(), true, MessageKafkaTranslator.STREAM_FIELDS);
    }

    @Builder
    @Getter
    public static class Config implements Serializable {
        private String streamToHub;
        private String hubComponent;
        private String workerSpoutComponent;

        @Builder.Default
        private int defaultTimeout = 100;

        @Builder.Default
        private boolean autoAck = true;
    }
}
