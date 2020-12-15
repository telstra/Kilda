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

package org.openkilda.wfm.topology.floodlightrouter.bolts;

import org.openkilda.messaging.AliveRequest;
import org.openkilda.messaging.AliveResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.NetworkCommandData;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.bolt.MonotonicClock;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.RegionAwareKafkaTopicSelector;
import org.openkilda.wfm.topology.floodlightrouter.Stream;
import org.openkilda.wfm.topology.floodlightrouter.TickId;
import org.openkilda.wfm.topology.floodlightrouter.service.FloodlightTracker;
import org.openkilda.wfm.topology.floodlightrouter.service.RegionMonitorCarrier;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.Set;

@Slf4j
public class RegionTrackerBolt extends AbstractBolt implements RegionMonitorCarrier {
    public static final String BOLT_ID = ComponentType.KILDA_TOPO_DISCO_BOLT;

    public static final String FIELD_ID_REGION = SpeakerToNetworkProxyBolt.FIELD_ID_REGION;

    public static final String STREAM_SPEAKER_ID = Stream.SPEAKER_DISCO;

    public static final String STREAM_REGION_NOTIFICATION_ID = "region";
    public static final Fields STREAM_REGION_NOTIFICATION_FIELDS = new Fields(
            FIELD_ID_REGION, AbstractBolt.FIELD_ID_CONTEXT);

    private final String kafkaSpeakerTopic;

    private final PersistenceManager persistenceManager;
    private final MonotonicClock.Match<TickId> monotonicTickMatch = new MonotonicClock.Match<>(
            MonotonicTick.BOLT_ID, null);
    private final MonotonicClock.Match<TickId> networkDumpTickMatch = new MonotonicClock.Match<>(
            MonotonicTick.BOLT_ID, TickId.NETWORK_DUMP);

    private final Set<String> floodlights;
    private final long floodlightAliveTimeout;
    private final long floodlightAliveInterval;

    private transient FeatureTogglesRepository featureTogglesRepository;
    private transient FloodlightTracker floodlightTracker;

    public RegionTrackerBolt(
            String kafkaSpeakerTopic, PersistenceManager persistenceManager, Set<String> floodlights,
            long floodlightAliveTimeout, long floodlightAliveInterval) {
        super();

        this.kafkaSpeakerTopic = kafkaSpeakerTopic;

        this.persistenceManager = persistenceManager;
        this.floodlights = floodlights;
        this.floodlightAliveTimeout = floodlightAliveTimeout;
        this.floodlightAliveInterval = floodlightAliveInterval;
    }

    @Override
    protected void dispatch(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (monotonicTickMatch.isTick(input)) {
            handleTick();
        } else if (networkDumpTickMatch.isTick(input)) {
            handleNetworkDump();
        } else if (SpeakerToNetworkProxyBolt.BOLT_ID.equals(source)) {
            handleNetworkNotification(input);
        } else {
            super.dispatch(input);
        }
    }

    @Override
    protected void handleInput(Tuple input) {
        unhandledInput(input);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputManager) {
        Fields fields = new Fields(
                FieldNameBasedTupleToKafkaMapper.BOLT_KEY, FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE,
                RegionAwareKafkaTopicSelector.FIELD_ID_TOPIC, RegionAwareKafkaTopicSelector.FIELD_ID_REGION);
        outputManager.declareStream(STREAM_SPEAKER_ID, fields);

        outputManager.declareStream(STREAM_REGION_NOTIFICATION_ID, STREAM_REGION_NOTIFICATION_FIELDS);
    }

    @Override
    public void init() {
        super.init();

        featureTogglesRepository = persistenceManager.getRepositoryFactory().createFeatureTogglesRepository();
        floodlightTracker = new FloodlightTracker(this, floodlights, floodlightAliveTimeout, floodlightAliveInterval);
    }

    private void handleTick() {
        floodlightTracker.emitAliveRequests();
        floodlightTracker.handleAliveExpiration();
    }

    private void handleNetworkDump() {
        if (!queryPeriodicSyncFeatureToggle()) {
            log.warn("Skip periodic network sync (disabled by feature toggle)");
            return;
        }

        log.debug("Do periodic network dump request");
        String dumpId = getCommandContext().getCorrelationId();
        for (String region : floodlights) {
            emitNetworkDumpRequest(region, dumpId);
        }
    }

    private void handleNetworkNotification(Tuple input) throws PipelineException {
        String stream = input.getSourceStreamId();
        if (SpeakerToNetworkProxyBolt.STREAM_ALIVE_EVIDENCE_ID.equals(stream)) {
            handleAliveEvidenceNotification(input);
        } else if (SpeakerToNetworkProxyBolt.STREAM_REGION_NOTIFICATION_ID.equals(stream)) {
            handleRegionNotification(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleAliveEvidenceNotification(Tuple input) throws PipelineException {
        String region = pullSpeakerRegion(input);
        long timestamp = pullValue(input, SpeakerToNetworkProxyBolt.FIELD_ID_TIMESTAMP, Long.class);

        floodlightTracker.handleAliveEvidence(region, timestamp);
    }

    private void handleRegionNotification(Tuple input) throws PipelineException {
        String region = pullSpeakerRegion(input);
        InfoData payload = pullValue(input, SpeakerToNetworkProxyBolt.FIELD_ID_PAYLOAD, InfoData.class);
        if (! handleRegionNotification(region, payload)) {
            unhandledInput(input);
        }
    }

    private boolean handleRegionNotification(String region, InfoData payload) {
        if (payload instanceof AliveResponse) {
            floodlightTracker.handleAliveResponse(region, (AliveResponse) payload);
        } else {
            return false;
        }
        return true;
    }

    // SwitchStatusCarrier implementation

    @Override
    public void emitSpeakerAliveRequest(String region) {
        AliveRequest request = new AliveRequest();
        CommandMessage message = new CommandMessage(
                request, System.currentTimeMillis(),
                getCommandContext().fork(String.format("alive-request(%s)", region)).getCorrelationId());
        getOutput().emit(
                STREAM_SPEAKER_ID, getCurrentTuple(), makeSpeakerTuple(null, message, region));
    }

    @Override
    public void emitNetworkDumpRequest(String region) {
        emitNetworkDumpRequest(region, null);
    }

    /**
     * Send network dump requests for target region.
     */
    @Override
    public void emitNetworkDumpRequest(String region, String dumpId) {
        String correlationId = getCommandContext().fork(String.format("network-dump(%s)", region)).getCorrelationId();
        CommandMessage command = new CommandMessage(
                new NetworkCommandData(dumpId), System.currentTimeMillis(), correlationId);

        log.info("Send network dump request (correlation-id: {})", correlationId);
        getOutput().emit(STREAM_SPEAKER_ID, getCurrentTuple(), makeSpeakerTuple(correlationId, command, region));
    }

    @Override
    public void emitRegionBecameUnavailableNotification(String region) {
        getOutput().emit(STREAM_REGION_NOTIFICATION_ID, getCurrentTuple(), makeRegionNotificationTuple(region));
    }

    private boolean queryPeriodicSyncFeatureToggle() {
        return featureTogglesRepository.getOrDefault().getFloodlightRoutePeriodicSync();
    }

    private String pullSpeakerRegion(Tuple tuple) throws PipelineException {
        return pullValue(tuple, SpeakerToNetworkProxyBolt.FIELD_ID_REGION, String.class);
    }

    private Values makeSpeakerTuple(String key, Message payload, String region) {
        return new Values(key, payload, kafkaSpeakerTopic, region);
    }

    private Values makeRegionNotificationTuple(String region) {
        return new Values(region, getCommandContext().fork(region));
    }
}
