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
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.NetworkCommandData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.switches.UnmanagedSwitchNotification;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.Stream;
import org.openkilda.wfm.topology.floodlightrouter.service.FloodlightTracker;
import org.openkilda.wfm.topology.floodlightrouter.service.MessageSender;
import org.openkilda.wfm.topology.floodlightrouter.service.RouterService;
import org.openkilda.wfm.topology.floodlightrouter.service.SwitchMapping;
import org.openkilda.wfm.topology.utils.AbstractTickRichBolt;

import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DiscoveryBolt extends AbstractTickRichBolt implements MessageSender {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryBolt.class);

    private final PersistenceManager persistenceManager;

    private final Set<String> floodlights;
    private final long floodlightAliveTimeout;
    private final long floodlightAliveInterval;
    private final long floodlightDumpInterval;
    private long lastNetworkDumpTimestamp;

    private transient FeatureTogglesRepository featureTogglesRepository;
    private transient RouterService routerService;
    private transient CommandContext commandContext;

    private Tuple currentTuple;

    public DiscoveryBolt(PersistenceManager persistenceManager, Set<String> floodlights, long floodlightAliveTimeout,
                         long floodlightAliveInterval, long floodlightDumpInterval) {
        this.persistenceManager = persistenceManager;
        this.floodlights = floodlights;
        this.floodlightAliveTimeout = floodlightAliveTimeout;
        this.floodlightAliveInterval = floodlightAliveInterval;
        this.floodlightDumpInterval = TimeUnit.SECONDS.toMillis(floodlightDumpInterval);
    }

    @Override
    protected void doTick(Tuple tuple) {
        setupTuple(tuple);

        try {
            handleTick();
        } finally {
            cleanupTuple();
        }
    }

    @Override
    protected void doWork(Tuple input) {
        currentTuple = input;
        try {
            handleInput(input);
        } catch (Exception e) {
            logger.error("Failed to process tuple {}", input, e);
        } finally {
            outputCollector.ack(input);
            cleanupTuple();
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        Fields kafkaFields = new Fields(FieldNameBasedTupleToKafkaMapper.BOLT_KEY,
                                        FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE);
        for (String region : floodlights) {
            outputFieldsDeclarer.declareStream(Stream.formatWithRegion(Stream.SPEAKER_DISCO, region), kafkaFields);
        }
        outputFieldsDeclarer.declareStream(Stream.KILDA_TOPO_DISCO, kafkaFields);
        outputFieldsDeclarer.declareStream(Stream.REGION_NOTIFICATION, new Fields(AbstractTopology.MESSAGE_FIELD,
                                                                                  AbstractBolt.FIELD_ID_CONTEXT));
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        featureTogglesRepository = persistenceManager.getRepositoryFactory().createFeatureTogglesRepository();
        FloodlightTracker floodlightTracker = new FloodlightTracker(floodlights, floodlightAliveTimeout,
                floodlightAliveInterval);
        routerService = new RouterService(floodlightTracker);
        super.prepare(map, topologyContext, outputCollector);
    }

    private void handleTick() {
        routerService.doPeriodicProcessing(this);

        long now = System.currentTimeMillis();
        if (now >= lastNetworkDumpTimestamp + floodlightDumpInterval) {
            doNetworkDump();
            lastNetworkDumpTimestamp = now;
        }
    }

    private void handleInput(Tuple input) {
        String sourceComponent = input.getSourceComponent();
        Message message = (Message) input.getValueByField(AbstractTopology.MESSAGE_FIELD);

        // setup correct command context
        commandContext = new CommandContext(message);

        switch (sourceComponent) {
            case ComponentType.KILDA_TOPO_DISCO_REPLY_BOLT:
                routerService.processSpeakerDiscoResponse(this, message);
                break;
            default:
                logger.error("Unknown input stream handled: {}", sourceComponent);
                break;
        }
    }

    private void setupTuple(Tuple input) {
        currentTuple = input;
        commandContext = new CommandContext();
    }

    private void cleanupTuple() {
        currentTuple = null;
        commandContext = null;
    }

    // MessageSender implementation

    @Override
    public void emitSpeakerAliveRequest(String region) {
        AliveRequest request = new AliveRequest();
        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(),
                                                    commandContext.fork(String.format("alive-request(%s)", region))
                                                            .getCorrelationId());
        emitSpeakerMessage(message, region);
    }

    @Override
    public void emitSwitchUnmanagedNotification(SwitchId sw) {
        UnmanagedSwitchNotification notification = new UnmanagedSwitchNotification(sw);
        InfoMessage message = new InfoMessage(notification, System.currentTimeMillis(),
                                              commandContext.fork(String.format("unmanaged(%s)", sw.toOtsdFormat()))
                                                      .getCorrelationId());
        emitControllerMessage(sw.toString(), message);
    }

    /**
     * Send network dump requests for target region.
     */
    @Override
    public void emitNetworkDumpRequest(String region) {
        String correlationId = commandContext.fork(String.format("network-dump(%s)", region)).getCorrelationId();
        CommandMessage command = new CommandMessage(new NetworkCommandData(),
                                                    System.currentTimeMillis(), correlationId);

        logger.info("Send network dump request (correlation-id: {})", correlationId);
        emitSpeakerMessage(correlationId, command, region);
    }

    @Override
    public void emitSpeakerMessage(Message message, String region) {
        emitSpeakerMessage(pullKeyFromCurrentTuple(), message, region);
    }

    @Override
    public void emitSpeakerMessage(String key, Message message, String region) {
        String stream = Stream.formatWithRegion(Stream.SPEAKER_DISCO, region);
        send(key, message, stream);
    }

    @Override
    public void emitControllerMessage(Message message) {
        emitControllerMessage(pullKeyFromCurrentTuple(), message);
    }

    @Override
    public void emitControllerMessage(String key, Message message) {
        send(key, message, Stream.KILDA_TOPO_DISCO);
    }

    @Override
    public void emitRegionNotification(SwitchMapping mapping) {
        outputCollector.emit(Stream.REGION_NOTIFICATION, currentTuple, new Values(mapping, commandContext));
    }

    private void send(String key, Message message, String outputStream) {
        Values values = new Values(key, message);
        outputCollector.emit(outputStream, currentTuple, values);
    }

    private String pullKeyFromCurrentTuple() {
        if (currentTuple.getFields().contains(AbstractTopology.KEY_FIELD)) {
            return currentTuple.getStringByField(AbstractTopology.KEY_FIELD);
        }
        return null;
    }

    private void doNetworkDump() {
        if (!queryPeriodicSyncFeatureToggle()) {
            logger.warn("Skip periodic network sync (disabled by feature toggle)");
            return;
        }

        logger.debug("Do periodic network dump request");
        for (String region : floodlights) {
            emitNetworkDumpRequest(region);
        }
    }

    private boolean queryPeriodicSyncFeatureToggle() {
        return featureTogglesRepository.getOrDefault().getFloodlightRoutePeriodicSync();
    }
}
