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
import static org.mockito.Mockito.when;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.DiscoPacketSendingConfirmation;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.Stream;
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
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class SpeakerToNetworkProxyBoltTest {
    private static final int TASK_ID_SPOUT = 0;
    private static final int ZOOKEEPER_SPOUT = 1;
    private static final String STREAM_SPOUT_DEFAULT = Utils.DEFAULT_STREAM_ID;

    private SpeakerToNetworkProxyBolt subject;

    private static final SwitchId switchAlpha = new SwitchId(1);

    private static final String REGION_ONE = "1";

    private static final Signal START_SIGNAL = Signal.START;

    @Mock
    private TopologyContext topologyContext;

    @Mock
    private OutputCollector outputCollector;

    private GeneralTopologyContext generalTopologyContext;

    private final Map<String, String> topologyConfig = Collections.emptyMap();

    @Before
    public void setUp() {
        subject = new SpeakerToNetworkProxyBolt(Stream.KILDA_TOPO_DISCO, Duration.ofSeconds(900));

        when(topologyContext.getThisTaskId()).thenReturn(1);
        subject.prepare(topologyConfig, topologyContext, outputCollector);

        StormTopology topology = mock(StormTopology.class);

        Map<Integer, String> taskToComponent = ImmutableMap.of(
                TASK_ID_SPOUT, ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT,
                ZOOKEEPER_SPOUT, ZooKeeperSpout.BOLT_ID);
        Map<String, Map<String, Fields>> componentToFields = ImmutableMap.of(
                ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT, ImmutableMap.of(
                        Utils.DEFAULT_STREAM_ID, new Fields(
                                KafkaRecordTranslator.FIELD_ID_KEY, KafkaRecordTranslator.FIELD_ID_PAYLOAD,
                                AbstractBolt.FIELD_ID_CONTEXT)),
                ZooKeeperSpout.BOLT_ID, ImmutableMap.of(
                        Utils.DEFAULT_STREAM_ID, new Fields(
                                ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT, ZooKeeperSpout.FIELD_ID_CONTEXT)));
        generalTopologyContext = new GeneralTopologyContext(
                topology, topologyConfig, taskToComponent, Collections.emptyMap(), componentToFields, "dummy");
    }

    @Test
    public void verifySpeakerToConsumerTupleConsistency() throws Exception {
        injectLifecycleEventUpdate(START_SIGNAL);
        ArgumentCaptor<Values> outputCaptor = ArgumentCaptor.forClass(Values.class);
        verify(outputCollector).emit(anyString(), any(Tuple.class), outputCaptor.capture());
        Values output = outputCaptor.getValue();
        assertEquals(START_SIGNAL, ((LifecycleEvent) output.get(0)).getSignal());

        InfoMessage discoveryConfirmation = new InfoMessage(
                new DiscoPacketSendingConfirmation(new NetworkEndpoint(switchAlpha, 1), 1L),
                3L, "discovery-confirmation", REGION_ONE);
        Tuple tuple = new TupleImpl(
                generalTopologyContext, new Values(
                        switchAlpha.toString(), discoveryConfirmation, new CommandContext(discoveryConfirmation)),
                TASK_ID_SPOUT, STREAM_SPOUT_DEFAULT);
        subject.execute(tuple);
        ArgumentCaptor<Values> discoReplyValuesCaptor = ArgumentCaptor.forClass(Values.class);

        verify(outputCollector).emit(
                eq(SpeakerToNetworkProxyBolt.STREAM_ALIVE_EVIDENCE_ID), eq(tuple), discoReplyValuesCaptor.capture());

        assertEquals(REGION_ONE, discoReplyValuesCaptor.getValue().get(0));
        assertEquals(discoveryConfirmation.getTimestamp(), discoReplyValuesCaptor.getValue().get(1));

        ArgumentCaptor<Values> topoDiscoCaptor = ArgumentCaptor.forClass(Values.class);
        verify(outputCollector).emit(eq(tuple), topoDiscoCaptor.capture());
        assertEquals(switchAlpha.toString(), topoDiscoCaptor.getValue().get(0));
        assertEquals(discoveryConfirmation, topoDiscoCaptor.getValue().get(1));
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
}
