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

import static java.lang.String.format;

import org.openkilda.messaging.info.discovery.InstallIslDefaultRulesResult;
import org.openkilda.messaging.info.discovery.RemoveIslDefaultRulesResult;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmState;
import org.openkilda.wfm.topology.network.error.IslControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class NetworkIslService {
    private final IslFsm.IslFsmFactory controllerFactory;
    private final Map<IslReference, IslFsm> controller = new HashMap<>();
    private final FsmExecutor<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> controllerExecutor;

    private final IIslCarrier carrier;
    private final NetworkOptions options;

    public NetworkIslService(IIslCarrier carrier, PersistenceManager persistenceManager, NetworkOptions options) {
        this(carrier, persistenceManager, options, NetworkTopologyDashboardLogger.builder(), Clock.systemUTC());
    }

    @VisibleForTesting
    NetworkIslService(IIslCarrier carrier, PersistenceManager persistenceManager, NetworkOptions options,
                      NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder, Clock clock) {
        this.carrier = carrier;
        this.options = options;

        controllerFactory = IslFsm.factory(clock, persistenceManager, dashboardLoggerBuilder);
        controllerExecutor = controllerFactory.produceExecutor();
    }

    /**
     * Create ISL handler and use "history" data to initialize it's state.
     */
    public void islSetupFromHistory(Endpoint endpoint, IslReference reference, Isl history) {
        log.info("ISL service receive SETUP request from history data for {} (on {})", reference, endpoint);
        if (!controller.containsKey(reference)) {
            ensureControllerIsMissing(reference);
            controller.put(reference, makeIslController(endpoint, reference));
        } else {
            log.error("Receive HISTORY data for already created ISL - ignore history "
                              + "(possible start-up race condition)");
        }
    }

    /**
     * .
     */
    public void islUp(Endpoint endpoint, IslReference reference, IslDataHolder islData) {
        log.debug("ISL service receive DISCOVERY notification for {} (on {})", reference, endpoint);
        IslFsm islFsm = locateControllerCreateIfAbsent(endpoint, reference);
        IslFsmContext context = IslFsmContext.builder(carrier, endpoint)
                .islData(islData)
                .build();
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_UP, context);
    }

    /**
     * .
     */
    public void islDown(Endpoint endpoint, IslReference reference, IslDownReason reason) {
        log.debug("ISL service receive FAIL notification for {} (on {})", reference, endpoint);
        IslFsm islFsm = locateController(reference);
        IslFsmContext context = IslFsmContext.builder(carrier, endpoint)
                .downReason(reason)
                .build();
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_DOWN, context);
    }

    /**
     * .
     */
    public void islMove(Endpoint endpoint, IslReference reference) {
        log.debug("ISL service receive MOVED(FAIL) notification for {} (on {})", reference, endpoint);
        IslFsm islFsm = locateController(reference);
        IslFsmContext context = IslFsmContext.builder(carrier, endpoint).build();
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_MOVE, context);
    }

    /**
     * Handle round trip status notification.
     */
    public void roundTripStatusNotification(IslReference reference, RoundTripStatus status) {
        log.debug("ISL service receive ROUND TRIP STATUS for {} (on {})", reference, status.getEndpoint());
        IslFsm islFsm = this.controller.get(reference);
        if (islFsm == null) {
            log.debug("Ignore ROUND TRIP STATUS for for {} - ISL not found", reference);
            return;
        }

        IslFsmContext context = IslFsmContext.builder(carrier, status.getEndpoint())
                .roundTripStatus(status)
                .build();
        controllerExecutor.fire(islFsm, IslFsmEvent.ROUND_TRIP_STATUS, context);
    }

    /**
     * Handle BFD status events.
     */
    public void bfdStatusUpdate(Endpoint endpoint, IslReference reference, BfdStatusUpdate status) {
        log.debug("ISL service receive BFD status update for {} (on {}) - {}", reference, endpoint, status);
        IslFsm islFsm = locateController(reference);
        IslFsmEvent event;
        switch (status) {
            case UP:
                event = IslFsmEvent.BFD_UP;
                break;
            case DOWN:
                event = IslFsmEvent.BFD_DOWN;
                break;
            case KILL:
                event = IslFsmEvent.BFD_KILL;
                break;
            case FAIL:
                event = IslFsmEvent.BFD_FAIL;
                break;

            default:
                throw new IllegalArgumentException(
                        format("Unsupported %s value %s", status.getClass().getName(), status));
        }
        IslFsmContext context = IslFsmContext.builder(carrier, endpoint).build();
        controllerExecutor.fire(islFsm, event, context);
    }

    /**
     * Process enable/disable BFD requests.
     */
    public void bfdPropertiesUpdate(IslReference reference) {
        log.debug("ISL service receive BFD properties update notification for {}", reference);
        IslFsmContext context = IslFsmContext.builder(carrier, reference.getSource()).build();
        controllerExecutor.fire(locateController(reference), IslFsmEvent.BFD_PROPERTIES_UPDATE, context);
    }

    /**
     * Process installed isl rule notification.
     * @param reference isl reference
     * @param payload response payload
     */
    public void islDefaultRuleInstalled(IslReference reference, InstallIslDefaultRulesResult payload) {
        log.debug("ISL service received isl rule installed for {} (on {})", reference, reference.getSource());
        IslFsm islFsm = locateController(reference);
        IslFsmContext context;
        if (payload.isSuccess()) {
            context = IslFsmContext.builder(carrier, reference.getSource())
                    .installedRulesEndpoint(Endpoint.of(payload.getSrcSwitch(), payload.getSrcPort()))
                    .build();
            controllerExecutor.fire(islFsm, IslFsmEvent.ISL_RULE_INSTALLED, context);
        } else {
            context = IslFsmContext.builder(carrier, reference.getSource())
                    .build();
            controllerExecutor.fire(islFsm, IslFsmEvent.ISL_RULE_TIMEOUT, context);
        }
    }

    /**
     * Process removed isl rule notification.
     * @param reference isl reference
     * @param payload target endpoint
     */
    public void islDefaultRuleDeleted(IslReference reference, RemoveIslDefaultRulesResult payload) {
        log.debug("ISL service received isl rule removed for {} (on {})", reference, reference.getSource());
        IslFsm controller = this.controller.get(reference);
        if (controller == null) {
            log.info("Got clean up resources notification for not existing ISL {}", reference);
            return;
        }
        IslFsmContext context;
        if (payload.isSuccess()) {
            context = IslFsmContext.builder(carrier, reference.getSource())
                    .removedRulesEndpoint(Endpoint.of(payload.getSrcSwitch(), payload.getSrcPort()))
                    .build();
            controllerExecutor.fire(controller, IslFsmEvent.ISL_RULE_REMOVED, context);
        } else {
            context = IslFsmContext.builder(carrier, reference.getSource()).build();
            controllerExecutor.fire(controller, IslFsmEvent.ISL_RULE_TIMEOUT, context);
        }

        removeIfCompleted(reference, controller);
    }

    /**
     * Process isl rule timeout notification.
     * @param reference isl reference
     * @param endpoint target endpoint
     */
    public void islDefaultTimeout(IslReference reference, Endpoint endpoint) {
        log.debug("ISL service received isl rule timeout notification for {} (on {})",
                reference, reference.getSource());
        IslFsm controller = locateController(reference);
        IslFsmContext context = IslFsmContext.builder(carrier, reference.getSource()).build();
        controllerExecutor.fire(controller, IslFsmEvent.ISL_RULE_TIMEOUT, context);
        removeIfCompleted(reference, controller);
    }

    /**
     * Remove isl by request.
     */
    public void remove(IslReference reference) {
        log.debug("ISL service receive remove for {}", reference);

        IslFsm islFsm = controller.get(reference);
        if (islFsm == null) {
            log.info("Got DELETE request for not existing ISL {}", reference);
            return;
        }

        IslFsmContext context = IslFsmContext.builder(carrier, reference.getSource())
                .build();
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_REMOVE, context);
        removeIfCompleted(reference, islFsm);
    }

    // -- private --

    private void ensureControllerIsMissing(IslReference reference) {
        IslFsm islFsm = controller.get(reference);
        if (islFsm != null) {
            throw new IllegalStateException(String.format("ISL FSM for %s already exist (it's state is %s)",
                                                          reference, islFsm.getCurrentState()));
        }
    }

    private IslFsm locateController(IslReference reference) {
        IslFsm islController = controller.get(reference);
        if (islController == null) {
            throw new IslControllerNotFoundException(reference);
        }
        return islController;
    }

    private IslFsm locateControllerCreateIfAbsent(Endpoint endpoint, IslReference reference) {
        return controller.computeIfAbsent(reference, key -> makeIslController(endpoint, reference));
    }

    private IslFsm makeIslController(Endpoint endpoint, IslReference reference) {
        IslFsmContext context = IslFsmContext.builder(carrier, endpoint).build();
        return controllerFactory.produce(options, reference, context);
    }

    private void removeIfCompleted(IslReference reference, IslFsm controller) {
        if (controller.isTerminated()) {
            this.controller.remove(reference);
            log.info("ISL {} have been removed", reference);
        }
    }
}
