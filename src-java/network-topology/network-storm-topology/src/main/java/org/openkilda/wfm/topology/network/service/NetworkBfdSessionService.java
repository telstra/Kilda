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

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.model.BfdProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmFactory;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.error.BfdSessionControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Slf4j
public class NetworkBfdSessionService {
    private final IBfdSessionCarrier carrier;
    private final PersistenceManager persistenceManager;

    private final BfdSessionFsmFactory controllerFactory;
    private final Map<Endpoint, BfdSessionFsm> controllerByPhysicalPort = new HashMap<>();
    private final Map<Endpoint, BfdSessionFsm> controllerByLogicalPort = new HashMap<>();
    private final List<BfdSessionFsm> pendingCleanup = new LinkedList<>();

    public NetworkBfdSessionService(IBfdSessionCarrier carrier, PersistenceManager persistenceManager) {
        this.carrier = carrier;
        this.persistenceManager = persistenceManager;

        controllerFactory = BfdSessionFsm.factory();
    }

    /**
     * Add BFD session.
     */
    public void add(Endpoint endpoint, int physicalPortNumber) {
        log.info("BFD-session service receive ADD request for {} (physical-port:{})",
                  endpoint, physicalPortNumber);
        BfdSessionFsm controller = controllerFactory.produce(persistenceManager, endpoint, physicalPortNumber);
        handle(controller, Event.HISTORY);

        controllerByLogicalPort.put(controller.getLogicalEndpoint(), controller);
        controllerByPhysicalPort.put(controller.getPhysicalEndpoint(), controller);
    }

    /**
     * Deletes BFD session.
     */
    public void delete(Endpoint logicalEndpoint) {
        log.info("BFD-session service receive DELETE request for {} (logical)", logicalEndpoint);

        BfdSessionFsm controller = controllerByLogicalPort.remove(logicalEndpoint);
        if (controller == null) {
            throw BfdSessionControllerNotFoundException.ofLogical(logicalEndpoint);
        }
        controllerByPhysicalPort.remove(controller.getPhysicalEndpoint());

        delete(controller);
    }

    private void delete(BfdSessionFsm controller) {
        handle(controller, Event.KILL);

        if (controller.isDoingCleanup()) {
            log.info("BFD-session {} (physical-port:{}) have switched into housekeeping mode",
                     controller.getLogicalEndpoint(), controller.getPhysicalEndpoint().getPortNumber());
            pendingCleanup.add(controller);
        } else {
            log.debug("BFD-session {} (physical-port:{}) do not require housekeeping, remove it immediately",
                      controller.getLogicalEndpoint(), controller.getPhysicalEndpoint().getPortNumber());
            carrier.sessionCompleteNotification(controller.getPhysicalEndpoint());
        }
    }

    /**
     * .
     */
    public void updateLinkStatus(Endpoint logicalEndpoint, LinkStatus linkStatus) {
        log.debug("BFD-session service receive logical port status update for {} (logical) status:{}",
                  logicalEndpoint, linkStatus);

        BfdSessionFsm controller = lookupControllerByLogicalEndpoint(logicalEndpoint);
        controller.updateLinkStatus(carrier, linkStatus);
    }

    /**
     * Handle change in ONLINE status of switch that own logical-BFD port.
     */
    public void updateOnlineStatus(Endpoint logical, boolean isOnline) {
        Event event = isOnline ? Event.ONLINE : Event.OFFLINE;
        log.debug("BFD-session service receive online mode change notification for {} (logical) mode:{}",
                  logical, event);

        BfdSessionFsm controller = lookupControllerByLogicalEndpoint(logical);
        handle(controller, event);
    }

    /**
     * .
     */
    public void enableUpdate(Endpoint physical, IslReference reference, BfdProperties properties) {
        log.info(
                "BFD-session service receive ENABLE/UPDATE request for {} (physical) with properties {}",
                physical, properties);

        BfdSessionFsm controller = lookupControllerByPhysicalEndpoint(physical);
        BfdSessionFsmContext context = BfdSessionFsmContext.builder(carrier)
                .islReference(reference)
                .properties(properties)
                .build();
        handle(controller, Event.ENABLE_UPDATE, context);
    }

    /**
     * .
     */
    public void disable(Endpoint physicalEndpoint) {
        BfdSessionFsm controller = lookupControllerByPhysicalEndpoint(physicalEndpoint);
        log.info("Remove BFD session request for {} (logical-port:{})",
                 controller.getPhysicalEndpoint(), controller.getLogicalEndpoint().getPortNumber());
        handle(controller, Event.DISABLE);
    }

    /**
     * Handle speaker response.
     */
    public void speakerResponse(String key, Endpoint logicalEndpoint, BfdSessionResponse response) {
        log.debug("BFD-session service receive speaker response for {} (logical) key:{}",
                  logicalEndpoint, key);

        BfdSessionFsmContext context = BfdSessionFsmContext.builder(carrier)
                .requestKey(key)
                .bfdSessionResponse(response)
                .build();
        handleSpeakerResponse(logicalEndpoint, context);
    }

    /**
     * Handle speaker timeout response.
     */
    public void speakerTimeout(String key, Endpoint logicalEndpoint) {
        log.debug("BFD-session service receive speaker timeout response for {} (logical) key:{}", logicalEndpoint, key);

        BfdSessionFsmContext context = BfdSessionFsmContext.builder(carrier)
                .requestKey(key)
                .build();
        handleSpeakerResponse(logicalEndpoint, context);
    }

    private void handleSpeakerResponse(Endpoint logicalEndpoint, BfdSessionFsmContext context) {
        BfdSessionFsm controller = controllerByLogicalPort.get(logicalEndpoint);
        if (controller != null) {
            handle(controller, Event.SPEAKER_RESPONSE, context);
        }

        handleSpeakerResponse(context);
    }

    private void handleSpeakerResponse(BfdSessionFsmContext context) {
        Iterator<BfdSessionFsm> iter;
        for (iter = pendingCleanup.iterator(); iter.hasNext(); ) {
            BfdSessionFsm controller = iter.next();
            handle(controller, Event.SPEAKER_RESPONSE, context);

            if (!controller.isDoingCleanup()) {
                log.info("BFD-session {} (physical-port:{}) have done with housekeeping, remove it",
                         controller.getLogicalEndpoint(), controller.getPhysicalEndpoint().getPortNumber());
                iter.remove();
                carrier.sessionCompleteNotification(controller.getPhysicalEndpoint());
            }
        }
    }

    // -- private --
    private void handle(BfdSessionFsm fsm, Event event) {
        handle(fsm, event, BfdSessionFsmContext.builder(carrier).build());
    }

    private void handle(BfdSessionFsm fsm, Event event, BfdSessionFsmContext context) {
        BfdSessionFsmFactory.EXECUTOR.fire(fsm, event, context);
    }

    private BfdSessionFsm lookupControllerByPhysicalEndpoint(Endpoint endpoint) {
        BfdSessionFsm controller = controllerByPhysicalPort.get(endpoint);
        if (controller == null) {
            throw BfdSessionControllerNotFoundException.ofPhysical(endpoint);
        }
        return controller;
    }

    private BfdSessionFsm lookupControllerByLogicalEndpoint(Endpoint endpoint) {
        BfdSessionFsm controller = controllerByLogicalPort.get(endpoint);
        if (controller == null) {
            throw BfdSessionControllerNotFoundException.ofLogical(endpoint);
        }
        return controller;
    }
}
