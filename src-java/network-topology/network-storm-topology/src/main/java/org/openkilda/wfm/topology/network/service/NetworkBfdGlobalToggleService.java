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

import org.openkilda.model.FeatureToggles;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.controller.BfdGlobalToggleFsm;
import org.openkilda.wfm.topology.network.error.BfdGlobalToggleControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class NetworkBfdGlobalToggleService {
    private final IBfdGlobalToggleCarrier carrier;
    private final FeatureTogglesRepository featureTogglesRepository;

    private final BfdGlobalToggleFsm.BfdGlobalToggleFsmFactory controllerFactory;
    private final Map<Endpoint, BfdGlobalToggleFsm> controllerByEndpoint = new HashMap<>();

    private final FsmExecutor<BfdGlobalToggleFsm, BfdGlobalToggleFsm.BfdGlobalToggleFsmState,
            BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent,
            BfdGlobalToggleFsm.BfdGlobalToggleFsmContext> controllerExecutor;

    public NetworkBfdGlobalToggleService(IBfdGlobalToggleCarrier carrier, PersistenceManager persistenceManager) {
        this.carrier = carrier;

        featureTogglesRepository = persistenceManager.getRepositoryFactory().createFeatureTogglesRepository();

        controllerFactory = BfdGlobalToggleFsm.factory(persistenceManager);
        controllerExecutor = controllerFactory.produceExecutor();
    }

    /**
     * Setup global BFD-toggle controller.
     */
    public void create(Endpoint endpoint) {
        log.debug("BFD global toggle service receive setup request for {}", endpoint);
        if (controllerByEndpoint.containsKey(endpoint)) {
            log.debug("Receive BFD-global-toggle SETUP request for {}, but this controller already exists (do not "
                      + "replace existing handler)", endpoint);
            return;
        }

        BfdGlobalToggleFsm fsm = controllerFactory.produce(carrier, endpoint);
        controllerByEndpoint.put(endpoint, fsm);
    }

    /**
     * Remove global BFD-toggle controller.
     */
    public void delete(Endpoint endpoint) {
        BfdGlobalToggleFsm controller = controllerByEndpoint.remove(endpoint);
        if (controller == null) {
            throw new IllegalArgumentException(String.format(
                    "Unable to remove BFD-global-toggle controller for %s - there is no such controller", endpoint));
        }

        BfdGlobalToggleFsm.BfdGlobalToggleFsmContext context = BfdGlobalToggleFsm.BfdGlobalToggleFsmContext.builder()
                .build();
        controllerExecutor.fire(controller, BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.KILL, context);
    }

    public void synchronizeToggle() {
        updateToggle(featureTogglesRepository.getOrDefault());
    }

    /**
     * Consume feature toggles update notification.
     */
    public void updateToggle(FeatureToggles toggles) {
        log.debug("BFD global toggle service receive toggles update notification: {}", toggles);

        Boolean value = toggles.getUseBfdForIslIntegrityCheck();
        if (value == null) {
            // ignore null value
            log.debug("Ignore global BFD toggle update, due missing toggle value (null)");
            return;
        }
        updateToggle(value);
    }

    /**
     * Activate or deactivate BFD feature toggle effect.
     */
    public void updateToggle(boolean isActive) {
        BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent event;
        if (isActive) {
            event = BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.ENABLE;
        } else {
            event = BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.DISABLE;
        }

        BfdGlobalToggleFsm.BfdGlobalToggleFsmContext context = BfdGlobalToggleFsm.BfdGlobalToggleFsmContext.builder()
                .build();
        for (BfdGlobalToggleFsm controller : controllerByEndpoint.values()) {
            controllerExecutor.fire(controller, event, context);
        }
    }

    /**
     * Consume BFD status change notifications.
     */
    public void bfdStateChange(Endpoint physicalEndpoint, LinkStatus status) {
        log.debug("BFD global toggle service receive BFD status change for {} new-status:{}", physicalEndpoint, status);

        BfdGlobalToggleFsm.BfdGlobalToggleFsmContext context = BfdGlobalToggleFsm.BfdGlobalToggleFsmContext.builder()
                .build();
        BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent event;
        switch (status) {
            case UP:
                event = BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.BFD_UP;
                break;
            case DOWN:
                event = BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.BFD_DOWN;
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("Unsupported %s value %s", LinkStatus.class.getName(), status));
        }
        fireEvent(physicalEndpoint, event, context);
    }

    /**
     * Consume BFD-kill notification.
     */
    public void bfdKillNotification(Endpoint physicalEndpoint) {
        log.debug("BFD global toggle service receive BFD-kill notification");
        BfdGlobalToggleFsm.BfdGlobalToggleFsmContext context = BfdGlobalToggleFsm.BfdGlobalToggleFsmContext.builder()
                .build();
        fireEvent(physicalEndpoint, BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.BFD_KILL, context);
    }

    /**
     * Consume BFD-fail notification.
     */
    public void bfdFailNotification(Endpoint physicalEndpoint) {
        log.debug("BFD global toggle service receive BFD-fail notification");
        BfdGlobalToggleFsm.BfdGlobalToggleFsmContext context = BfdGlobalToggleFsm.BfdGlobalToggleFsmContext.builder()
                .build();
        fireEvent(physicalEndpoint, BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent.BFD_FAIL, context);
    }

    private void fireEvent(
            Endpoint endpoint,
            BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent event, BfdGlobalToggleFsm.BfdGlobalToggleFsmContext context) {
        BfdGlobalToggleFsm controller = lookupController(endpoint);
        controllerExecutor.fire(controller, event, context);
    }

    private BfdGlobalToggleFsm lookupController(Endpoint endpoint) {
        BfdGlobalToggleFsm controller = controllerByEndpoint.get(endpoint);
        if (controller == null) {
            throw new BfdGlobalToggleControllerNotFoundException(endpoint);
        }
        return controller;
    }
}
