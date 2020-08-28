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
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.controller.bfd.BfdPortFsm.BfdPortFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdPortFsm.BfdPortFsmEvent;
import org.openkilda.wfm.topology.network.controller.bfd.BfdPortFsm.BfdPortFsmState;
import org.openkilda.wfm.topology.network.error.SwitchReferenceLookupException;
import org.openkilda.wfm.topology.network.model.BfdDescriptor;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.service.IBfdPortCarrier;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Random;

@Slf4j
public final class BfdPortFsm extends
        AbstractBaseFsm<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> {
    static final int BFD_UDP_PORT = 3784;

    private final SwitchRepository switchRepository;
    private final BfdSessionRepository bfdSessionRepository;

    private final Random random = new Random();

    @Getter
    private final Endpoint physicalEndpoint;
    @Getter
    private final Endpoint logicalEndpoint;

    private final PortStatusMonitor portStatusMonitor;

    private IslReference islReference;
    private BfdProperties properties;
    private BfdProperties effectiveProperties;
    private BfdDescriptor sessionDescriptor = null;
    private BfdAction action = null;

    public static BfdPortFsmFactory factory() {
        return new BfdPortFsmFactory();
    }

    public BfdPortFsm(PersistenceManager persistenceManager, Endpoint endpoint, Integer physicalPortNumber) {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.bfdSessionRepository = repositoryFactory.createBfdSessionRepository();

        this.logicalEndpoint = endpoint;
        this.physicalEndpoint = Endpoint.of(logicalEndpoint.getDatapath(), physicalPortNumber);

        portStatusMonitor = new PortStatusMonitor(this);
    }

    // -- external API --

    public void updateLinkStatus(IBfdPortCarrier carrier, LinkStatus status) {
        portStatusMonitor.update(carrier, status);
    }

    // FIXME(surabujin): extremely unreliable
    public boolean isDoingCleanup() {
        return BfdPortFsmState.DO_CLEANUP == getCurrentState();
    }

    // -- FSM actions --

    public void consumeHistory(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                               BfdPortFsmContext context) {
        Optional<BfdSession> port = loadBfdSession();
        if (port.isPresent()) {
            BfdSession dbView = port.get();
            try {
                sessionDescriptor = BfdDescriptor.builder()
                        .local(makeSwitchReference(dbView.getSwitchId(), dbView.getIpAddress()))
                        .remote(makeSwitchReference(dbView.getRemoteSwitchId(), dbView.getRemoteIpAddress()))
                        .discriminator(dbView.getDiscriminator())
                        .build();
                properties = effectiveProperties = BfdProperties.builder()
                        .interval(dbView.getInterval())
                        .multiplier(dbView.getMultiplier())
                        .build();
            } catch (SwitchReferenceLookupException e) {
                log.error("{} - unable to use stored BFD session data {} - {}",
                        makeLogPrefix(), dbView, e.getMessage());
            }
        }
    }

    public void handleInitChoice(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        if (sessionDescriptor == null) {
            fire(BfdPortFsmEvent._INIT_CHOICE_CLEAN, context);
        } else {
            fire(BfdPortFsmEvent._INIT_CHOICE_DIRTY, context);
        }
    }

    public void idleEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("ready for setup requests");
    }

    public void doSetupEnter(
            BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        portStatusMonitor.cleanTransitions();

        logInfo(String.format("BFD session setup process have started - discriminator:%s, remote-datapath:%s",
                sessionDescriptor.getDiscriminator(), sessionDescriptor.getRemote().getDatapath()));
        action = new BfdSessionSetupAction(context.getOutput(), makeBfdSessionRecord(properties));
    }

    public void saveIslReference(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        islReference = context.getIslReference();
        properties = context.getProperties();
    }

    public void savePropertiesAction(
            BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        properties = context.getProperties();
    }

    public void doAllocateResources(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                    BfdPortFsmContext context) {
        try {
            sessionDescriptor = allocateDiscriminator(makeSessionDescriptor(islReference));
        } catch (SwitchReferenceLookupException e) {
            logError(String.format("Can't allocate BFD-session resources - %s", e.getMessage()));
            fire(BfdPortFsmEvent.FAIL, context);
        }
    }

    public void doReleaseResources(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                   BfdPortFsmContext context) {
        bfdSessionRepository.findBySwitchIdAndPort(logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber())
                .ifPresent(value -> {
                    if (value.getDiscriminator().equals(sessionDescriptor.getDiscriminator())) {
                        bfdSessionRepository.delete(value);
                    }
                });
        sessionDescriptor = null;
    }

    public void activeEnter(
            BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("BFD session is operational");

        effectiveProperties = properties;

        Optional<BfdSession> session = loadBfdSession();
        if (session.isPresent()) {
            BfdSession dbView = session.get();
            dbView.setInterval(properties.getInterval());
            dbView.setMultiplier(properties.getMultiplier());
            bfdSessionRepository.createOrUpdate(dbView);
        } else {
            logError("DB session is missing, unable to save effective properties values");
        }
    }

    public void activeExit(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("notify consumer(s) to STOP react on BFD event");
        portStatusMonitor.cleanTransitions();
        context.getOutput().bfdKillNotification(physicalEndpoint);
    }

    public void waitStatusEnter(
            BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        portStatusMonitor.pull(context.getOutput());
    }

    public void upEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("LINK detected");
        context.getOutput().bfdUpNotification(physicalEndpoint);
    }

    public void downEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("LINK corrupted");
        context.getOutput().bfdDownNotification(physicalEndpoint);
    }

    public void setupFailEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                               BfdPortFsmContext context) {
        logError("BFD-setup action have failed");
        context.getOutput().bfdFailNotification(physicalEndpoint);
    }

    public void removeFailEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                BfdPortFsmContext context) {
        logError("BFD-remove action have failed");
        context.getOutput().bfdFailNotification(physicalEndpoint);
    }

    public void chargedFailEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        logError("BFD-remove action have failed (for re-setup)");
    }

    public void makeBfdRemoveAction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                    BfdPortFsmContext context) {
        logInfo(String.format("perform BFD session remove - discriminator:%s, remote-datapath:%s",
                sessionDescriptor.getDiscriminator(), sessionDescriptor.getRemote().getDatapath()));
        BfdProperties bfdProperties = this.effectiveProperties;
        if (bfdProperties == null) {
            bfdProperties = properties;
        }
        action = new BfdSessionRemoveAction(context.getOutput(), makeBfdSessionRecord(bfdProperties));
    }

    public void proxySpeakerResponseIntoAction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                               BfdPortFsmContext context) {
        action.consumeSpeakerResponse(context.getRequestKey(), context.getBfdSessionResponse())
                .ifPresent(result -> handleActionResult(result, context));
    }

    public void reportSetupSuccess(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                   BfdPortFsmContext context) {
        logInfo("BFD session setup is successfully completed");
    }

    public void reportMalfunctionAction(
            BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logError(String.format("is in %s state - ignore %s request", getCurrentState(), event));
    }

    // -- private/service methods --
    private NoviBfdSession makeBfdSessionRecord(BfdProperties bfdProperties) {
        if (bfdProperties == null) {
            throw new IllegalArgumentException(String.format(
                    "Can't produce %s without properties (properties is null)", NoviBfdSession.class.getSimpleName()));
        }
        return NoviBfdSession.builder()
                .target(sessionDescriptor.getLocal())
                .remote(sessionDescriptor.getRemote())
                .physicalPortNumber(physicalEndpoint.getPortNumber())
                .logicalPortNumber(logicalEndpoint.getPortNumber())
                .udpPortNumber(BFD_UDP_PORT)
                .discriminator(sessionDescriptor.getDiscriminator())
                .keepOverDisconnect(true)
                .intervalMs((int) bfdProperties.getInterval().toMillis())
                .multiplier(bfdProperties.getMultiplier())
                .build();
    }

    private BfdDescriptor allocateDiscriminator(BfdDescriptor descriptor) {
        BfdSession dbView = loadBfdSession()
                .orElseGet(() -> new BfdSession(
                        logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber(),
                        physicalEndpoint.getPortNumber(), properties));

        Integer discriminator = dbView.getDiscriminator();
        descriptor.fill(dbView);

        if (discriminator == null) {
            while (true) {
                // FIXME(surabujin): loop will never end if all possible discriminators are allocated
                discriminator = random.nextInt();
                try {
                    dbView.setDiscriminator(discriminator);
                    bfdSessionRepository.createOrUpdate(dbView);
                    break;
                } catch (ConstraintViolationException ex) {
                    log.warn("ConstraintViolationException on allocate bfd discriminator");
                }
            }
        } else {
            bfdSessionRepository.createOrUpdate(dbView);
        }

        return descriptor.toBuilder()
                .discriminator(discriminator)
                .build();
    }

    private Optional<BfdSession> loadBfdSession() {
        return bfdSessionRepository.findBySwitchIdAndPort(
                logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber());
    }

    private BfdDescriptor makeSessionDescriptor(IslReference islReference) throws SwitchReferenceLookupException {
        Endpoint remoteEndpoint = islReference.getOpposite(getPhysicalEndpoint());
        return BfdDescriptor.builder()
                .local(makeSwitchReference(physicalEndpoint.getDatapath()))
                .remote(makeSwitchReference(remoteEndpoint.getDatapath()))
                .build();
    }

    private SwitchReference makeSwitchReference(SwitchId datapath) throws SwitchReferenceLookupException {
        Switch sw = switchRepository.findById(datapath)
                .orElseThrow(() -> new SwitchReferenceLookupException(datapath, "persistent record is missing"));
        return new SwitchReference(datapath, sw.getSocketAddress().getAddress());
    }

    private SwitchReference makeSwitchReference(SwitchId datapath, String ipAddress)
            throws SwitchReferenceLookupException {
        InetAddress address;
        try {
            address = InetAddress.getByName(ipAddress);
        } catch (UnknownHostException e) {
            throw new SwitchReferenceLookupException(
                    datapath,
                    String.format("unable to parse switch address \"%s\"", ipAddress));
        }

        return new SwitchReference(datapath, address);
    }

    private void handleActionResult(BfdAction.ActionResult result, BfdPortFsmContext context) {
        BfdPortFsmEvent event;
        if (result.isSuccess()) {
            event = BfdPortFsmEvent.ACTION_SUCCESS;
        } else {
            event = BfdPortFsmEvent.ACTION_FAIL;
            reportActionFailure(result);
        }
        fire(event, context);
    }

    private void reportActionFailure(BfdAction.ActionResult result) {
        String prefix = String.format("%s action have FAILED", action.getLogIdentifier());
        if (result.getErrorCode() == null) {
            logError(String.format("%s due to TIMEOUT on speaker request", prefix));
        } else {
            logError(String.format("%s with error %s", prefix, result.getErrorCode()));
        }
    }

    private void logInfo(String message) {
        if (log.isInfoEnabled()) {
            log.info("{} - {}", makeLogPrefix(), message);
        }
    }

    private void logError(String message) {
        if (log.isErrorEnabled()) {
            log.error("{} - {}", makeLogPrefix(), message);
        }
    }

    private String makeLogPrefix() {
        return String.format("BFD port %s(physical-port:%s)", logicalEndpoint, physicalEndpoint.getPortNumber());
    }

    public static class BfdPortFsmFactory {
        public static final FsmExecutor<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> EXECUTOR
                = new FsmExecutor<>(BfdPortFsmEvent.NEXT);

        private final StateMachineBuilder<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> builder;

        BfdPortFsmFactory() {
            final String doReleaseResourcesMethod = "doReleaseResources";
            final String saveIslReferenceMethod = "saveIslReference";
            final String savePropertiesMethod = "savePropertiesAction";
            final String reportMalfunctionMethod = "reportMalfunctionAction";
            final String makeBfdRemoveActionMethod = "makeBfdRemoveAction";
            final String proxySpeakerResponseIntoActionMethod = "proxySpeakerResponseIntoAction";

            builder = StateMachineBuilderFactory.create(
                    BfdPortFsm.class, BfdPortFsmState.class, BfdPortFsmEvent.class, BfdPortFsmContext.class,
                    // extra parameters
                    PersistenceManager.class, Endpoint.class, Integer.class);

            // INIT
            builder.transition()
                    .from(BfdPortFsmState.INIT).to(BfdPortFsmState.INIT_CHOICE).on(BfdPortFsmEvent.HISTORY)
                    .callMethod("consumeHistory");

            // INIT_CHOICE
            builder.transition()
                    .from(BfdPortFsmState.INIT_CHOICE).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent._INIT_CHOICE_CLEAN);
            builder.transition()
                    .from(BfdPortFsmState.INIT_CHOICE).to(BfdPortFsmState.INIT_REMOVE)
                    .on(BfdPortFsmEvent._INIT_CHOICE_DIRTY);
            builder.onEntry(BfdPortFsmState.INIT_CHOICE)
                    .callMethod("handleInitChoice");

            // IDLE
            builder.transition()
                    .from(BfdPortFsmState.IDLE).to(BfdPortFsmState.INIT_SETUP).on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);
            builder.transition()
                    .from(BfdPortFsmState.IDLE).to(BfdPortFsmState.UNOPERATIONAL).on(BfdPortFsmEvent.OFFLINE);
            builder.onEntry(BfdPortFsmState.IDLE)
                    .callMethod("idleEnter");

            // UNOPERATIONAL
            builder.transition()
                    .from(BfdPortFsmState.UNOPERATIONAL).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.ONLINE);
            builder.transition()
                    .from(BfdPortFsmState.UNOPERATIONAL).to(BfdPortFsmState.PENDING).on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(savePropertiesMethod);

            // PENDING
            builder.transition()
                    .from(BfdPortFsmState.PENDING).to(BfdPortFsmState.UNOPERATIONAL).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.PENDING).to(BfdPortFsmState.INIT_SETUP).on(BfdPortFsmEvent.ONLINE);
            builder.onEntry(BfdPortFsmState.PENDING)
                    .callMethod(saveIslReferenceMethod);

            // INIT_SETUP
            builder.transition()
                    .from(BfdPortFsmState.INIT_SETUP).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.FAIL);
            builder.transition()
                    .from(BfdPortFsmState.INIT_SETUP).to(BfdPortFsmState.DO_SETUP).on(BfdPortFsmEvent.NEXT);
            builder.onEntry(BfdPortFsmState.INIT_SETUP)
                    .callMethod("doAllocateResources");

            // DO_SETUP
            builder.transition()
                    .from(BfdPortFsmState.DO_SETUP).to(BfdPortFsmState.ACTIVE).on(BfdPortFsmEvent.ACTION_SUCCESS)
                    .callMethod("reportSetupSuccess");
            builder.transition()
                    .from(BfdPortFsmState.DO_SETUP).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.DO_SETUP).to(BfdPortFsmState.SETUP_FAIL).on(BfdPortFsmEvent.ACTION_FAIL);
            builder.transition()
                    .from(BfdPortFsmState.DO_SETUP).to(BfdPortFsmState.SETUP_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.DO_SETUP).to(BfdPortFsmState.INIT_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.internalTransition().within(BfdPortFsmState.DO_SETUP).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.onEntry(BfdPortFsmState.DO_SETUP)
                    .callMethod("doSetupEnter");

            // SETUP_FAIL
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.SETUP_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.INIT_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.RESET).on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(savePropertiesMethod);
            builder.onEntry(BfdPortFsmState.SETUP_FAIL)
                    .callMethod("setupFailEnter");

            // SETUP_INTERRUPT
            builder.transition()
                    .from(BfdPortFsmState.SETUP_INTERRUPT).to(BfdPortFsmState.RESET)
                    .on(BfdPortFsmEvent.ONLINE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_INTERRUPT).to(BfdPortFsmState.REMOVE_INTERRUPT)
                    .on(BfdPortFsmEvent.DISABLE);

            // RESET
            builder.transition()
                    .from(BfdPortFsmState.RESET).to(BfdPortFsmState.DO_SETUP)
                    .on(BfdPortFsmEvent.ACTION_SUCCESS);
            builder.transition()
                    .from(BfdPortFsmState.RESET).to(BfdPortFsmState.SETUP_INTERRUPT)
                    .on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.RESET).to(BfdPortFsmState.SETUP_FAIL)
                    .on(BfdPortFsmEvent.ACTION_FAIL);
            builder.transition()
                    .from(BfdPortFsmState.RESET).to(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.RESET).to(BfdPortFsmState.DO_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.internalTransition()
                    .within(BfdPortFsmState.RESET).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.onEntry(BfdPortFsmState.RESET)
                    .callMethod(makeBfdRemoveActionMethod);

            // ACTIVE
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.OFFLINE).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.INIT_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.RESET).on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(savePropertiesMethod);
            builder.onEntry(BfdPortFsmState.ACTIVE)
                    .callMethod("activeEnter");
            builder.onExit(BfdPortFsmState.ACTIVE)
                    .callMethod("activeExit");
            builder.defineSequentialStatesOn(
                    BfdPortFsmState.ACTIVE,
                    BfdPortFsmState.WAIT_STATUS, BfdPortFsmState.UP, BfdPortFsmState.DOWN);

            // WAIT_STATUS
            builder.transition()
                    .from(BfdPortFsmState.WAIT_STATUS).to(BfdPortFsmState.UP).on(BfdPortFsmEvent.PORT_UP);
            builder.transition()
                    .from(BfdPortFsmState.WAIT_STATUS).to(BfdPortFsmState.DOWN).on(BfdPortFsmEvent.PORT_DOWN);
            builder.onEntry(BfdPortFsmState.WAIT_STATUS)
                    .callMethod("waitStatusEnter");

            // UP
            builder.transition()
                    .from(BfdPortFsmState.UP).to(BfdPortFsmState.DOWN).on(BfdPortFsmEvent.PORT_DOWN);
            builder.onEntry(BfdPortFsmState.UP)
                    .callMethod("upEnter");

            // DOWN
            builder.transition()
                    .from(BfdPortFsmState.DOWN).to(BfdPortFsmState.UP).on(BfdPortFsmEvent.PORT_UP);
            builder.onEntry(BfdPortFsmState.DOWN)
                    .callMethod("downEnter");

            // OFFLINE
            builder.transition()
                    .from(BfdPortFsmState.OFFLINE).to(BfdPortFsmState.ACTIVE).on(BfdPortFsmEvent.ONLINE);
            builder.transition()
                    .from(BfdPortFsmState.OFFLINE).to(BfdPortFsmState.REMOVE_INTERRUPT).on(BfdPortFsmEvent.DISABLE);

            // INIT_REMOVE
            builder.transition()
                    .from(BfdPortFsmState.INIT_REMOVE).to(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.NEXT);
            builder.onEntry(BfdPortFsmState.INIT_REMOVE)
                    .callMethod(makeBfdRemoveActionMethod);

            // DO_REMOVE
            builder.transition()
                    .from(BfdPortFsmState.DO_REMOVE).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.ACTION_SUCCESS)
                    .callMethod(doReleaseResourcesMethod);
            builder.transition()
                    .from(BfdPortFsmState.DO_REMOVE).to(BfdPortFsmState.REMOVE_FAIL).on(BfdPortFsmEvent.ACTION_FAIL);
            builder.transition()
                    .from(BfdPortFsmState.DO_REMOVE).to(BfdPortFsmState.REMOVE_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.DO_REMOVE).to(BfdPortFsmState.DO_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.transition()
                    .from(BfdPortFsmState.DO_REMOVE).to(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);
            builder.internalTransition().within(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);

            // REMOVE_FAIL
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_FAIL).to(BfdPortFsmState.CHARGED_RESET)
                    .on(BfdPortFsmEvent.ENABLE_UPDATE).callMethod(saveIslReferenceMethod);
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_FAIL).to(BfdPortFsmState.REMOVE_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_FAIL).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.onEntry(BfdPortFsmState.REMOVE_FAIL)
                    .callMethod("removeFailEnter");

            // REMOVE_INTERRUPT
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_INTERRUPT).to(BfdPortFsmState.INIT_REMOVE)
                    .on(BfdPortFsmEvent.ONLINE);
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_INTERRUPT).to(BfdPortFsmState.CHARGED_INTERRUPT)
                    .on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);

            // CHARGED
            builder.transition()
                    .from(BfdPortFsmState.CHARGED).to(BfdPortFsmState.INIT_SETUP).on(BfdPortFsmEvent.ACTION_SUCCESS)
                    .callMethod(doReleaseResourcesMethod);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED).to(BfdPortFsmState.CHARGED_FAIL).on(BfdPortFsmEvent.ACTION_FAIL);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED).to(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED).to(BfdPortFsmState.CHARGED_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED).to(BfdPortFsmState.DO_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.internalTransition()
                    .within(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.internalTransition()
                    .within(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);

            // CHARGED_FAIL
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_FAIL).to(BfdPortFsmState.CHARGED_INTERRUPT)
                    .on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_FAIL).to(BfdPortFsmState.REMOVE_FAIL).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_FAIL).to(BfdPortFsmState.CHARGED_RESET)
                    .on(BfdPortFsmEvent.ENABLE_UPDATE).callMethod(saveIslReferenceMethod);
            builder.onEntry(BfdPortFsmState.CHARGED_FAIL)
                    .callMethod("chargedFailEnter");

            // CHARGED_INTERRUPT
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_INTERRUPT).to(BfdPortFsmState.CHARGED_RESET)
                    .on(BfdPortFsmEvent.ONLINE);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_INTERRUPT).to(BfdPortFsmState.REMOVE_INTERRUPT)
                    .on(BfdPortFsmEvent.DISABLE);

            // CHARGED_RESET
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_RESET).to(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.NEXT);
            builder.onEntry(BfdPortFsmState.CHARGED_RESET)
                    .callMethod(makeBfdRemoveActionMethod);

            // INIT_CLEANUP
            builder.transition()
                    .from(BfdPortFsmState.INIT_CLEANUP).to(BfdPortFsmState.DO_CLEANUP).on(BfdPortFsmEvent.NEXT);
            builder.onEntry(BfdPortFsmState.INIT_CLEANUP)
                    .callMethod(makeBfdRemoveActionMethod);

            // DO_CLEANUP
            builder.transition()
                    .from(BfdPortFsmState.DO_CLEANUP).to(BfdPortFsmState.STOP).on(BfdPortFsmEvent.ACTION_SUCCESS)
                    .callMethod(doReleaseResourcesMethod);
            builder.transition()
                    .from(BfdPortFsmState.DO_CLEANUP).to(BfdPortFsmState.STOP).on(BfdPortFsmEvent.ACTION_FAIL);
            builder.internalTransition()
                    .within(BfdPortFsmState.DO_CLEANUP).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);

            // STOP
            builder.defineFinalState(BfdPortFsmState.STOP);
        }

        public BfdPortFsm produce(PersistenceManager persistenceManager, Endpoint endpoint,
                                  Integer physicalPortNumber) {
            return builder.newStateMachine(BfdPortFsmState.INIT, persistenceManager, endpoint, physicalPortNumber);
        }
    }

    @Value
    @Builder
    public static class BfdPortFsmContext {
        private final IBfdPortCarrier output;

        private IslReference islReference;
        private BfdProperties properties;

        private String requestKey;
        private BfdSessionResponse bfdSessionResponse;

        /**
         * Builder.
         */
        public static BfdPortFsmContextBuilder builder(IBfdPortCarrier carrier) {
            return (new BfdPortFsmContextBuilder())
                    .output(carrier);
        }
    }

    public enum BfdPortFsmEvent {
        NEXT, KILL, FAIL,

        HISTORY,
        ENABLE_UPDATE, DISABLE,
        ONLINE, OFFLINE,
        PORT_UP, PORT_DOWN,

        SPEAKER_RESPONSE,
        ACTION_SUCCESS, ACTION_FAIL,

        _INIT_CHOICE_CLEAN, _INIT_CHOICE_DIRTY
    }

    public enum BfdPortFsmState {
        INIT, INIT_CHOICE,
        IDLE, UNOPERATIONAL, PENDING,

        INIT_SETUP, DO_SETUP, SETUP_FAIL, SETUP_INTERRUPT,
        RESET,
        ACTIVE, WAIT_STATUS, UP, DOWN, OFFLINE,
        INIT_REMOVE, DO_REMOVE, REMOVE_FAIL, REMOVE_INTERRUPT,
        CHARGED, CHARGED_FAIL, CHARGED_INTERRUPT, CHARGED_RESET,
        INIT_CLEANUP, DO_CLEANUP,

        STOP
    }
}
