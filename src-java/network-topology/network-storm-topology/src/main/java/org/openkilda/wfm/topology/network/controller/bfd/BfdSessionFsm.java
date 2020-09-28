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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.SwitchReference;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.BfdSession;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.tx.TransactionCallback;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.State;
import org.openkilda.wfm.topology.network.error.SwitchReferenceLookupException;
import org.openkilda.wfm.topology.network.model.BfdDescriptor;
import org.openkilda.wfm.topology.network.model.BfdSessionData;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;
import org.openkilda.wfm.topology.network.utils.EndpointStatusListener;
import org.openkilda.wfm.topology.network.utils.EndpointStatusMonitor;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusListener;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusMonitor;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.StateMachineLogger;

import java.util.Objects;
import java.util.Optional;
import java.util.Random;

@Slf4j
public final class BfdSessionFsm
        extends AbstractBaseFsm<BfdSessionFsm, State, Event, BfdSessionFsmContext>
        implements BfdSessionManager, SwitchOnlineStatusListener, EndpointStatusListener {
    static final int BFD_UDP_PORT = 3784;

    private final Random random = new Random();

    private final TransactionManager transactionManager;
    private final SwitchRepository switchRepository;
    private final BfdSessionRepository bfdSessionRepository;

    private final IBfdSessionCarrier carrier;

    private final Endpoint logicalEndpoint;
    private final int physicalPortNumber;
    private final BfdSessionData sessionData;

    private BfdProperties properties;
    private Integer discriminator;

    private BfdSessionAction action = null;
    private boolean error = false;
    private boolean online;
    private LinkStatus endpointStatus;

    public static BfdSessionFsmFactory factory(
            PersistenceManager persistenceManager, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
            EndpointStatusMonitor endpointStatusMonitor, IBfdSessionCarrier carrier) {
        return new BfdSessionFsmFactory(persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, carrier);
    }

    public BfdSessionFsm(
            PersistenceManager persistenceManager,
            SwitchOnlineStatusMonitor switchOnlineStatusMonitor, EndpointStatusMonitor endpointStatusMonitor,
            IBfdSessionCarrier carrier, Endpoint logical, Integer physicalPortNumber, BfdSessionData sessionData) {
        transactionManager = persistenceManager.getTransactionManager();

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.bfdSessionRepository = repositoryFactory.createBfdSessionRepository();

        this.carrier = carrier;

        this.logicalEndpoint = logical;
        this.physicalPortNumber = physicalPortNumber;
        this.sessionData = sessionData;

        online = switchOnlineStatusMonitor.subscribe(logical.getDatapath(), this);
        endpointStatus = endpointStatusMonitor.subscribe(logical, this);
    }

    // -- external API --

    @Override
    public boolean isOperationalAndEqualTo(BfdSessionData goal) {
        switch (getCurrentState()) {
            case ACTIVE:
            case WAIT_STATUS:
            case UP:
            case DOWN:
            case OFFLINE:
                BfdSessionData effective = new BfdSessionData(sessionData.getReference(), properties);
                return effective.equals(goal);

            default:
                return false;
        }
    }

    @Override
    public void speakerResponse(String key) {
        speakerResponse(key, null);  // timeout
    }

    @Override
    public void speakerResponse(String key, BfdSessionResponse response) {
        if (action != null) {
            action.consumeSpeakerResponse(key, response)
                    .ifPresent(result -> handleActionResult(result, response));
        }
    }

    @Override
    public boolean disable() {
        if (! isTerminated()) {
            handle(Event.DISABLE);
            return false;
        }
        return true;
    }

    @Override
    public void switchOnlineStatusUpdate(boolean isOnline) {
        online = isOnline;
        if (! isTerminated()) {
            handle(isOnline ? Event.ONLINE : Event.OFFLINE);
        }
    }

    @Override
    public void endpointStatusUpdate(LinkStatus status) {
        endpointStatus = status;
        if (! isTerminated()) {
            handle(mapEndpointStatusToEvent(status));
        }
    }

    public void handle(Event event) {
        handle(event, BfdSessionFsmContext.builder().build());
    }

    public void handle(Event event, BfdSessionFsmContext context) {
        BfdSessionFsmFactory.EXECUTOR.fire(this, event, context);
    }

    @Override
    public boolean isDummy() {
        return false;
    }

    // -- FSM actions --

    public void enterEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        loadExistingSession();
    }

    public void prepareEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        Optional<BfdDescriptor> descriptor = allocateDiscriminator(context);
        if (online && descriptor.isPresent()) {
            action = new BfdSessionCreateAction(carrier, makeBfdSessionRecord(descriptor.get()));
            fire(Event.READY, context);
        }
    }

    public void sendSessionCreateRequestAction(State from, State to, Event event, BfdSessionFsmContext context) {
        Optional<BfdDescriptor> descriptor = makeSessionDescriptor(context);
        if (descriptor.isPresent()) {
            action = new BfdSessionCreateAction(carrier, makeBfdSessionRecord(descriptor.get()));
            fire(Event.READY, context);
        }
    }

    public void creatingEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        endpointStatus = null;
    }

    public void activeEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("BFD session is operational");
        saveEffectiveProperties();
    }

    public void activeExitAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("notify consumer(s) to STOP react on BFD event");
        carrier.bfdKillNotification(getPhysicalEndpoint());
    }

    public void offlineEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        endpointStatus = null;
    }

    public void waitStatusEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        if (endpointStatus != null) {
            fire(mapEndpointStatusToEvent(endpointStatus), context);
        }
    }

    public void upEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("LINK detected");
        carrier.bfdUpNotification(getPhysicalEndpoint());
    }

    public void downEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("LINK corrupted");
        carrier.bfdDownNotification(getPhysicalEndpoint());
    }

    public void removingEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        makeSessionRemoveAction(context);
    }

    public void sessionRemoveAction(State from, State to, Event event, BfdSessionFsmContext context) {
        makeSessionRemoveAction(context);
    }

    public void emitBfdFailAction(State from, State to, Event event, BfdSessionFsmContext context) {
        emitBfdFail();
    }

    public void errorEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        error = true;
        emitBfdFail();
    }

    public void housekeepingEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        releaseDiscriminator();
    }

    public void stopEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        carrier.sessionRotateRequest(logicalEndpoint, error);
    }

    // -- private/service methods --

    private void disableIfConfigured() {
        if (discriminator != null && properties != null && properties.isEnabled()) {
            handle(Event.DISABLE);
        } else {
            discriminator = null;
            properties = null;
        }
    }

    private void loadExistingSession() {
        transactionManager.doInTransaction(() -> loadBfdSession().ifPresent(this::loadExistingSession));
    }

    private void loadExistingSession(BfdSession dbView) {
        discriminator = dbView.getDiscriminator();
        properties = BfdProperties.builder()
                .interval(dbView.getInterval())
                .multiplier(dbView.getMultiplier())
                .build();
    }

    private void makeSessionRemoveAction(BfdSessionFsmContext context) {
        if (online) {
            makeSessionDescriptor(context).ifPresent(
                    descriptor -> action = new BfdSessionRemoveAction(carrier, makeBfdSessionRecord(descriptor)));
        }
    }

    private NoviBfdSession makeBfdSessionRecord(BfdDescriptor descriptor) {
        if (discriminator == null) {
            throw new IllegalStateException(makeLogPrefix() + " there is no allocated discriminator");
        }

        BfdProperties effectiveProperties = properties != null ? properties : sessionData.getProperties();
        return NoviBfdSession.builder()
                .target(descriptor.getLocal())
                .remote(descriptor.getRemote())
                .physicalPortNumber(physicalPortNumber)
                .logicalPortNumber(logicalEndpoint.getPortNumber())
                .udpPortNumber(BFD_UDP_PORT)
                .discriminator(discriminator)
                .keepOverDisconnect(true)
                .intervalMs((int) effectiveProperties.getInterval().toMillis())
                .multiplier(effectiveProperties.getMultiplier())
                .build();
    }

    private Optional<BfdDescriptor> allocateDiscriminator(BfdSessionFsmContext context) {
        Optional<BfdDescriptor> descriptor = makeSessionDescriptor(context);
        descriptor.ifPresent(this::allocateDiscriminator);
        return descriptor;
    }

    private void allocateDiscriminator(BfdDescriptor descriptor) {
        while (true) {
            try {
                discriminator = transactionManager.doInTransaction(
                        () -> allocateDiscriminator(descriptor, loadBfdSession().orElse(null)));
                break;
            } catch (ConstraintViolationException ex) {
                log.warn("ConstraintViolationException on allocate bfd discriminator");
            }
        }
    }

    private Integer allocateDiscriminator(BfdDescriptor descriptor, BfdSession bfdSession) {
        if (bfdSession != null && bfdSession.getDiscriminator() != null) {
            return bfdSession.getDiscriminator();
        }

        // FIXME(surabujin): loop will never end if all possible discriminators are allocated
        Integer attempt = random.nextInt();
        if (bfdSession != null) {
            bfdSession.setDiscriminator(attempt);
            descriptor.fill(bfdSession);
        } else {
            bfdSession = BfdSession.builder()
                    .switchId(logicalEndpoint.getDatapath())
                    .port(logicalEndpoint.getPortNumber())
                    .physicalPort(physicalPortNumber)
                    .discriminator(attempt)
                    .build();
            descriptor.fill(bfdSession);
            bfdSessionRepository.add(bfdSession);
        }

        return attempt;
    }

    private void releaseDiscriminator() {
        if (discriminator == null) {
            return;
        }

        transactionManager.doInTransaction(() -> {
            loadBfdSession().ifPresent(this::releaseDiscriminator);
        });
    }

    private void releaseDiscriminator(BfdSession session) {
        if (Objects.equals(session.getDiscriminator(), discriminator)) {
            bfdSessionRepository.remove(session);
        }
    }

    private void saveEffectiveProperties() {
        properties = sessionData.getProperties();
        transactionManager.doInTransaction(this::saveEffectivePropertiesTransaction);
    }

    private void saveEffectivePropertiesTransaction() {
        Optional<BfdSession> session = loadBfdSession();
        if (session.isPresent()) {
            BfdSession dbView = session.get();
            logInfo("save effective session properties {}", properties);
            dbView.setInterval(properties.getInterval());
            dbView.setMultiplier(properties.getMultiplier());
        } else {
            logError("DB session is missing, unable to save effective properties values");
        }
    }

    private Optional<BfdSession> loadBfdSession() {
        return bfdSessionRepository.findBySwitchIdAndPort(
                logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber());
    }

    private Optional<BfdDescriptor> makeSessionDescriptor(BfdSessionFsmContext context) {
        BfdDescriptor descriptor = null;
        try {
            descriptor = transactionManager.doInTransaction(
                    (TransactionCallback<BfdDescriptor, SwitchReferenceLookupException>) this::makeSessionDescriptor);
        } catch (SwitchReferenceLookupException e) {
            logError(e.getMessage());
            fire(Event.ERROR, context);
        }
        return Optional.ofNullable(descriptor);
    }

    private BfdDescriptor makeSessionDescriptor() throws SwitchReferenceLookupException {
        Endpoint remoteEndpoint = sessionData.getReference().getOpposite(getPhysicalEndpoint());
        return BfdDescriptor.builder()
                .local(makeSwitchReference(logicalEndpoint.getDatapath()))
                .remote(makeSwitchReference(remoteEndpoint.getDatapath()))
                .build();
    }

    private SwitchReference makeSwitchReference(SwitchId datapath) throws SwitchReferenceLookupException {
        Switch sw = switchRepository.findById(datapath)
                .orElseThrow(() -> new SwitchReferenceLookupException(datapath, "persistent record is missing"));
        return new SwitchReference(datapath, sw.getSocketAddress().getAddress());
    }

    private void emitBfdFail() {
        carrier.bfdFailNotification(getPhysicalEndpoint());
    }

    private void handleActionResult(BfdSessionAction.ActionResult result, BfdSessionResponse response) {
        Event event;
        if (result.isSuccess()) {
            event = Event.ACTION_SUCCESS;
        } else {
            event = Event.ACTION_FAIL;
            reportActionFailure(result);
        }

        action = null;
        BfdSessionFsmContext context = BfdSessionFsmContext.builder()
                .speakerResponse(response)
                .build();
        handle(event, context);
    }

    private void reportActionFailure(BfdSessionAction.ActionResult result) {
        String prefix = String.format("%s action have FAILED", action.getLogIdentifier());
        if (result.getErrorCode() == null) {
            logError(String.format("%s due to TIMEOUT on speaker request", prefix));
        } else {
            logError(String.format("%s with error %s", prefix, result.getErrorCode()));
        }
    }

    private Endpoint getPhysicalEndpoint() {
        return Endpoint.of(logicalEndpoint.getDatapath(), physicalPortNumber);
    }

    private Event mapEndpointStatusToEvent(LinkStatus status) {
        Event event;
        switch (status) {
            case UP:
                event = Event.PORT_UP;
                break;
            case DOWN:
                event = Event.PORT_DOWN;
                break;

            default:
                throw new IllegalStateException(String.format(
                        "%s - there is no mapping from %s.%s into %s",
                        makeLogPrefix(), status.getClass().getName(), status, Event.class.getName()));
        }
        return event;
    }

    private void logInfo(String message, Object... args) {
        if (log.isInfoEnabled()) {
            log.info("{} - " + message, makeLogPrefix(), args);
        }
    }

    private void logError(String message, Object... args) {
        if (log.isErrorEnabled()) {
            log.error("{} - " + message, makeLogPrefix(), args);
        }
    }

    private String makeLogPrefix() {
        return String.format("BFD session %s(physical-port:%s)", logicalEndpoint, physicalPortNumber);
    }

    public static class BfdSessionFsmFactory {
        public static final FsmExecutor<BfdSessionFsm, State, Event, BfdSessionFsmContext> EXECUTOR
                = new FsmExecutor<>(Event.NEXT);

        private final PersistenceManager persistenceManager;
        @Getter
        private final SwitchOnlineStatusMonitor switchOnlineStatusMonitor;
        private final EndpointStatusMonitor endpointStatusMonitor;

        @Getter
        private final IBfdSessionCarrier carrier;

        private final StateMachineBuilder<BfdSessionFsm, State, Event, BfdSessionFsmContext> builder;

        BfdSessionFsmFactory(
                PersistenceManager persistenceManager, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
                EndpointStatusMonitor endpointStatusMonitor, IBfdSessionCarrier carrier) {
            this.persistenceManager = persistenceManager;
            this.switchOnlineStatusMonitor = switchOnlineStatusMonitor;
            this.endpointStatusMonitor = endpointStatusMonitor;
            this.carrier = carrier;

            builder = StateMachineBuilderFactory.create(
                    BfdSessionFsm.class, State.class, Event.class, BfdSessionFsmContext.class,
                    // extra parameters
                    PersistenceManager.class, SwitchOnlineStatusMonitor.class, EndpointStatusMonitor.class,
                    IBfdSessionCarrier.class, Endpoint.class, Integer.class, BfdSessionData.class);

            final String sessionRemoveAction = "sessionRemoveAction";
            final String emitBfdFailAction = "emitBfdFailAction";

            // ENTER
            builder.onEntry(State.ENTER)
                    .callMethod("enterEnterAction");
            builder.transition()
                    .from(State.ENTER).to(State.PREPARE).on(Event.ENABLE);
            builder.transition()
                    .from(State.ENTER).to(State.REMOVING).on(Event.DISABLE);

            // PREPARE
            builder.onEntry(State.PREPARE)
                    .callMethod("prepareEnterAction");
            builder.transition()
                    .from(State.PREPARE).to(State.CREATING).on(Event.READY);
            builder.transition()
                    .from(State.PREPARE).to(State.HOUSEKEEPING).on(Event.DISABLE);
            builder.transition()
                    .from(State.PREPARE).to(State.ERROR).on(Event.ERROR);
            builder.internalTransition()
                    .within(State.PREPARE).on(Event.ONLINE)
                    .callMethod("sendSessionCreateRequestAction");

            // CREATING
            builder.onEntry(State.CREATING)
                    .callMethod("creatingEnterAction");
            builder.transition()
                    .from(State.CREATING).to(State.ACTIVE).on(Event.ACTION_SUCCESS);
            builder.transition()
                    .from(State.CREATING).to(State.ERROR).on(Event.ACTION_FAIL);
            builder.transition()
                    .from(State.CREATING).to(State.REMOVING).on(Event.DISABLE);

            // ACTIVE
            builder.onEntry(State.ACTIVE)
                    .callMethod("activeEnterAction");
            builder.transition()
                    .from(State.ACTIVE).to(State.REMOVING).on(Event.DISABLE);
            builder.transition()
                    .from(State.ACTIVE).to(State.OFFLINE).on(Event.OFFLINE);
            builder.onExit(State.ACTIVE)
                    .callMethod("activeExitAction");

            builder.defineSequentialStatesOn(
                    State.ACTIVE,
                    State.WAIT_STATUS, State.UP, State.DOWN);

            // WAIT_STATUS
            builder.onEntry(State.WAIT_STATUS)
                    .callMethod("waitStatusEnterAction");
            builder.transition()
                    .from(State.WAIT_STATUS).to(State.UP).on(Event.PORT_UP);
            builder.transition()
                    .from(State.WAIT_STATUS).to(State.DOWN).on(Event.PORT_DOWN);

            // UP
            builder.transition()
                    .from(State.UP).to(State.DOWN).on(Event.PORT_DOWN);
            builder.onEntry(State.UP)
                    .callMethod("upEnterAction");

            // DOWN
            builder.transition()
                    .from(State.DOWN).to(State.UP).on(Event.PORT_UP);
            builder.onEntry(State.DOWN)
                    .callMethod("downEnterAction");

            // OFFLINE
            builder.onEntry(State.OFFLINE)
                    .callMethod("offlineEnterAction");
            builder.transition()
                    .from(State.OFFLINE).to(State.ACTIVE).on(Event.ONLINE);
            builder.transition()
                    .from(State.OFFLINE).to(State.REMOVING).on(Event.DISABLE);

            // REMOVING
            builder.onEntry(State.REMOVING)
                    .callMethod("removingEnterAction");
            builder.transition()
                    .from(State.REMOVING).to(State.HOUSEKEEPING).on(Event.ACTION_SUCCESS);
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.ACTION_FAIL)
                    .callMethod(emitBfdFailAction);
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.ONLINE)
                    .callMethod(sessionRemoveAction);
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.DISABLE)
                    .callMethod(sessionRemoveAction);

            // ERROR
            builder.onEntry(State.ERROR)
                    .callMethod("errorEnterAction");
            builder.transition()
                    .from(State.ERROR).to(State.HOUSEKEEPING).on(Event.NEXT);

            // HOUSEKEEPING
            builder.onEntry(State.HOUSEKEEPING)
                    .callMethod("housekeepingEnterAction");
            builder.transition()
                    .from(State.HOUSEKEEPING).to(State.STOP).on(Event.NEXT);

            // STOP
            builder.onEntry(State.STOP)
                    .callMethod("stopEnterAction");
            builder.defineFinalState(State.STOP);
        }

        public BfdSessionFsm produce(Endpoint logical, Integer physicalPortNumber, BfdSessionData sessionData) {
            BfdSessionFsm entity = builder.newStateMachine(
                    State.ENTER, persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, carrier,
                    logical, physicalPortNumber, sessionData);
            entity.start(BfdSessionFsmContext.builder().build());

            entity.disableIfConfigured();
            entity.handle(Event.ENABLE);

            // FIXME - DEBUG!
            new StateMachineLogger(entity).startLogging();
            // FIXME - DEBUG!

            return entity;
        }
    }

    @Value
    @Builder
    @ToString
    public static class BfdSessionFsmContext {
        BfdSessionResponse speakerResponse;
    }

    public enum Event {
        NEXT, ERROR, READY,
        ENABLE, DISABLE,

        ACTION_SUCCESS, ACTION_FAIL,

        ONLINE, OFFLINE,
        PORT_UP, PORT_DOWN,
    }

    public enum State {
        ENTER,
        PREPARE,
        CREATING,
        ACTIVE, WAIT_STATUS, UP, DOWN,
        OFFLINE,
        REMOVING,
        ERROR,
        HOUSEKEEPING,
        STOP
    }
}
