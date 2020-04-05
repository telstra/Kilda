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

package org.openkilda.server42.control.serverstub;

import org.openkilda.server42.control.messaging.flowrtt.Control.Flow;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacket;
import org.openkilda.server42.stats.messaging.flowrtt.Statistics.FlowLatencyPacketBucket;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import java.time.Instant;
import java.util.HashMap;
import javax.annotation.PostConstruct;

/**
 * Send statistics to stats application.
 */
@Service
@Slf4j
public class StatsServer extends Thread {

    @Value
    @Builder
    static class FlowStats {
        String flowId;
        Boolean direction;
        Long baseLatency;
    }

    private final int minBaseLatency = 100;
    private final int maxBaseLatency = 10000;

    private final int minDeltaLatency = 10;
    private final int maxDeltaLatency = 100;

    private HashMap<String, FlowStats> flows = new HashMap<>();
    private long tickSize = 500;

    @org.springframework.beans.factory.annotation.Value("${openkilda.server42.control.zeromq.stats.server.endpoint}")
    private String bindEndpoint;

    /**
     * Generate packet statistics.
     */
    @Override
    public void run() {
        log.info("started");
        try (ZContext context = new ZContext()) {
            Socket server = context.createSocket(ZMQ.PUSH);
            server.bind(bindEndpoint);
            while (!isInterrupted()) {
                FlowLatencyPacketBucket.Builder flowBucketBuilder = FlowLatencyPacketBucket.newBuilder();
                FlowLatencyPacket.Builder flowLatencyPacketBuilder = FlowLatencyPacket.newBuilder();
                long millis = Instant.now().toEpochMilli();
                synchronized (this) {
                    for (FlowStats flow : flows.values()) {
                        flowLatencyPacketBuilder.setFlowId(flow.flowId);
                        flowLatencyPacketBuilder.setDirection(flow.direction);
                        flowLatencyPacketBuilder.setT0(millis);

                        long generatedLatency = minDeltaLatency
                                + (long) (Math.random() * (maxDeltaLatency - minDeltaLatency));
                        flowLatencyPacketBuilder.setT1(millis + flow.baseLatency + generatedLatency);
                        flowBucketBuilder.addPacket(flowLatencyPacketBuilder.build());
                    }
                }

                if (!flows.isEmpty()) {
                    server.send(flowBucketBuilder.build().toByteArray());
                    log.info("send stats");
                }

                try {
                    sleep(tickSize);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @PostConstruct
    void init() {
        this.start();
    }

    /**
     * Add flow to packet generator.
     */
    public synchronized void addFlow(Flow flow) {

        long generatedLatency = minBaseLatency + (long) (Math.random() * (maxBaseLatency - minBaseLatency));

        FlowStats flowStats = FlowStats.builder()
                .flowId(flow.getFlowId())
                .direction(flow.getDirection())
                .baseLatency(generatedLatency)
                .build();

        flows.put(flow.getFlowId(), flowStats);
    }

    public synchronized void removeFlow(String flowId) {
        flows.remove(flowId);
    }

    public synchronized void clearFlows() {
        flows.clear();
    }
}
