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

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;

import java.io.IOException;

@Slf4j
public class ZkWriter extends ZkClient {
    private static final String STATE = "state";
    private String statePath;

    @Builder
    public ZkWriter(String id, String serviceName, String connectionString,
                    int sessionTimeout) throws IOException, KeeperException, InterruptedException {
        super(id, serviceName, connectionString, sessionTimeout);
        statePath = getPaths(serviceName, id, STATE);

        validateNodes();
    }

    /**
     * Sets state for service.
     */
    public void setState(int active) {
        try {
            zookeeper.setData(statePath, Integer.toString(active).getBytes(), -1);
        } catch (KeeperException e) {
            log.error("Zk keeper error: {}", e.getMessage(), e);
            throw new IllegalStateException("failed to update state");
        } catch (InterruptedException e) {
            log.error("Zk event interrupted: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to update state");
        }
    }


    @Override
    void validateNodes() throws KeeperException, InterruptedException {
        super.validateNodes();
        ensureZNode(serviceName, id, STATE);
    }

    @Override
    public void process(WatchedEvent event) {
        log.info("Received event: {}", event);
        try {
            refreshConnection(event.getState());
        } catch (IOException e) {
            log.error("Failed to read zk event: {}", e.getMessage(), e);
        }
    }


}
