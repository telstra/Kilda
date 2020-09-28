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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.controller.bfd.BfdLogicalPortFsm.BfdLogicalPortFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdLogicalPortFsm.BfdLogicalPortFsmFactory;
import org.openkilda.wfm.topology.network.controller.bfd.BfdLogicalPortFsm.Event;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class SwitchStatusMonitor {
    @Getter
    private final SwitchId switchId;

    private final Map<Endpoint, BfdLogicalPortFsm> controllers = new HashMap<>();

    @Getter
    private boolean online = false;

    public SwitchStatusMonitor(SwitchId switchId) {
        this.switchId = switchId;
    }

    /**
     * Update internal status cache and propagate status update on related controllers.
     */
    public void updateStatus(boolean isOnline) {
        if (online == isOnline) {
            return;
        }

        online = isOnline;
        Event event = isOnline ? Event.ONLINE : Event.OFFLINE;
        BfdLogicalPortFsmContext context = BfdLogicalPortFsmContext.builder().build();
        for (BfdLogicalPortFsm entry : controllers.values()) {
            BfdLogicalPortFsmFactory.EXECUTOR.fire(entry, event, context);
        }
    }

    /**
     * Update internal status cache and propagate status update on specific controller.
     */
    public void updateStatus(Endpoint physical, boolean isOnline) {
        BfdLogicalPortFsm entry = controllers.get(physical);
        if (entry != null) {
            online = isOnline;

            BfdLogicalPortFsmContext context = BfdLogicalPortFsmContext.builder().build();
            BfdLogicalPortFsmFactory.EXECUTOR.fire(entry, isOnline ? Event.ONLINE : Event.OFFLINE, context);
        }
    }

    public void addController(BfdLogicalPortFsm entity) {
        controllers.put(entity.getPhysicalEndpoint(), entity);
    }

    public void delController(BfdLogicalPortFsm entity) {
        controllers.remove(entity.getPhysicalEndpoint());
    }

    public boolean isEmpty() {
        return !online && controllers.isEmpty();
    }
}
