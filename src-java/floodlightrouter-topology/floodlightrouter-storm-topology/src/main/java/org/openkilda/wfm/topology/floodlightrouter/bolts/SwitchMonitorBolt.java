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

package org.openkilda.wfm.topology.floodlightrouter.bolts;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.NetworkDumpSwitchData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.RegionAwareKafkaTopicSelector;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingUpdate;
import org.openkilda.wfm.topology.floodlightrouter.service.SwitchMonitorCarrier;
import org.openkilda.wfm.topology.floodlightrouter.service.monitor.SwitchMonitorService;

import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.time.Clock;

public class SwitchMonitorBolt extends AbstractBolt implements SwitchMonitorCarrier {
    public static final String BOLT_ID = ComponentType.SWITCH_MONITOR;

    public static final String FIELD_ID_PAYLOAD = "payload";

    public static final String STREAM_NETWORK_ID = "network";

    public static final String STREAM_REGION_MAPPING_ID = "region";
    public static final Fields STREAM_REGION_MAPPING_FIELDS = new Fields(FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private final String kafkaNetworkTopic;

    private transient Clock clock;
    private transient SwitchMonitorService service;

    public SwitchMonitorBolt(String kafkaNetworkTopic) {
        this.kafkaNetworkTopic = kafkaNetworkTopic;
    }

    @Override
    protected void handleInput(Tuple input) throws PipelineException {
        String source = input.getSourceComponent();
        if (ZooKeeperSpout.BOLT_ID.equals(input.getSourceComponent())) {
            LifecycleEvent event = (LifecycleEvent) input.getValueByField(ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT);
            handleLifeCycleEvent(event);
        } else if (MonotonicTick.BOLT_ID.equals(source) && active) {
            service.handleTimerTick();
        } else if (RegionTrackerBolt.BOLT_ID.equals(source) && active) {
            handleRegionOfflineNotification(input);
        } else if (SpeakerToNetworkProxyBolt.BOLT_ID.equals(source) && active) {
            handleSwitchConnectionNotification(input);
        } else if (active) {
            unhandledInput(input);
        }
    }

    private void handleRegionOfflineNotification(Tuple input) throws PipelineException {
        service.handleRegionOfflineNotification(pullRegion(input));
    }

    private void handleSwitchConnectionNotification(Tuple input) throws PipelineException {
        String region = pullRegion(input);
        InfoData payload = pullValue(input, SpeakerToNetworkProxyBolt.FIELD_ID_PAYLOAD, InfoData.class);
        if (payload instanceof SwitchInfoData) {
            service.handleStatusUpdateNotification((SwitchInfoData) payload, region);
        } else if (payload instanceof NetworkDumpSwitchData) {
            service.handleNetworkDumpResponse((NetworkDumpSwitchData) payload, region);
        } else {
            unhandledInput(input);
        }
    }

    // -- SwitchMonitorCarrier - implementation ----

    @Override
    public void regionUpdateNotification(RegionMappingUpdate mappingUpdate) {
        // do not bound produced tuple to current tuple, to avoid issued from looped streams
        getOutput().emit(STREAM_REGION_MAPPING_ID, makeRegionMappingTuple(mappingUpdate));
    }

    @Override
    public void switchStatusUpdateNotification(SwitchId switchId, InfoData notification) {
        InfoMessage message = new InfoMessage(
                notification, clock.instant().toEpochMilli(), getCommandContext().getCorrelationId());
        getOutput().emit(STREAM_NETWORK_ID, getCurrentTuple(), makeNetworkTuple(switchId.toString(), message));
    }

    private String pullRegion(Tuple input) throws PipelineException {
        return pullValue(input, RegionTrackerBolt.FIELD_ID_REGION, String.class);
    }

    // -- service methods ----

    private Values makeNetworkTuple(String key, Message payload) {
        return new Values(key, payload, kafkaNetworkTopic, null);
    }

    private Values makeRegionMappingTuple(RegionMappingUpdate mappingUpdate) {
        return new Values(mappingUpdate, getCommandContext());
    }

    // -- AbstractBolt - overrides ----
    @Override
    protected void init() {
        super.init();

        clock = Clock.systemUTC();
        service = new SwitchMonitorService(clock, this);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        Fields kafkaProducerFields = new Fields(
                FieldNameBasedTupleToKafkaMapper.BOLT_KEY, FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE,
                RegionAwareKafkaTopicSelector.FIELD_ID_TOPIC, RegionAwareKafkaTopicSelector.FIELD_ID_REGION);
        streamManager.declareStream(STREAM_NETWORK_ID, kafkaProducerFields);

        streamManager.declareStream(STREAM_REGION_MAPPING_ID, STREAM_REGION_MAPPING_FIELDS);
        streamManager.declareStream(ZkStreams.ZK.toString(), new Fields(ZooKeeperBolt.FIELD_ID_STATE,
                ZooKeeperBolt.FIELD_ID_CONTEXT));
    }
}
