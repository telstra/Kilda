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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingSet;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingUpdate;
import org.openkilda.wfm.topology.utils.KafkaRecordTranslator;

import com.google.common.collect.ImmutableMap;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.task.GeneralTopologyContext;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class ControllerToSpeakerProxyBoltTest {
    private static final int TASK_ID_SPOUT = 0;
    private static final int SWITCH_MONITOR_BOLT = 1;
    private static final int ZOOKEEPER_SPOUT = 2;
    private static final String STREAM_SPOUT_DEFAULT = Utils.DEFAULT_STREAM_ID;

    private ControllerToSpeakerProxyBolt subject;

    private static final SwitchId switchAlpha = new SwitchId(1);

    private static final String REGION_ONE = "1";
    private static final String REGION_TWO = "2";

    private static final String TARGET_TOPIC = "topic";

    private static final Signal START_SIGNAL = Signal.START;

    @Mock
    private TopologyContext topologyContext;

    @Mock
    private OutputCollector outputCollector;

    private GeneralTopologyContext generalTopologyContext;

    private final Map<String, String> topologyConfig = Collections.emptyMap();

    @Before
    public void setUp() {
        Set<String> regions = new HashSet<>();
        regions.add(REGION_ONE);
        regions.add(REGION_TWO);
        subject = new ControllerToSpeakerProxyBolt(TARGET_TOPIC, regions, Duration.ofSeconds(900));

        when(topologyContext.getThisTaskId()).thenReturn(1);
        subject.prepare(topologyConfig, topologyContext, outputCollector);

        StormTopology topology = mock(StormTopology.class);

        Map<Integer, String> taskToComponent = ImmutableMap.of(
                TASK_ID_SPOUT, ComponentType.SPEAKER_KAFKA_SPOUT,
                SWITCH_MONITOR_BOLT, SwitchMonitorBolt.BOLT_ID,
                ZOOKEEPER_SPOUT, ZooKeeperSpout.BOLT_ID);
        Map<String, Map<String, Fields>> componentToFields = ImmutableMap.of(
                ComponentType.SPEAKER_KAFKA_SPOUT, ImmutableMap.of(
                        Utils.DEFAULT_STREAM_ID, new Fields(
                                KafkaRecordTranslator.FIELD_ID_KEY, KafkaRecordTranslator.FIELD_ID_PAYLOAD,
                                AbstractBolt.FIELD_ID_CONTEXT)),
                SwitchMonitorBolt.BOLT_ID, ImmutableMap.of(
                        SwitchMonitorBolt.STREAM_REGION_MAPPING_ID, SwitchMonitorBolt.STREAM_REGION_MAPPING_FIELDS),
                ZooKeeperSpout.BOLT_ID, ImmutableMap.of(
                        Utils.DEFAULT_STREAM_ID, new Fields(
                                ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT, ZooKeeperSpout.FIELD_ID_CONTEXT)));
        generalTopologyContext = new GeneralTopologyContext(
                topology, topologyConfig, taskToComponent, Collections.emptyMap(), componentToFields, "dummy");
    }

    @Test
    public void verifyMissingAnyRegionMapping() {
        injectDiscoveryRequest(switchAlpha);
        verifyNoMoreInteractions(outputCollector);
    }

    @Test
    public void verifyMissingRwRegionMapping() {
        injectRegionUpdate(new RegionMappingSet(switchAlpha, REGION_ONE, false));
        injectDiscoveryRequest(switchAlpha);
        verifyNoMoreInteractions(outputCollector);
    }

    @Test
    public void verifyHappyPath() {
        injectLifecycleEventUpdate(START_SIGNAL);
        ArgumentCaptor<Values> outputCaptor = ArgumentCaptor.forClass(Values.class);
        verify(outputCollector).emit(anyString(), any(Tuple.class), outputCaptor.capture());
        Values output = outputCaptor.getValue();
        assertEquals(START_SIGNAL, ((LifecycleEvent) output.get(0)).getSignal());

        injectRegionUpdate(new RegionMappingSet(switchAlpha, REGION_ONE, true));
        final CommandMessage request = injectDiscoveryRequest(switchAlpha);
        verify(outputCollector).emit(any(Tuple.class), outputCaptor.capture());

        assertEquals(switchAlpha.toString(), outputCaptor.getValue().get(0));
        output = outputCaptor.getValue();
        assertEquals(switchAlpha.toString(), output.get(0));  // key
        assertEquals(request, output.get(1)); // value
        assertEquals(TARGET_TOPIC, output.get(2)); // topic
        assertEquals(REGION_ONE, output.get(3)); // region

        verifyNoMoreInteractions(outputCollector);
    }

    private void injectLifecycleEventUpdate(Signal signal) {
        LifecycleEvent event = LifecycleEvent.builder()
                .signal(signal)
                .build();
        Tuple input = new TupleImpl(
                generalTopologyContext, new Values(event, new CommandContext()),
                ZOOKEEPER_SPOUT, STREAM_SPOUT_DEFAULT);
        subject.execute(input);
        verify(outputCollector).ack(eq(input));
    }

    private void injectRegionUpdate(RegionMappingUpdate update) {
        Tuple input = new TupleImpl(
                generalTopologyContext, new Values(update, new CommandContext()),
                SWITCH_MONITOR_BOLT, RegionTrackerBolt.STREAM_REGION_NOTIFICATION_ID);
        subject.execute(input);
        verify(outputCollector).ack(eq(input));
    }

    private CommandMessage injectDiscoveryRequest(SwitchId switchId) {
        CommandMessage discoCommand = new CommandMessage(
                new DiscoverIslCommandData(switchId, 1, 1L),
                3L, "discovery-confirmation");
        Tuple input = new TupleImpl(
                generalTopologyContext,
                new Values(switchId.toString(), discoCommand, new CommandContext(discoCommand)),
                TASK_ID_SPOUT, STREAM_SPOUT_DEFAULT);
        subject.execute(input);
        verify(outputCollector).ack(eq(input));
        return discoCommand;
    }
}
