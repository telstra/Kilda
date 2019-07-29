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
import org.openkilda.model.BfdSession;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.PersistenceManager;
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
    static int bfdPollInterval = 350;  // TODO: use config option
    static short bfdFailCycleLimit = 3;  // TODO: use config option

    private final SwitchRepository switchRepository;
    private final BfdSessionRepository bfdSessionRepository;

    private final Random random = new Random();

    @Getter
    private final Endpoint physicalEndpoint;
    @Getter
    private final Endpoint logicalEndpoint;

    private IslReference islReference;
    private BfdDescriptor sessionDescriptor = null;
    private LinkStatus linkStatus = LinkStatus.DOWN;
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
    }

    // -- external API --

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

    public void saveIslReference(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        islReference = context.getIslReference();
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
                        bfdSessionRepository.remove(value);
                    }
                });
        sessionDescriptor = null;
    }

    public void activeExit(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("notify consumer(s) to STOP react on BFD event");
        context.getOutput().bfdKillNotification(physicalEndpoint);
    }

    public void upEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("LINK detected");
        linkStatus = LinkStatus.UP;
        context.getOutput().bfdUpNotification(physicalEndpoint);
    }

    public void downEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        logInfo("LINK corrupted");
        linkStatus = LinkStatus.DOWN;
        context.getOutput().bfdDownNotification(physicalEndpoint);
    }

    public void setupFailEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                               BfdPortFsmContext context) {
        logError("BFD-setup action have failed");
    }

    public void removeFailEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                BfdPortFsmContext context) {
        logError("BFD-remove action have failed");
    }

    public void chargedFailEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        logError("BFD-remove action have failed (for re-setup)");
    }

    public void doCleanupEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                               BfdPortFsmContext context) {
        logInfo("perform housekeeping - release all resources");

        // We can't receive link status update messages after port handler remove. Force DOWN link status for action
        // so it will not wait for link update and react only on speaker response.
        action.updateLinkStatus(LinkStatus.DOWN)
                .ifPresent(result -> handleActionResult(result, context));
    }

    public void makeBfdSetupAction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                   BfdPortFsmContext context) {
        logInfo(String.format("BFD session setup process have started - discriminator:%s, remote-datapath:%s",
                              sessionDescriptor.getDiscriminator(), sessionDescriptor.getRemote().getDatapath()));
        action = new BfdSessionSetupAction(context.getOutput(), makeBfdSessionRecord());
    }

    public void makeBfdRemoveAction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                    BfdPortFsmContext context) {
        logInfo(String.format("perform BFD session remove - discriminator:%s, remote-datapath:%s",
                              sessionDescriptor.getDiscriminator(), sessionDescriptor.getRemote().getDatapath()));
        action = new BfdSessionRemoveAction(context.getOutput(), makeBfdSessionRecord(), linkStatus);
    }

    public void proxySpeakerResponseIntoAction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                               BfdPortFsmContext context) {
        action.consumeSpeakerResponse(context.getRequestKey(), context.getBfdSessionResponse())
                .ifPresent(result -> handleActionResult(result, context));
    }

    public void proxyLinkStatusUpdateIntoAction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                                BfdPortFsmContext context) {
        if (event == BfdPortFsmEvent.PORT_UP) {
            linkStatus = LinkStatus.UP;
        } else if (event == BfdPortFsmEvent.PORT_DOWN) {
            linkStatus = LinkStatus.DOWN;
        } else {
            throw new IllegalArgumentException(String.format(
                    "Invalid call %s.proxyLinkStatusUpdateIntoAction(...) - can't process event %s",
                    getClass().getCanonicalName(), event));
        }
        action.updateLinkStatus(linkStatus)
                .ifPresent(result -> handleActionResult(result, context));
    }

    public void updateLinkStatus(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        if (event == BfdPortFsmEvent.PORT_UP) {
            linkStatus = LinkStatus.UP;
        } else if (event == BfdPortFsmEvent.PORT_DOWN) {
            linkStatus = LinkStatus.DOWN;
        } else {
            throw new IllegalArgumentException(String.format(
                    "Invalid call %s.updateLinkStatus(...) - can't process event %s",
                    getClass().getCanonicalName(), event));
        }
    }

    public void reportSetupSuccess(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                   BfdPortFsmContext context) {
        logInfo("BFD session setup is successfully completed");
    }

    public void reportConflict(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                               BfdPortFsmContext context) {
        logError("BFD session created outside OpenKilda have been detected (ignore all request involving this port)");
    }

    public void reportMalfunction(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                  BfdPortFsmContext context) {
        logError(String.format("is in %s state - ignore %s request", getCurrentState(), event));
    }

    // -- private/service methods --
    private NoviBfdSession makeBfdSessionRecord() {
        return NoviBfdSession.builder()
                .target(sessionDescriptor.getLocal())
                .remote(sessionDescriptor.getRemote())
                .physicalPortNumber(physicalEndpoint.getPortNumber())
                .logicalPortNumber(logicalEndpoint.getPortNumber())
                .udpPortNumber(BFD_UDP_PORT)
                .discriminator(sessionDescriptor.getDiscriminator())
                .intervalMs(bfdPollInterval)
                .multiplier(bfdFailCycleLimit)
                .keepOverDisconnect(true)
                .build();
    }

    private BfdDescriptor allocateDiscriminator(BfdDescriptor descriptor) {
        BfdSession dbView = loadBfdSession().orElseGet(() -> {
            BfdSession bfdSession = BfdSession.builder()
                    .switchId(logicalEndpoint.getDatapath())
                    .port(logicalEndpoint.getPortNumber())
                    .build();
            bfdSessionRepository.add(bfdSession);
            return bfdSession;
        });

        Integer discriminator = dbView.getDiscriminator();
        descriptor.fill(dbView);

        if (discriminator == null) {
            while (true) {
                // FIXME(surabujin): loop will never end if all possible discriminators are allocated
                discriminator = random.nextInt();
                try {
                    dbView.setDiscriminator(discriminator);
                    break;
                } catch (ConstraintViolationException ex) {
                    log.warn("ConstraintViolationException on allocate bfd discriminator");
                }
            }
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
        private final StateMachineBuilder<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> builder;

        BfdPortFsmFactory() {
            final String doReleaseResourcesMethod = "doReleaseResources";
            final String saveIslReferenceMethod = "saveIslReference";
            final String reportConflictMethod = "reportConflict";
            final String reportMalfunctionMethod = "reportMalfunction";
            final String makeBfdSetupActionMethod = "makeBfdSetupAction";
            final String makeBfdRemoveActionMethod = "makeBfdRemoveAction";
            final String proxySpeakerResponseIntoActionMethod = "proxySpeakerResponseIntoAction";
            final String proxyLinkStatusUpdateIntoActionMethod = "proxyLinkStatusUpdateIntoAction";
            final String updateLinkStatusMethod = "updateLinkStatus";

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
                    .from(BfdPortFsmState.IDLE).to(BfdPortFsmState.INIT_SETUP).on(BfdPortFsmEvent.ENABLE)
                    .callMethod(saveIslReferenceMethod);
            builder.transition()
                    .from(BfdPortFsmState.IDLE).to(BfdPortFsmState.CONFLICT).on(BfdPortFsmEvent.PORT_UP);
            builder.transition()
                    .from(BfdPortFsmState.IDLE).to(BfdPortFsmState.UNOPERATIONAL).on(BfdPortFsmEvent.OFFLINE);
            builder.onEntry(BfdPortFsmState.IDLE)
                    .callMethod("idleEnter");

            // UNOPERATIONAL
            builder.transition()
                    .from(BfdPortFsmState.UNOPERATIONAL).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.ONLINE);
            builder.transition()
                    .from(BfdPortFsmState.UNOPERATIONAL).to(BfdPortFsmState.PENDING).on(BfdPortFsmEvent.ENABLE);

            // CONFLICT
            builder.transition()
                    .from(BfdPortFsmState.CONFLICT).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.PORT_DOWN);
            builder.transition()
                    .from(BfdPortFsmState.CONFLICT).to(BfdPortFsmState.UNOPERATIONAL).on(BfdPortFsmEvent.OFFLINE);
            builder.internalTransition().within(BfdPortFsmState.CONFLICT).on(BfdPortFsmEvent.ENABLE)
                    .callMethod(reportConflictMethod);
            builder.internalTransition().within(BfdPortFsmState.CONFLICT).on(BfdPortFsmEvent.DISABLE)
                    .callMethod(reportConflictMethod);
            builder.onEntry(BfdPortFsmState.CONFLICT)
                    .callMethod(reportConflictMethod);

            // PENDING
            builder.transition()
                    .from(BfdPortFsmState.PENDING).to(BfdPortFsmState.UNOPERATIONAL).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.PENDING).to(BfdPortFsmState.INIT_SETUP).on(BfdPortFsmEvent.PORT_DOWN);
            builder.transition()
                    .from(BfdPortFsmState.PENDING).to(BfdPortFsmState.CONFLICT).on(BfdPortFsmEvent.PORT_UP);
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
            builder.internalTransition().within(BfdPortFsmState.DO_SETUP).on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);
            builder.internalTransition().within(BfdPortFsmState.DO_SETUP).on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);
            builder.onEntry(BfdPortFsmState.DO_SETUP)
                    .callMethod(makeBfdSetupActionMethod);

            // SETUP_FAIL
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.SETUP_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_FAIL).to(BfdPortFsmState.INIT_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.internalTransition()
                    .within(BfdPortFsmState.SETUP_FAIL).on(BfdPortFsmEvent.ENABLE)
                    .callMethod(reportMalfunctionMethod);
            builder.onEntry(BfdPortFsmState.SETUP_FAIL)
                    .callMethod("setupFailEnter");

            // SETUP_INTERRUPT
            builder.transition()
                    .from(BfdPortFsmState.SETUP_INTERRUPT).to(BfdPortFsmState.SETUP_RECOVERY)
                    .on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(updateLinkStatusMethod);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_INTERRUPT).to(BfdPortFsmState.SETUP_RECOVERY)
                    .on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(updateLinkStatusMethod);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_INTERRUPT).to(BfdPortFsmState.REMOVE_INTERRUPT)
                    .on(BfdPortFsmEvent.DISABLE);

            // SETUP_RECOVERY
            builder.transition()
                    .from(BfdPortFsmState.SETUP_RECOVERY).to(BfdPortFsmState.DO_SETUP)
                    .on(BfdPortFsmEvent.ACTION_SUCCESS);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_RECOVERY).to(BfdPortFsmState.SETUP_INTERRUPT)
                    .on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_RECOVERY).to(BfdPortFsmState.SETUP_FAIL)
                    .on(BfdPortFsmEvent.ACTION_FAIL);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_RECOVERY).to(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.SETUP_RECOVERY).to(BfdPortFsmState.DO_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.internalTransition()
                    .within(BfdPortFsmState.SETUP_RECOVERY).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.internalTransition()
                    .within(BfdPortFsmState.SETUP_RECOVERY).on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);
            builder.internalTransition()
                    .within(BfdPortFsmState.SETUP_RECOVERY).on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);
            builder.onEntry(BfdPortFsmState.SETUP_RECOVERY)
                    .callMethod(makeBfdRemoveActionMethod);

            // ACTIVE
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.OFFLINE).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE).to(BfdPortFsmState.INIT_CLEANUP).on(BfdPortFsmEvent.KILL);
            builder.onExit(BfdPortFsmState.ACTIVE)
                    .callMethod("activeExit");
            builder.defineSequentialStatesOn(
                    BfdPortFsmState.ACTIVE,
                    BfdPortFsmState.UP, BfdPortFsmState.DOWN);

            // ACTIVE_RECOVERY
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE_RECOVERY).to(BfdPortFsmState.OFFLINE).on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE_RECOVERY).to(BfdPortFsmState.ACTIVE).on(BfdPortFsmEvent.PORT_UP);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE_RECOVERY).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdPortFsmState.ACTIVE_RECOVERY).to(BfdPortFsmState.INIT_CLEANUP).on(BfdPortFsmEvent.KILL);

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
                    .from(BfdPortFsmState.OFFLINE).to(BfdPortFsmState.ACTIVE_RECOVERY).on(BfdPortFsmEvent.ONLINE);
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
                    .from(BfdPortFsmState.DO_REMOVE).to(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.ENABLE)
                    .callMethod(saveIslReferenceMethod);
            builder.internalTransition().within(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.internalTransition().within(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);
            builder.internalTransition().within(BfdPortFsmState.DO_REMOVE).on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);

            // REMOVE_FAIL
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_FAIL).to(BfdPortFsmState.CHARGED_FAIL).on(BfdPortFsmEvent.ENABLE)
                    .callMethod(saveIslReferenceMethod);
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_FAIL).to(BfdPortFsmState.REMOVE_INTERRUPT).on(BfdPortFsmEvent.OFFLINE);
            builder.internalTransition()
                    .within(BfdPortFsmState.REMOVE_FAIL).on(BfdPortFsmEvent.DISABLE)
                    .callMethod(reportMalfunctionMethod);
            builder.onEntry(BfdPortFsmState.REMOVE_FAIL)
                    .callMethod("removeFailEnter");

            // REMOVE_INTERRUPT
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_INTERRUPT).to(BfdPortFsmState.INIT_REMOVE).on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(updateLinkStatusMethod);
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_INTERRUPT).to(BfdPortFsmState.INIT_REMOVE)
                    .on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(updateLinkStatusMethod);
            builder.transition()
                    .from(BfdPortFsmState.REMOVE_INTERRUPT).to(BfdPortFsmState.CHARGED_INTERRUPT)
                    .on(BfdPortFsmEvent.ENABLE)
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
                    .within(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);
            builder.internalTransition()
                    .within(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(proxyLinkStatusUpdateIntoActionMethod);

            // CHARGED_FAIL
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_FAIL).to(BfdPortFsmState.CHARGED_INTERRUPT)
                    .on(BfdPortFsmEvent.OFFLINE);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_FAIL).to(BfdPortFsmState.REMOVE_FAIL).on(BfdPortFsmEvent.DISABLE);
            builder.onEntry(BfdPortFsmState.CHARGED_FAIL)
                    .callMethod("chargedFailEnter");

            // CHARGED_INTERRUPT
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_INTERRUPT).to(BfdPortFsmState.CHARGED_RECOVERY)
                    .on(BfdPortFsmEvent.PORT_UP)
                    .callMethod(updateLinkStatusMethod);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_INTERRUPT).to(BfdPortFsmState.CHARGED_RECOVERY)
                    .on(BfdPortFsmEvent.PORT_DOWN)
                    .callMethod(updateLinkStatusMethod);
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_INTERRUPT).to(BfdPortFsmState.REMOVE_INTERRUPT)
                    .on(BfdPortFsmEvent.DISABLE);

            // CHARGED_RECOVERY
            builder.transition()
                    .from(BfdPortFsmState.CHARGED_RECOVERY).to(BfdPortFsmState.CHARGED).on(BfdPortFsmEvent.NEXT);
            builder.onEntry(BfdPortFsmState.CHARGED_RECOVERY)
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
            builder.onEntry(BfdPortFsmState.DO_CLEANUP)
                    .callMethod("doCleanupEnter");

            // STOP
            builder.defineFinalState(BfdPortFsmState.STOP);
        }

        public FsmExecutor<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> produceExecutor() {
            return new FsmExecutor<>(BfdPortFsmEvent.NEXT);
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
        ENABLE, DISABLE,
        ONLINE, OFFLINE,
        PORT_UP, PORT_DOWN,

        SPEAKER_RESPONSE,
        ACTION_SUCCESS, ACTION_FAIL,

        _INIT_CHOICE_CLEAN, _INIT_CHOICE_DIRTY
    }

    public enum BfdPortFsmState {
        INIT, INIT_CHOICE,
        IDLE, UNOPERATIONAL, CONFLICT, PENDING,

        INIT_SETUP, DO_SETUP, SETUP_FAIL, SETUP_INTERRUPT, SETUP_RECOVERY,
        ACTIVE, ACTIVE_RECOVERY, UP, DOWN, OFFLINE,
        INIT_REMOVE, DO_REMOVE, REMOVE_FAIL, REMOVE_INTERRUPT,
        CHARGED, CHARGED_FAIL, CHARGED_INTERRUPT, CHARGED_RECOVERY,
        INIT_CLEANUP, DO_CLEANUP,

        STOP
    }
}
