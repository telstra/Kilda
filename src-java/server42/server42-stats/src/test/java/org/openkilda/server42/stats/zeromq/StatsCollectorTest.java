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

package org.openkilda.server42.stats.zeromq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.stats.FlowRttStatsData;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacket;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacketBucket;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacketBucket.Builder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {StatsCollector.class})
@TestPropertySource("classpath:test.properties")
@MockBean(value = {
        KafkaTemplate.class
})
public class StatsCollectorTest {

    @MockBean
    KafkaTemplate<String, Object> template;
    @Autowired
    StatsCollector statsCollector;
    @Value("${openkilda.server42.stats.kafka.topic.flowrtt.to_storm}")
    private String toStorm;

    @Test
    public void sendStatsTest() throws Exception {
        Builder bucketBuilder = FlowLatencyPacketBucket.newBuilder();
        FlowLatencyPacket packet1 = FlowLatencyPacket.newBuilder()
                .setFlowId("some-flow-id-1")
                .setDirection(false)
                .setT0(100)
                .setT1(150)
                .setPacketId(1).build();
        FlowLatencyPacket packet2 = FlowLatencyPacket.newBuilder()
                .setDirection(true)
                .setT0(200)
                .setT1(250)
                .setPacketId(2).build();
        bucketBuilder.addPacket(packet1);
        bucketBuilder.addPacket(packet2);

        statsCollector.sendStats(bucketBuilder.build());

        ArgumentCaptor<InfoMessage> argument = ArgumentCaptor.forClass(InfoMessage.class);
        verify(template).send(eq(toStorm), eq(packet1.getFlowId()), argument.capture());

        InfoMessage packet1Message = argument.getValue();
        FlowRttStatsData statsPacket1 = (FlowRttStatsData) packet1Message.getData();

        assertThat(statsPacket1).extracting(
                FlowRttStatsData::getFlowId,
                FlowRttStatsData::getT0,
                FlowRttStatsData::getT1)
                .contains(packet1.getFlowId(), packet1.getT0(), packet1.getT1());

        assertThat(statsPacket1)
                .extracting(FlowRttStatsData::getDirection)
                .isEqualTo("forward");

        verify(template).send(eq(toStorm), eq(packet2.getFlowId()), argument.capture());

        InfoMessage packet2Message = argument.getValue();
        FlowRttStatsData statsPacket2 = (FlowRttStatsData) packet2Message.getData();

        assertThat(statsPacket2).extracting(
                FlowRttStatsData::getFlowId,
                FlowRttStatsData::getT0,
                FlowRttStatsData::getT1)
                .contains(packet2.getFlowId(), packet2.getT0(), packet2.getT1());

        assertThat(statsPacket2)
                .extracting(FlowRttStatsData::getDirection)
                .isEqualTo("reverse");
    }
}
