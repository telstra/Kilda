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

import org.openkilda.messaging.error.rule.SwitchSyncErrorData;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.info.switches.SwitchSyncResponse;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.network.controller.sw.SwitchFsm;
import org.openkilda.wfm.topology.network.controller.sw.SwitchFsm.SwitchFsmContext;
import org.openkilda.wfm.topology.network.controller.sw.SwitchFsm.SwitchFsmEvent;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.facts.HistoryFacts;
import org.openkilda.wfm.topology.network.utils.GenerationTracker;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class NetworkSwitchService {
    private static final long SWITCH_GENERATION_TRACKING_LIMIT = 32;

    private final SwitchFsm.SwitchFsmFactory controllerFactory;
    private final Map<SwitchId, SwitchFsm> controller = new HashMap<>();

    private final PersistenceManager persistenceManager;
    private final GenerationTracker<String> dumpGenerationTracker = new GenerationTracker<>(
            SWITCH_GENERATION_TRACKING_LIMIT);

    private final NetworkOptions options;

    ISwitchCarrier carrier;

    public NetworkSwitchService(ISwitchCarrier carrier, PersistenceManager persistenceManager,
                                NetworkOptions options) {
        this.carrier = carrier;
        this.persistenceManager = persistenceManager;
        this.options = options;

        controllerFactory = SwitchFsm.factory();

        log.info("Discovery switch service configuration: bfd-logical-port-offset:{}",
                 options.getBfdLogicalPortOffset());
    }

    /**
     * .
     */
    public void switchAddWithHistory(HistoryFacts history) {
        log.info("Switch service receive switch ADD from history request for {}", history.getSwitchId());
        SwitchFsm switchFsm = locateControllerCreateIfAbsent(history.getSwitchId());
        SwitchFsmContext fsmContext = SwitchFsmContext.builder(carrier)
                .history(history)
                .build();
        fire(switchFsm, SwitchFsmEvent.HISTORY, fsmContext);
    }

    /**
     * .
     */
    public void switchEvent(SwitchInfoData payload) {
        log.info("Switch service receive SWITCH event for {} status:{}", payload.getSwitchId(), payload.getState());
        SwitchFsmContext.SwitchFsmContextBuilder fsmContextBuilder = SwitchFsmContext.builder(carrier);
        SwitchFsmEvent event = null;

        switch (payload.getState()) {
            case ACTIVATED:
                event = SwitchFsmEvent.ONLINE;
                fsmContextBuilder
                        .speakerData(payload.getSwitchView())
                        .dumpGeneration(dumpGenerationTracker.getActual());
                break;
            case DEACTIVATED:
                event = SwitchFsmEvent.OFFLINE;
                break;

            default:
                log.info("Ignore switch event {} on {} (no need to handle it)", payload.getState(),
                        payload.getSwitchId());
                break;
        }

        if (event != null) {
            SwitchFsm fsm = locateControllerCreateIfAbsent(payload.getSwitchId());
            fire(fsm, event, fsmContextBuilder.build());
        }
    }

    /**
     * Handle switch synchronization response from SwitchManager topology.
     */
    public void switchManagerResponse(SwitchSyncResponse payload, String key) {
        log.info("Switch service receive switch synchronization response for {}", payload.getSwitchId());
        SwitchFsmContext.SwitchFsmContextBuilder fsmContextBuilder =
                SwitchFsmContext.builder(carrier).syncResponse(payload).syncKey(key);
        SwitchFsm fsm = locateController(payload.getSwitchId());
        fire(fsm, SwitchFsmEvent.SYNC_RESPONSE, fsmContextBuilder.build());
    }

    /**
     * Handle switch synchronization error response from SwitchManager topology.
     */
    public void switchManagerErrorResponse(SwitchSyncErrorData payload, String key) {
        log.error("Switch service receive switch synchronization error response for {}: {}",
                payload.getSwitchId(), payload);
        SwitchFsmContext.SwitchFsmContextBuilder fsmContextBuilder =
                SwitchFsmContext.builder(carrier).syncKey(key);
        SwitchFsm fsm = locateController(payload.getSwitchId());
        fire(fsm, SwitchFsmEvent.SYNC_ERROR, fsmContextBuilder.build());
    }

    /**
     * Handle switch synchronization timeout response from SwitchManager topology.
     */
    public void switchManagerTimeout(SwitchId switchId, String key) {
        log.error("Switch service receive switch synchronization timeout for {}:", switchId);
        SwitchFsmContext.SwitchFsmContextBuilder fsmContextBuilder =
                SwitchFsmContext.builder(carrier).syncKey(key);
        SwitchFsm fsm = locateController(switchId);
        fire(fsm, SwitchFsmEvent.SYNC_TIMEOUT, fsmContextBuilder.build());
    }

    /**
     * Handle switch UNMANAGED notification.
     */
    public void switchBecomeUnmanaged(SwitchId datapath) {
        log.debug("Switch service receive unmanaged notification for {}", datapath);
        SwitchFsm fsm = locateControllerCreateIfAbsent(datapath);
        SwitchFsmContext context = SwitchFsmContext.builder(carrier)
                .isRegionOffline(true)
                .build();
        fire(fsm, SwitchFsmEvent.OFFLINE, context);
    }

    /**
     * Handle switch MANAGED notification.
     */
    public void switchBecomeManaged(SpeakerSwitchView switchView, String dumpId) {
        SwitchId switchId = switchView.getDatapath();
        log.debug("Switch service receive MANAGED notification for {}", switchId);

        long actualGeneration = dumpGenerationTracker.getActual();
        SwitchFsmContext.SwitchFsmContextBuilder context = SwitchFsmContext.builder(carrier)
                .speakerData(switchView);
        if (dumpId != null) {
            long generation = dumpGenerationTracker.identify(dumpId);
            log.debug("Map dumpId into generation {} => {} for {}", dumpId, generation, switchId);
            context.dumpGeneration(generation);
        } else {
            log.debug("Do not make generation lookup for partial network dump entry (switchId: {})", switchId);
        }

        SwitchFsm fsm = locateControllerCreateIfAbsent(switchId);
        fire(fsm, SwitchFsmEvent.ONLINE, context.build());

        if (actualGeneration != dumpGenerationTracker.getActual()) {
            lookupMissingSwitches();
        }
    }

    /**
     * .
     */
    public void switchPortEvent(PortInfoData payload) {
        log.debug("Switch service receive PORT event for {} port:{}, status:{}",
                  payload.getSwitchId(), payload.getPortNo(), payload.getState());
        SwitchFsmContext.SwitchFsmContextBuilder fsmContext = SwitchFsmContext.builder(carrier)
                .portNumber(payload.getPortNo());
        SwitchFsmEvent event = null;
        switch (payload.getState()) {
            case ADD:
                event = SwitchFsmEvent.PORT_ADD;
                fsmContext.portEnabled(payload.getEnabled());
                break;
            case DELETE:
                event = SwitchFsmEvent.PORT_DEL;
                break;
            case UP:
                event = SwitchFsmEvent.PORT_UP;
                break;
            case DOWN:
                event = SwitchFsmEvent.PORT_DOWN;
                break;

            default:
                log.info("Ignore port event {} for {}_{} (no need to handle it)",
                        payload.getState(), payload.getSwitchId(), payload.getPortNo());
        }

        if (event != null) {
            SwitchFsm switchFsm = locateController(payload.getSwitchId());
            fire(switchFsm, event, fsmContext.build());
        }
    }

    /**
     * Remove isl by request.
     */
    public void remove(SwitchId datapath) {
        log.info("Switch service receive remove request for {}", datapath);
        SwitchFsm fsm = controller.get(datapath);
        if (fsm == null) {
            log.info("Got DELETE request for not existing switch {}", datapath);
            return;
        }

        SwitchFsmContext context = SwitchFsmContext.builder(carrier).build();
        fire(fsm, SwitchFsmEvent.SWITCH_REMOVE, context);
        if (fsm.isTerminated()) {
            controller.remove(datapath);
            log.debug("Switch service removed FSM {}", datapath);
        } else {
            log.error("Switch service remove failed for FSM {}, state: {}", datapath, fsm.getCurrentState());
        }
    }

    // -- private --

    /**
     * Find switches not mentioned in N last periodic dumps (lost OFFLINE event).
     */
    private void lookupMissingSwitches() {
        long actualGeneration = dumpGenerationTracker.getActual();
        for (SwitchFsm entry : controller.values()) {
            entry.ensureOnline(carrier, actualGeneration);
        }
    }

    private SwitchFsm locateController(SwitchId datapath) {
        SwitchFsm switchFsm = controller.get(datapath);
        if (switchFsm == null) {
            throw new IllegalStateException(String.format("Switch FSM not found (%s).", datapath));
        }
        return switchFsm;
    }

    private SwitchFsm locateControllerCreateIfAbsent(SwitchId datapath) {
        return controller.computeIfAbsent(
                datapath, key -> controllerFactory.produce(
                        persistenceManager, datapath, options, dumpGenerationTracker.getActual()));
    }

    private void fire(SwitchFsm switchFsm, SwitchFsmEvent event, SwitchFsmContext context) {
        SwitchFsm.SwitchFsmFactory.EXECUTOR.fire(switchFsm, event, context);
    }
}
