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

package org.openkilda.bluegreen;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ZkStateTracker {
    private ZkWriter zooKeeperWriter;
    UUID shutdownUuid;
    UUID startUuid;
    int active;

    public ZkStateTracker(ZkWriter zooKeeperWriter) {
        this.zooKeeperWriter = zooKeeperWriter;
    }

    /**
     * Process new lifecycle event.
     */
    public void processLifecycleEvent(LifecycleEvent event) {
        if (Signal.START == event.getSignal()) {
            shutdownUuid = null;
            handleStart(event);
        } else if (Signal.SHUTDOWN == event.getSignal()) {
            handleShutdown(event);
            startUuid = null;
        }
        zooKeeperWriter.setState(active);
    }

    private void handleStart(LifecycleEvent event) {
        if (startUuid != null) {
            if (startUuid.equals(event.getUuid())) {
                active++;

            } else {
                startUuid = event.getUuid();
                active = 1;
            }
        } else {
            startUuid = event.getUuid();
            active++;
        }
    }

    private void handleShutdown(LifecycleEvent event) {
        if (shutdownUuid != null) {
            if (shutdownUuid.equals(event.getUuid())) {
                active--;
            } else {
                log.error("Received new uuid: {}, expected: {}", event.getUuid(), shutdownUuid);
            }
        } else {
            shutdownUuid = event.getUuid();
            active--;
        }
    }

    private void resetActive() {
        active = 0;
        startUuid = null;
    }

}
