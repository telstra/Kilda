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

package org.openkilda.floodlight.kafka;

import org.openkilda.bluegreen.LifeCycleObserver;
import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.bluegreen.ZkStateTracker;
import org.openkilda.bluegreen.ZkWatchDog;
import org.openkilda.bluegreen.ZkWriter;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.util.UUID;

@Slf4j
public class ZooKeeperHandler implements Runnable, LifeCycleObserver {

    public static final String ZK_COMPONENT_NAME = "floodlight";

    @Getter
    private volatile LifecycleEvent event;

    private ZkStateTracker zkStateTracker;

    private long messageId = 0;
    private boolean awaitingShutdown = false;

    public ZooKeeperHandler(String id, String serviceName, String connectionString) {
        try {
            ZkWatchDog watchDog = ZkWatchDog.builder().id(id).serviceName(serviceName)
                    .connectionString(connectionString).build();
            watchDog.subscribe(this);

            ZkWriter zkWriter = ZkWriter.builder().id(id).serviceName(serviceName)
                    .connectionString(connectionString).build();
            zkStateTracker = new ZkStateTracker(zkWriter);
        } catch (IOException | InterruptedException | KeeperException e) {
            log.error("Failed to init ZooKeeper with connection string: {}, received: {}",
                    connectionString, e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            synchronized (this) {
                while (true) {
                    wait();
                }
            }
        } catch (InterruptedException e) {
            log.error("Zookeeper handler loop has been interrupted", e);
        }
    }

    @Override
    public void handle(Signal signal) {
        log.info("Received signal {}", signal);

        LifecycleEvent event = LifecycleEvent.builder()
                .signal(signal)
                .uuid(UUID.randomUUID())
                .messageId(messageId++).build();

        if (this.event == null || !this.event.getSignal().equals(signal)) {
            this.event = event;
            if (this.event.getSignal().equals(Signal.START)) {
                zkStateTracker.processLifecycleEvent(event);
            } else if (this.event.getSignal().equals(Signal.SHUTDOWN)) {
                awaitingShutdown = true;
            }
        }
    }

    /**
     * Update zookeeper state when signal is SHUTDOWN.
     */
    public void updateZkStateIfShutdown() {
        if (awaitingShutdown && this.event.getSignal().equals(Signal.SHUTDOWN)) {
            zkStateTracker.processLifecycleEvent(event);
            awaitingShutdown = false;
        }
    }
}
