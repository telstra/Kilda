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

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.stats.StatsRequest;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.floodlightrouter.RegionAwareKafkaTopicSelector;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMapping;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingUpdate;
import org.openkilda.wfm.topology.floodlightrouter.service.ControllerToSpeakerProxyCarrier;
import org.openkilda.wfm.topology.floodlightrouter.service.ControllerToSpeakerProxyService;
import org.openkilda.wfm.topology.floodlightrouter.service.RouterUtils;
import org.openkilda.wfm.topology.utils.KafkaRecordTranslator;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.time.Duration;
import java.util.Set;

@Slf4j
public class ControllerToSpeakerProxyBolt extends AbstractBolt implements ControllerToSpeakerProxyCarrier {
    private final String targetTopic;

    protected transient RegionMapping switchMapping;
    protected final Set<String> allRegions;
    private final Duration switchMappingRemoveDelay;

    private transient ControllerToSpeakerProxyService service;

    public ControllerToSpeakerProxyBolt(String targetTopic, Set<String> allRegions, Duration switchMappingRemoveDelay) {
        this.targetTopic = targetTopic;
        this.allRegions = allRegions;
        this.switchMappingRemoveDelay = switchMappingRemoveDelay;
    }

    @Override
    protected void dispatch(Tuple input) throws Exception {
        if (ZooKeeperSpout.BOLT_ID.equals(input.getSourceComponent())) {
            LifecycleEvent event = (LifecycleEvent) input.getValueByField(ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT);
            handleLifeCycleEvent(event);
        } else if (active && SwitchMonitorBolt.BOLT_ID.equals(input.getSourceComponent())) {
            handleSwitchMappingUpdate(input);
        } else if (active) {
            super.dispatch(input);
        }
    }

    @Override
    public void handleInput(Tuple input) throws Exception {
        Object raw = pullControllerPayload(input);
        if (raw instanceof Message) {
            handleControllerRequest((Message) raw);
        } else if (raw instanceof AbstractMessage) {
            handleControllerRequest((AbstractMessage) raw);
        } else {
            unhandledInput(input);
        }
    }

    private void handleSwitchMappingUpdate(Tuple input) throws PipelineException {
        service.switchMappingUpdate(pullValue(input, SwitchMonitorBolt.FIELD_ID_PAYLOAD, RegionMappingUpdate.class));
    }

    private void handleControllerRequest(Message message) {
        if (message instanceof CommandMessage) {
            handleControllerRequest((CommandMessage) message);
        } else {
            service.unicastRequest(message);
        }
    }

    private void handleControllerRequest(CommandMessage message) {
        CommandData payload = message.getData();
        if (payload instanceof StatsRequest) {
            service.statsRequest((StatsRequest) payload, message.getCorrelationId());
        } else if (RouterUtils.isBroadcast(payload)) {
            service.broadcastRequest(message);
        } else {
            service.unicastRequest(message);
        }
    }

    private void handleControllerRequest(AbstractMessage message) {
        service.unicastHsRequest(message);
    }

    protected void init() {
        service = new ControllerToSpeakerProxyService(this, allRegions, switchMappingRemoveDelay);
    }

    // ControllerToSpeakerProxyCarrier

    public void sendToSpeaker(Message message, String region) {
        getOutput().emit(getCurrentTuple(), makeDefaultTuple(message, pullKafkaKey(), region));
    }

    public void sendToSpeaker(AbstractMessage message, String region) {
        getOutput().emit(getCurrentTuple(), makeDefaultTuple(message, pullKafkaKey(), region));
    }

    @Override
    public void regionNotFoundError(Message message, SwitchId switchId) {
        handleRegionNotFoundError(message, switchId);
    }

    @Override
    public void regionNotFoundError(AbstractMessage message, SwitchId switchId) {
        handleRegionNotFoundError(message, switchId);
    }

    // stream management

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        Fields fields = new Fields(
                FieldNameBasedTupleToKafkaMapper.BOLT_KEY, FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE,
                RegionAwareKafkaTopicSelector.FIELD_ID_TOPIC, RegionAwareKafkaTopicSelector.FIELD_ID_REGION);
        outputFieldsDeclarer.declare(fields);
        outputFieldsDeclarer.declareStream(ZkStreams.ZK.toString(), new Fields(ZooKeeperBolt.FIELD_ID_STATE,
                ZooKeeperBolt.FIELD_ID_CONTEXT));
    }

    protected Object pullControllerPayload(Tuple tuple) throws PipelineException {
        return pullValue(tuple, KafkaRecordTranslator.FIELD_ID_PAYLOAD, Object.class);
    }

    protected Values makeDefaultTuple(Object payload, String key, String region) {
        return new Values(key, payload, targetTopic, region);
    }

    // own code

    protected void handleRegionNotFoundError(Object payload, SwitchId switchId) {
        log.error("Unable to route request - region that owns switch {} is unknown (message: {})", switchId, payload);
    }

    private String pullKafkaKey() {
        String result;
        Tuple tuple = getCurrentTuple();
        try {
            result = pullValue(tuple, KafkaRecordTranslator.FIELD_ID_KEY, String.class);
        } catch (PipelineException e) {
            log.error("Unable to read kafka-key from tuple {}: {}", formatTuplePayload(tuple), e);
            return null;
        }
        return result;
    }
}
