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
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.ConnectedDevicePacketBase;
import org.openkilda.messaging.info.event.IslBaseLatency;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.IslOneWayLatency;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.floodlightrouter.RegionAwareKafkaTopicSelector;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMapping;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingUpdate;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Slf4j
public class SpeakerToControllerProxyBolt extends AbstractBolt {
    private final String controllerTopic;

    private RegionMapping switchMapping;
    private final Duration switchMappingRemoveDelay;

    public SpeakerToControllerProxyBolt(String controllerTopic, Duration switchMappingRemoveDelay) {
        this.controllerTopic = controllerTopic;
        this.switchMappingRemoveDelay = switchMappingRemoveDelay;
    }

    @Override
    protected void dispatch(Tuple input) throws Exception {
        if (ZooKeeperSpout.SPOUT_ID.equals(input.getSourceComponent())) {
            LifecycleEvent event = (LifecycleEvent) input.getValueByField(ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT);
            handleLifeCycleEvent(event);
        } else if (active && SwitchMonitorBolt.BOLT_ID.equals(input.getSourceComponent())) {
            handleSwitchMappingUpdate(input);
        } else if (active) {
            super.dispatch(input);
        }
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String key = input.getStringByField(MessageKafkaTranslator.FIELD_ID_KEY);
        Object payload = pullValue(input, MessageKafkaTranslator.FIELD_ID_PAYLOAD, Object.class);
        proxy(key, payload);
    }

    private void handleSwitchMappingUpdate(Tuple input) throws PipelineException {
        RegionMappingUpdate update = pullValue(input, SwitchMonitorBolt.FIELD_ID_PAYLOAD, RegionMappingUpdate.class);
        switchMapping.apply(update);
    }

    private void proxy(String key, Object value) {
        if (value instanceof InfoMessage) {
            proxyInfoMessage(key, (InfoMessage) value);
        } else {
            proxyOther(key, value);
        }
    }

    protected void proxyInfoMessage(String key, InfoMessage envelope) {
        InfoData payload = envelope.getData();
        if (payload instanceof IslInfoData) {
            proxyOnlyIfActiveRegion(key, envelope, (IslInfoData) payload);
        } else if (payload instanceof PortInfoData) {
            proxyOnlyIfActiveRegion(key, envelope, (PortInfoData) payload);
        } else if (payload instanceof IslOneWayLatency) {
            proxyOnlyIfActiveRegion(key, envelope, (IslOneWayLatency) payload);
        } else if (payload instanceof IslBaseLatency) {
            proxyOnlyIfActiveRegion(key, envelope, (IslBaseLatency) payload);
        } else if (payload instanceof ConnectedDevicePacketBase) {
            proxyOnlyIfActiveRegion(key, envelope, (ConnectedDevicePacketBase) payload);
        } else {
            proxyOther(key, envelope);
        }
    }

    protected void proxyOther(String key, Object value) {
        if (value instanceof SpeakerFlowSegmentResponse) {
            ((SpeakerFlowSegmentResponse) value).setRouterPassTime(Instant.now().toEpochMilli());
        }
        getOutput().emit(getCurrentTuple(), makeDefaultTuple(key, value));
    }

    private void proxyOnlyIfActiveRegion(String key, InfoMessage envelope, IslInfoData payload) {
        SwitchId switchId = payload.getDestination().getSwitchId();
        proxyOnlyIfActiveRegion(key, envelope, switchId);
    }

    private void proxyOnlyIfActiveRegion(String key, InfoMessage envelope, PortInfoData payload) {
        proxyOnlyIfActiveRegion(key, envelope, payload.getSwitchId());
    }

    private void proxyOnlyIfActiveRegion(String key, InfoMessage envelope, IslOneWayLatency payload) {
        proxyOnlyIfActiveRegion(key, envelope, payload.getDstSwitchId());
    }

    private void proxyOnlyIfActiveRegion(String key, InfoMessage envelope, IslBaseLatency payload) {
        proxyOnlyIfActiveRegion(key, envelope, payload.getSrcSwitchId());
    }

    private void proxyOnlyIfActiveRegion(String key, InfoMessage envelope, ConnectedDevicePacketBase payload) {
        proxyOnlyIfActiveRegion(key, envelope, payload.getSwitchId());
    }

    private void proxyOnlyIfActiveRegion(String key, InfoMessage envelope, SwitchId switchId) {
        switchMapping.lookupReadWriteRegion(switchId).ifPresent(activeRegion -> {
            String region = envelope.getRegion();
            if (Objects.equals(activeRegion, region)) {
                proxyOther(key, envelope);
            } else {
                log.debug(
                        "Suppress speaker event {} (received via region \"{}\" while active region is \"{}\")",
                        envelope, region, activeRegion);
            }
        });
    }

    @Override
    protected void init() {
        super.init();

        switchMapping = new RegionMapping(Clock.systemUTC(), switchMappingRemoveDelay);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        Fields fields = new Fields(
                FieldNameBasedTupleToKafkaMapper.BOLT_KEY, FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE,
                RegionAwareKafkaTopicSelector.FIELD_ID_TOPIC, RegionAwareKafkaTopicSelector.FIELD_ID_REGION);
        outputFieldsDeclarer.declare(fields);
        outputFieldsDeclarer.declareStream(ZkStreams.ZK.toString(), new Fields(ZooKeeperBolt.FIELD_ID_STATE,
                ZooKeeperBolt.FIELD_ID_CONTEXT));
    }

    protected Values makeDefaultTuple(String key, Object payload) {
        return new Values(key, payload, controllerTopic, null);
    }
}
