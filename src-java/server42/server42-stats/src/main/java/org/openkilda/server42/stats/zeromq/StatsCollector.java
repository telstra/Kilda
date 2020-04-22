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

import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.stats.FlowRttStatsData;
import org.openkilda.server42.messaging.FlowDirection;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacket;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacketBucket;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import javax.annotation.PostConstruct;

@Service
@Slf4j
public class StatsCollector extends Thread {
    private final KafkaTemplate<String, Object> template;

    @Value("${openkilda.server42.stats.zeromq.server.endpoint}")
    private String connectEndpoint;

    @Value("${openkilda.server42.stats.kafka.topic.flowrtt.to_storm}")
    private String toStorm;

    public StatsCollector(KafkaTemplate<String, Object> template) {
        this.template = template;
    }

    /**
     * Connect to server42 and get statistics.
     */
    @Override
    public void run() {
        log.info("started");
        while (!isInterrupted()) {
            try (ZContext context = new ZContext()) {
                Socket server = context.createSocket(ZMQ.PULL);
                try {
                    server.connect(connectEndpoint);
                    while (!isInterrupted()) {
                        byte[] recv = server.recv();
                        log.debug("stats recived");
                        try {
                            FlowLatencyPacketBucket flowLatencyPacketBucket = FlowLatencyPacketBucket.parseFrom(recv);
                            log.debug("getPacketList size {}", flowLatencyPacketBucket.getPacketList().size());

                            for (FlowLatencyPacket packet : flowLatencyPacketBucket.getPacketList()) {
                                FlowRttStatsData data = new FlowRttStatsData(
                                        packet.getFlowId(),
                                        FlowDirection.fromBoolean(packet.getDirection()).name().toLowerCase(),
                                        packet.getT0(),
                                        packet.getT1()
                                );

                                InfoMessage message = new InfoMessage(data, System.currentTimeMillis(), "");

                                log.debug("InfoMessage {}", message.toString());
                                template.send(toStorm, packet.getFlowId(), message);
                                log.debug("after send");
                            }
                        } catch (InvalidProtocolBufferException e) {
                            log.error(e.toString());
                        }
                    }
                } finally {
                    server.close();
                }

            } catch (org.zeromq.ZMQException ex) {
                log.error(ex.toString());
            }
        }
    }

    @PostConstruct
    void init() {
        this.start();
    }
}
