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

import org.openkilda.messaging.MessageData;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.controller.bfd.BfdLogicalPortFsm.BfdLogicalPortFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdLogicalPortFsm.Event;
import org.openkilda.wfm.topology.network.controller.bfd.BfdLogicalPortFsm.State;
import org.openkilda.wfm.topology.network.model.BfdSessionData;
import org.openkilda.wfm.topology.network.service.IBfdLogicalPortCarrier;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusListener;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusMonitor;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.StateMachineLogger;

import java.util.HashSet;
import java.util.Set;

@Slf4j
public class BfdLogicalPortFsm extends AbstractBaseFsm<BfdLogicalPortFsm, State, Event, BfdLogicalPortFsmContext>
        implements SwitchOnlineStatusListener {
    private final IBfdLogicalPortCarrier carrier;

    private final Set<String> activeRequest = new HashSet<>();

    @Getter
    private final Endpoint physicalEndpoint;

    private final int logicalPortNumber;

    private BfdSessionData sessionData;
    private boolean online;

    public BfdLogicalPortFsm(
            IBfdLogicalPortCarrier carrier, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
            Endpoint physicalEndpoint, Integer logicalPortNumber) {
        this.carrier = carrier;
        this.physicalEndpoint = physicalEndpoint;
        this.logicalPortNumber = logicalPortNumber;

        online = switchOnlineStatusMonitor.subscribe(physicalEndpoint.getDatapath(), this);
        carrier.logicalPortControllerAddNotification(physicalEndpoint);
    }

    // -- external API --

    public static BfdLogicalPortFsmFactory factory(IBfdLogicalPortCarrier carrier) {
        return new BfdLogicalPortFsmFactory(carrier);
    }

    public void processWorkerSuccess(String requestId, InfoData response) {
        if (activeRequest.remove(requestId)) {
            logInfo("receive worker success response: {}", response);
            BfdLogicalPortFsmContext context = BfdLogicalPortFsmContext.builder()
                    .workerResponse(response)
                    .build();
            BfdLogicalPortFsmFactory.EXECUTOR.fire(this, Event.WORKER_SUCCESS, context);
        } else {
            reportWorkerResponseIgnored(requestId, response);
        }
    }

    public void processWorkerError(String requestId, ErrorData response) {
        if (activeRequest.remove(requestId)) {
            String errorMessage = response == null ? "timeout" : response.getErrorMessage();
            logError("receive worker error response: {}", errorMessage);
            BfdLogicalPortFsmContext context = BfdLogicalPortFsmContext.builder()
                    .workerError(response)
                    .build();
            BfdLogicalPortFsmFactory.EXECUTOR.fire(this, Event.WORKER_ERROR, context);
        } else {
            reportWorkerResponseIgnored(requestId, response);
        }
    }

    @Override
    public void switchOnlineStatusUpdate(boolean isOnline) {
        online = isOnline;

        BfdLogicalPortFsmContext context = BfdLogicalPortFsmContext.builder().build();
        if (! isTerminated()) {
            BfdLogicalPortFsmFactory.EXECUTOR.fire(this, isOnline ? Event.ONLINE : Event.OFFLINE, context);
        }
    }

    public Endpoint getLogicalEndpoint() {
        return Endpoint.of(physicalEndpoint.getDatapath(), logicalPortNumber);
    }

    // -- FSM actions --

    public void prepareEnterAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        activeRequest.clear();
        saveSessionData(context);
    }

    public void readyEnterAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sessionData = null;
    }

    public void creatingEnterAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendPortCreateRequest();
    }

    public void creatingExitAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        activeRequest.clear();
    }

    public void operationalEnterAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        if (sessionData != null) {
            sendSessionEnableUpdateRequest();
        }
    }

    public void removingEnterAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendPortDeleteRequest();
    }

    public void stopEnterAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        carrier.logicalPortControllerDelNotification(physicalEndpoint);
    }

    public void sendPortCreateAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendPortCreateRequest();
    }

    public void renewPortCreateAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sessionData = context.getSessionData();
        sendPortCreateRequest();
    }

    public void reportWorkerSuccessAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        logInfo("success worker response {}", context.getWorkerResponse());
    }

    public void sendPortDeleteAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendPortDeleteRequest();
    }

    public void sendSessionEnableUpdateAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendSessionEnableUpdateRequest(context.getSessionData());
    }

    public void sendSessionDisableAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendSessionDisableRequest();
    }

    public void sendSessionFailureAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        sendSessionFailNotification();
    }

    public void saveSessionDataAction(State from, State to, Event event, BfdLogicalPortFsmContext context) {
        saveSessionData(context);
    }

    // -- private/service methods --

    private void saveSessionData(BfdLogicalPortFsmContext context) {
        sessionData = context.getSessionData();
    }

    private void sendPortCreateRequest() {
        Endpoint logical = getLogicalEndpoint();
        if (online) {
            activeRequest.add(carrier.createLogicalPort(logical, physicalEndpoint.getPortNumber()));
        } else {
            log.debug("Do not send logical port {} create request because the switch is offline now", logical);
        }
    }

    private void sendPortDeleteRequest() {
        Endpoint logical = getLogicalEndpoint();
        if (online) {
            activeRequest.add(carrier.deleteLogicalPort(logical));
        } else {
            log.debug("Do not send logical port {} delete request because the switch is offline now", logical);
        }
    }

    private void sendSessionFailNotification() {
        carrier.bfdKillNotification(physicalEndpoint);
    }

    private void sendSessionEnableUpdateRequest() {
        sendSessionEnableUpdateRequest(sessionData);
    }

    private void sendSessionEnableUpdateRequest(BfdSessionData data) {
        carrier.enableUpdateSession(getLogicalEndpoint(), physicalEndpoint.getPortNumber(), data);
    }

    private void sendSessionDisableRequest() {
        carrier.disableSession(getLogicalEndpoint());
    }

    private void reportWorkerResponseIgnored(String requestId, MessageData response) {
        logDebug("ignore response {} with id \"{}\" in state {}, active requests: {}",
                response, requestId, getCurrentState(), activeRequest);
    }

    private void logDebug(String format, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(makeLogPrefix() + " - " + format, args);
        }
    }

    private void logInfo(String format, Object... args) {
        if (log.isInfoEnabled()) {
            log.info(makeLogPrefix() + " - " + format, args);
        }
    }

    private void logError(String format, Object... args) {
        if (log.isErrorEnabled()) {
            log.error(makeLogPrefix() + " - " + format, args);
        }
    }

    private String makeLogPrefix() {
        return String.format("BFD logical port %s", getLogicalEndpoint());
    }

    public static class BfdLogicalPortFsmFactory {
        public static final FsmExecutor<BfdLogicalPortFsm, State, Event, BfdLogicalPortFsmContext> EXECUTOR
                = new FsmExecutor<>(Event.NEXT);

        private final IBfdLogicalPortCarrier carrier;

        private final StateMachineBuilder<BfdLogicalPortFsm, State, Event, BfdLogicalPortFsmContext> builder;

        BfdLogicalPortFsmFactory(IBfdLogicalPortCarrier carrier) {
            this.carrier = carrier;

            builder = StateMachineBuilderFactory.create(
                    BfdLogicalPortFsm.class, State.class, Event.class, BfdLogicalPortFsmContext.class,
                    // extra parameters
                    IBfdLogicalPortCarrier.class, SwitchOnlineStatusMonitor.class, Endpoint.class, Integer.class);

            final String sendSessionDisableAction = "sendSessionDisableAction";
            final String sendSessionEnableUpdateAction = "sendSessionEnableUpdateAction";
            final String sendPortCreateAction = "sendPortCreateAction";
            final String saveSessionDataAction = "saveSessionDataAction";
            final String sendPortDeleteAction = "sendPortDeleteAction";

            // ENTER
            builder.transition()
                    .from(State.ENTER).to(State.PREPARE).on(Event.ENABLE_UPDATE);
            builder.transition()
                    .from(State.ENTER).to(State.READY).on(Event.PORT_ADD);

            // PREPARE
            builder.transition()
                    .from(State.PREPARE).to(State.CREATING).on(Event.NEXT);
            builder.onEntry(State.PREPARE)
                    .callMethod("prepareEnterAction");

            // READY
            builder.transition()
                    .from(State.READY).to(State.OPERATIONAL).on(Event.ENABLE_UPDATE)
                    .callMethod(saveSessionDataAction);
            builder.transition()
                    .from(State.READY).to(State.REMOVING).on(Event.DISABLE);
            builder.transition()
                    .from(State.READY).to(State.STOP).on(Event.PORT_DEL);
            builder.onEntry(State.READY)
                    .callMethod("readyEnterAction");

            // CREATING
            builder.transition()
                    .from(State.CREATING).to(State.OPERATIONAL).on(Event.PORT_ADD);
            builder.transition()
                    .from(State.CREATING).to(State.REMOVING).on(Event.DISABLE);
            builder.onEntry(State.CREATING)
                    .callMethod("creatingEnterAction");
            builder.internalTransition()
                    .within(State.CREATING).on(Event.ONLINE)
                    .callMethod(sendPortCreateAction);
            builder.internalTransition()
                    .within(State.CREATING).on(Event.PORT_DEL)
                    .callMethod(sendPortCreateAction);
            builder.internalTransition()
                    .within(State.CREATING).on(Event.ENABLE_UPDATE)
                    .callMethod("renewPortCreateAction");
            builder.internalTransition()
                    .within(State.CREATING).on(Event.WORKER_SUCCESS)
                    .callMethod("workerSuccessAction");
            builder.internalTransition()
                    .within(State.CREATING).on(Event.WORKER_ERROR)
                    .callMethod("sendSessionFailureAction");
            builder.onExit(State.CREATING)
                    .callMethod("creatingExitAction");

            // OPERATIONAL
            builder.transition()
                    .from(State.OPERATIONAL).to(State.REMOVING).on(Event.SESSION_COMPLETED);
            builder.transition()
                    .from(State.OPERATIONAL).to(State.HOUSEKEEPING).on(Event.PORT_DEL);
            builder.onEntry(State.OPERATIONAL)
                    .callMethod("operationalEnterAction");
            builder.internalTransition()
                    .within(State.OPERATIONAL).on(Event.ENABLE_UPDATE)
                    .callMethod(sendSessionEnableUpdateAction);
            builder.internalTransition()
                    .within(State.OPERATIONAL).on(Event.DISABLE)
                    .callMethod(sendSessionDisableAction);

            // REMOVING
            builder.transition()
                    .from(State.REMOVING).to(State.PREPARE).on(Event.ENABLE_UPDATE);
            builder.transition()
                    .from(State.REMOVING).to(State.STOP).on(Event.PORT_DEL);
            builder.onEntry(State.REMOVING)
                    .callMethod("removingEnterAction");
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.ONLINE)
                    .callMethod(sendPortDeleteAction);
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.PORT_ADD)
                    .callMethod(sendPortDeleteAction);

            // HOUSEKEEPING
            builder.transition()
                    .from(State.HOUSEKEEPING).to(State.OPERATIONAL).on(Event.PORT_ADD);
            builder.transition()
                    .from(State.HOUSEKEEPING).to(State.STOP).on(Event.SESSION_COMPLETED);
            builder.transition()
                    .from(State.HOUSEKEEPING).to(State.PREPARE).on(Event.ENABLE_UPDATE);
            builder.transition()
                    .from(State.HOUSEKEEPING).to(State.DEBRIS).on(Event.DISABLE);
            builder.onEntry(State.HOUSEKEEPING)
                    .callMethod(sendSessionDisableAction);

            // DEBRIS
            builder.transition()
                    .from(State.DEBRIS).to(State.STOP).on(Event.SESSION_COMPLETED);
            builder.transition()
                    .from(State.DEBRIS).to(State.REMOVING).on(Event.PORT_ADD);
            builder.transition()
                    .from(State.DEBRIS).to(State.PREPARE).on(Event.ENABLE_UPDATE);

            // STOP
            builder.defineFinalState(State.STOP);
            builder.onEntry(State.STOP)
                    .callMethod("stopEnterAction");
        }

        public BfdLogicalPortFsm produce(
                SwitchOnlineStatusMonitor switchOnlineStatusMonitor, Endpoint physicalEndpoint, int logicalPortNumber) {
            BfdLogicalPortFsm fsm = builder.newStateMachine(
                    State.ENTER, carrier, switchOnlineStatusMonitor, physicalEndpoint, logicalPortNumber);
            fsm.start(BfdLogicalPortFsmContext.builder().build());

            // FIXME - DEBUG!
            new StateMachineLogger(fsm).startLogging();
            // FIXME - DEBUG!

            return fsm;
        }
    }

    @Value
    @Builder
    public static class BfdLogicalPortFsmContext {
        public static BfdLogicalPortFsmContext EMPTY = BfdLogicalPortFsmContext.builder().build();

        BfdSessionData sessionData;

        InfoData workerResponse;
        ErrorData workerError;
    }

    public enum State {
        ENTER,
        PREPARE,
        READY,
        CREATING,
        OPERATIONAL,
        REMOVING,
        HOUSEKEEPING,
        DEBRIS,
        STOP
    }

    public enum Event {
        NEXT,
        ENABLE_UPDATE, DISABLE,
        PORT_ADD, PORT_DEL,
        ONLINE, OFFLINE,
        WORKER_SUCCESS, WORKER_ERROR,
        SESSION_COMPLETED
    }
}
