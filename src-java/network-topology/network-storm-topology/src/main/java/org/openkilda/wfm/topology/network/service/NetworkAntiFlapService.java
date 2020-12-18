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

package org.openkilda.wfm.topology.network.service;

import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.controller.AntiFlapFsm;
import org.openkilda.wfm.topology.network.controller.AntiFlapFsm.Context;
import org.openkilda.wfm.topology.network.controller.AntiFlapFsm.Event;
import org.openkilda.wfm.topology.network.controller.AntiFlapFsm.State;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class NetworkAntiFlapService {
    private final AntiFlapFsm.AntiFlapFsmFactory controllerFactory;
    private final Map<Endpoint, AntiFlapFsm> controller = new HashMap<>();
    private final FsmExecutor<AntiFlapFsm, State, Event, Context> controllerExecutor;

    private final IAntiFlapCarrier carrier;
    private final AntiFlapFsm.Config config;

    public NetworkAntiFlapService(IAntiFlapCarrier carrier, AntiFlapFsm.Config config) {
        this.carrier = carrier;
        this.config = config;

        controllerFactory = AntiFlapFsm.factory(NetworkTopologyDashboardLogger.builder());
        controllerExecutor = controllerFactory.produceExecutor();
    }

    public void filterLinkStatus(Endpoint endpoint, LinkStatus status) {
        filterLinkStatus(endpoint, status, now());
    }

    @VisibleForTesting
    void filterLinkStatus(Endpoint endpoint, LinkStatus status, long timeMs) {
        AntiFlapFsm fsm = locateController(endpoint);
        AntiFlapFsm.Event event;
        switch (status) {
            case UP:
                event = AntiFlapFsm.Event.PORT_UP;
                break;
            case DOWN:
                event = AntiFlapFsm.Event.PORT_DOWN;
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("Unsupported %s value %s", LinkStatus.class.getName(), status));
        }
        log.debug("Physical port {} become {}", endpoint, event);
        controllerExecutor.fire(fsm, event, new AntiFlapFsm.Context(carrier, timeMs));
    }

    /**
     * Process timer tick.
     */
    public void tick() {
        tick(now());
    }

    @VisibleForTesting
    void tick(long timeMs) {
        controller.values().forEach(fsm ->
                controllerExecutor.fire(fsm, AntiFlapFsm.Event.TICK, new AntiFlapFsm.Context(carrier, timeMs)));
    }

    public void reset() {
        log.info("Clean all ports anti-flap state");
        controller.clear();
    }

    // -- private --

    private AntiFlapFsm locateController(Endpoint endpoint) {
        AntiFlapFsm fsm = controller.get(endpoint);
        if (fsm == null) {
            fsm = controllerFactory.produce(config.toBuilder().endpoint(endpoint).build());
            controller.put(endpoint, fsm);
        }
        return fsm;
    }

    private long now() {
        return System.nanoTime();
    }
}
