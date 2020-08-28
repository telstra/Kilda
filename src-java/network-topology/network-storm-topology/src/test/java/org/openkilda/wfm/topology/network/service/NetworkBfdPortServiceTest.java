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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.BfdSession;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.error.BfdPortControllerNotFoundException;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class NetworkBfdPortServiceTest {
    private static final int BFD_LOGICAL_PORT_OFFSET = 200;

    private final Endpoint alphaEndpoint = Endpoint.of(new SwitchId(1), 1);
    private final Endpoint alphaLogicalEndpoint = Endpoint.of(alphaEndpoint.getDatapath(),
                                                              alphaEndpoint.getPortNumber() + BFD_LOGICAL_PORT_OFFSET);
    private final Endpoint betaEndpoint = Endpoint.of(new SwitchId(2), 2);
    private final Endpoint betaLogicalEndpoint = Endpoint.of(betaEndpoint.getDatapath(),
                                                             betaEndpoint.getPortNumber() + BFD_LOGICAL_PORT_OFFSET);

    private final Endpoint gammaEndpoint = Endpoint.of(new SwitchId(3), 3);

    private final IslReference alphaToBetaIslRef = new IslReference(alphaEndpoint, betaEndpoint);
    private final BfdProperties genericBfdProperties = BfdProperties.builder()
            .interval(Duration.ofMillis(350))
            .multiplier((short) 3)
            .build();

    private final String alphaAddress = "192.168.1.1";
    private final String betaAddress = "192.168.1.2";
    private final String gammaAddress = "192.168.1.2";

    private final Switch alphaSwitch = Switch.builder()
            .switchId(alphaEndpoint.getDatapath())
            .socketAddress(getSocketAddress(alphaAddress, 30070))
            .build();
    private final Switch betaSwitch = Switch.builder()
            .switchId(betaEndpoint.getDatapath())
            .socketAddress(getSocketAddress(betaAddress, 30071))
            .build();
    private final Switch gammaSwitch = Switch.builder()
            .switchId(gammaEndpoint.getDatapath())
            .socketAddress(getSocketAddress(gammaAddress, 30072))
            .build();

    private final String setupRequestKey = "bfd-setup-speaker-key";
    private final String removeRequestKey = "bfd-remove-speaker-key";

    @Mock
    private PersistenceManager persistenceManager;

    @Mock
    private SwitchRepository switchRepository;

    @Mock
    private BfdSessionRepository bfdSessionRepository;

    @Mock
    private IBfdPortCarrier carrier;

    private NetworkBfdPortService service;

    @Before
    public void setUp() throws Exception {
        RepositoryFactory repositoryFactory = Mockito.mock(RepositoryFactory.class);

        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
        when(repositoryFactory.createBfdSessionRepository()).thenReturn(bfdSessionRepository);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        service = new NetworkBfdPortService(carrier, persistenceManager);
    }

    @Test
    public void enableDisable() throws UnknownHostException {
        when(bfdSessionRepository.findBySwitchIdAndPort(alphaEndpoint.getDatapath(), alphaEndpoint.getPortNumber()))
                .thenReturn(Optional.empty());
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);

        service.setup(alphaLogicalEndpoint, alphaEndpoint.getPortNumber());

        reset(bfdSessionRepository);

        // setup BFD session
        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey);

        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);

        ArgumentCaptor<NoviBfdSession> setupBfdSessionArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).setupBfdSession(setupBfdSessionArgument.capture());
        NoviBfdSession setupBfdSessionPayload = setupBfdSessionArgument.getValue();

        service.speakerResponse(setupRequestKey, alphaLogicalEndpoint, new BfdSessionResponse(
                setupBfdSessionPayload, null));
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);

        verify(bfdSessionRepository, times(2)).findBySwitchIdAndPort(
                alphaLogicalEndpoint.getDatapath(), alphaLogicalEndpoint.getPortNumber());

        ArgumentCaptor<BfdSession> bfdSessionCreateArgument = ArgumentCaptor.forClass(BfdSession.class);
        verify(bfdSessionRepository).createOrUpdate(bfdSessionCreateArgument.capture());

        BfdSession bfdSessionDb = bfdSessionCreateArgument.getValue();
        Assert.assertEquals(alphaLogicalEndpoint.getDatapath(), bfdSessionDb.getSwitchId());
        Assert.assertEquals((Integer) alphaLogicalEndpoint.getPortNumber(), bfdSessionDb.getPort());
        Assert.assertNotNull(bfdSessionDb.getDiscriminator());

        ArgumentCaptor<NoviBfdSession> bfdSessionCreateSpeakerArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).setupBfdSession(bfdSessionCreateSpeakerArgument.capture());

        NoviBfdSession speakerBfdSetup = bfdSessionCreateSpeakerArgument.getValue();
        Assert.assertEquals(alphaEndpoint.getDatapath(), speakerBfdSetup.getTarget().getDatapath());
        Assert.assertEquals(InetAddress.getByName(alphaAddress), speakerBfdSetup.getTarget().getInetAddress());
        Assert.assertEquals(betaEndpoint.getDatapath(), speakerBfdSetup.getRemote().getDatapath());
        Assert.assertEquals(InetAddress.getByName(betaAddress), speakerBfdSetup.getRemote().getInetAddress());
        Assert.assertEquals(alphaEndpoint.getPortNumber(), speakerBfdSetup.getPhysicalPortNumber());
        Assert.assertEquals(alphaLogicalEndpoint.getPortNumber(), speakerBfdSetup.getLogicalPortNumber());
        Assert.assertEquals(bfdSessionDb.getDiscriminator(), (Integer) speakerBfdSetup.getDiscriminator());
        Assert.assertTrue(speakerBfdSetup.isKeepOverDisconnect());

        verify(carrier).bfdUpNotification(alphaEndpoint);

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(bfdSessionRepository);

        reset(carrier);
        reset(bfdSessionRepository);

        // remove BFD session
        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);

        service.disable(alphaEndpoint);

        verify(carrier).bfdKillNotification(alphaEndpoint);

        ArgumentCaptor<NoviBfdSession> bfdSessionRemoveSpeakerArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).removeBfdSession(bfdSessionRemoveSpeakerArgument.capture());

        NoviBfdSession speakerBfdRemove = bfdSessionRemoveSpeakerArgument.getValue();
        Assert.assertEquals(speakerBfdSetup, speakerBfdRemove);

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(bfdSessionRepository);

        reset(carrier);

        // remove confirmation

        when(bfdSessionRepository.findBySwitchIdAndPort(alphaLogicalEndpoint.getDatapath(),
                                                        alphaLogicalEndpoint.getPortNumber()))
                .thenReturn(Optional.of(bfdSessionDb));

        BfdSessionResponse speakerResponse = new BfdSessionResponse(speakerBfdRemove, null);
        service.speakerResponse(removeRequestKey, alphaLogicalEndpoint, speakerResponse);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);

        verify(bfdSessionRepository).findBySwitchIdAndPort(alphaLogicalEndpoint.getDatapath(),
                                                        alphaLogicalEndpoint.getPortNumber());
        verify(bfdSessionRepository).delete(bfdSessionDb);

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(bfdSessionRepository);
    }

    @Test
    public void distinguishRecoverableErrors() {
        // prepare DB record to force cleanup on start
        BfdSession initialBfdSession = makeBfdSession(1);
        NoviBfdSession removeRequestPayload = forceCleanupAfterInit(initialBfdSession);

        // push speaker error response
        mockBfdSessionLookup(initialBfdSession);
        BfdSessionResponse removeResponse = new BfdSessionResponse(
                removeRequestPayload, NoviBfdSession.Errors.NOVI_BFD_DISCRIMINATOR_NOT_FOUND_ERROR);
        service.speakerResponse(removeRequestKey, alphaLogicalEndpoint, removeResponse);

        verify(bfdSessionRepository).findBySwitchIdAndPort(alphaLogicalEndpoint.getDatapath(),
                                                           alphaLogicalEndpoint.getPortNumber());
        verify(bfdSessionRepository).delete(initialBfdSession);

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(bfdSessionRepository);

        reset(carrier);
        reset(bfdSessionRepository);

        // following ENABLE request must initiate INSTALL process

        when(bfdSessionRepository.findBySwitchIdAndPort(alphaLogicalEndpoint.getDatapath(),
                                                        alphaLogicalEndpoint.getPortNumber()))
                .thenReturn(Optional.empty());
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);

        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);

        verify(carrier).setupBfdSession(any(NoviBfdSession.class));
    }

    @Test
    public void failOnCriticalErrors() {
        BfdSession initialBfdSession = makeBfdSession(1);
        NoviBfdSession removeRequestPayload = forceCleanupAfterInit(initialBfdSession);

        // push speaker error(critical) response
        mockBfdSessionLookup(initialBfdSession);
        BfdSessionResponse removeResponse = new BfdSessionResponse(
                removeRequestPayload, NoviBfdSession.Errors.SWITCH_RESPONSE_ERROR);
        service.speakerResponse(removeRequestKey, alphaLogicalEndpoint, removeResponse);

        verify(carrier).bfdFailNotification(alphaEndpoint);

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(bfdSessionRepository);

        reset(carrier);
        reset(bfdSessionRepository);

        // make one more remove attempt on next enable/update request
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);
        verify(carrier).removeBfdSession(removeRequestPayload);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void upDownUp() {
        // up
        setupAndEnable();

        // down
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        verify(carrier).bfdDownNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        // up
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);
        verify(carrier).bfdUpNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void upOfflineUp() {
        // up
        setupAndEnable();

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verify(carrier).bfdKillNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        // online (up)
        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);
        verify(carrier).bfdUpNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void upOfflineDownUp() {
        // up
        setupAndEnable();

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verify(carrier).bfdKillNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        // online (down)
        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        verify(carrier).bfdDownNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        // up
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);
        verify(carrier).bfdUpNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void upDownOfflineUp() {
        // up
        setupAndEnable();

        // down
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        verify(carrier).bfdDownNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verify(carrier).bfdKillNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        // up
        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);
        verify(carrier).bfdUpNotification(alphaEndpoint);

        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void surviveOffline() {
        setupController();

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);

        verifyNoMoreInteractions(carrier);

        // online
        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);

        verifyNoMoreInteractions(carrier);

        // ensure we react on enable requests
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);

        verify(carrier).setupBfdSession(argThat(
                argument -> argument.getTarget().getDatapath().equals(alphaLogicalEndpoint.getDatapath())
                && argument.getRemote().getDatapath().equals(betaEndpoint.getDatapath())));
    }

    @Test
    public void enableWhileOffline() {
        setupController();

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verifyNoMoreInteractions(carrier);

        // enable
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);
        verifyNoMoreInteractions(carrier);

        // online
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);

        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);

        verify(carrier).setupBfdSession(argThat(
                argument -> argument.getTarget().getDatapath().equals(alphaLogicalEndpoint.getDatapath())
                && argument.getRemote().getDatapath().equals(betaEndpoint.getDatapath())));
    }

    @Test
    public void enableDisableWhileOffline() {
        setupController();

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verifyNoMoreInteractions(carrier);

        // enable
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);
        verifyNoMoreInteractions(carrier);

        // disable
        service.disable(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        // online
        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        verifyNoMoreInteractions(carrier);

        // ensure following enable request do not interfere with previous(canceled) requests
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(gammaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);

        service.enableUpdate(alphaEndpoint, new IslReference(alphaEndpoint, gammaEndpoint), genericBfdProperties);
        verify(carrier).setupBfdSession(argThat(
                argument -> argument.getTarget().getDatapath().equals(alphaLogicalEndpoint.getDatapath())
                        && argument.getRemote().getDatapath().equals(gammaEndpoint.getDatapath())));
    }

    @Test
    public void offlineDuringInstalling() {
        setupController();

        // enable
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);
        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey);
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);

        ArgumentCaptor<NoviBfdSession> setupBfdSessionArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).setupBfdSession(setupBfdSessionArgument.capture());

        final NoviBfdSession initialSpeakerBfdSession = setupBfdSessionArgument.getValue();

        reset(carrier);
        reset(switchRepository);
        reset(bfdSessionRepository);

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verifyNoMoreInteractions(carrier);

        // online (should start recovery)
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);
        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);

        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);

        verify(carrier).removeBfdSession(setupBfdSessionArgument.capture());
        final NoviBfdSession recoverySpeakerBfdSession = setupBfdSessionArgument.getValue();

        Assert.assertEquals(initialSpeakerBfdSession, recoverySpeakerBfdSession);

        verifyNoMoreInteractions(carrier);

        reset(carrier);
        reset(switchRepository);
        reset(bfdSessionRepository);

        // ignore FL response (timeout)
        service.speakerTimeout(setupRequestKey, alphaLogicalEndpoint);
        verifyNoMoreInteractions(carrier);

        // react on valid FL response
        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey + "#2");

        BfdSessionResponse response = new BfdSessionResponse(recoverySpeakerBfdSession, null);
        service.speakerResponse(removeRequestKey, alphaLogicalEndpoint, response);

        verify(carrier).setupBfdSession(argThat(
                argument -> argument.getTarget().getDatapath().equals(alphaLogicalEndpoint.getDatapath())
                        && argument.getRemote().getDatapath().equals(betaEndpoint.getDatapath())
                        && initialSpeakerBfdSession.getDiscriminator() == argument.getDiscriminator()));
    }

    @Test
    public void offlineDuringCleaning() {
        setupAndEnable();

        String requestKey = "request-key-#";

        // disable
        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(requestKey + "1");

        service.disable(alphaEndpoint);

        ArgumentCaptor<NoviBfdSession> removeBfdSessionArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).removeBfdSession(removeBfdSessionArgument.capture());

        reset(carrier);

        // offline
        service.updateOnlineMode(alphaLogicalEndpoint, false);
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // online
        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(requestKey + "2");

        service.updateOnlineMode(alphaLogicalEndpoint, true);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);

        verify(carrier).removeBfdSession(removeBfdSessionArgument.getValue());

        verifyNoMoreInteractions(carrier);

        // ignore outdated timeout
        service.speakerTimeout(requestKey + "1", alphaLogicalEndpoint);
        // IDLE and DO_REMOVE will ignore it, but REMOVE_FAIL will react
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        verifyNoMoreInteractions(carrier);

        service.speakerResponse(requestKey + "2", alphaLogicalEndpoint,
                                new BfdSessionResponse(removeBfdSessionArgument.getValue(),
                                                       NoviBfdSession.Errors.NOVI_BFD_DISCRIMINATOR_NOT_FOUND_ERROR));
        verifyNoMoreInteractions(carrier);

        // IDLE ignore but REMOVE_FAIL react
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        verifyNoMoreInteractions(carrier);

        // ensure we are in IDLE
        doEnable();
    }

    @Test
    public void killDuringInstalling() {
        setupController();
        NoviBfdSession session = doEnable();

        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);
        service.remove(alphaLogicalEndpoint);

        verifyTerminateSequence(session);
    }

    @Test
    public void killDuringActiveUp() {
        NoviBfdSession session = setupAndEnable();

        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);
        service.remove(alphaLogicalEndpoint);

        verify(carrier).bfdKillNotification(alphaEndpoint);

        verifyTerminateSequence(session);
    }

    @Test
    public void killDuringCleaning() {
        NoviBfdSession session = setupAndEnable();

        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);

        service.disable(alphaEndpoint);
        verify(carrier).bfdKillNotification(alphaEndpoint);
        verify(carrier).removeBfdSession(session);

        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // remove
        service.remove(alphaLogicalEndpoint);

        verifyKillSequence(session);
    }

    @Test
    public void enableBeforeSetup() {
        IslReference reference = new IslReference(alphaEndpoint, betaEndpoint);

        // enable
        doEnableBeforeSetup(reference);

        // setup
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);
        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey);

        service.setup(alphaLogicalEndpoint, alphaEndpoint.getPortNumber());

        verify(bfdSessionRepository).createOrUpdate(any(BfdSession.class));
        verify(carrier).setupBfdSession(argThat(
                arg -> arg.getTarget().getDatapath().equals(alphaEndpoint.getDatapath())
                        && arg.getPhysicalPortNumber() == alphaEndpoint.getPortNumber()
                        && arg.getLogicalPortNumber() == alphaLogicalEndpoint.getPortNumber()
                        && arg.getRemote().getDatapath().equals(betaEndpoint.getDatapath())));
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void enableBeforeSetupForDirtyEndpoint() {
        final int discriminator = 1;
        final String requestKeyRemove = "remove-request";
        IslReference reference = new IslReference(alphaEndpoint, betaEndpoint);

        // enable
        doEnableBeforeSetup(reference);

        // setup (dirty)
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);

        BfdSession bfdSession = makeBfdSession(discriminator);
        mockBfdSessionLookup(bfdSession);

        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(requestKeyRemove);

        service.setup(alphaLogicalEndpoint, alphaEndpoint.getPortNumber());

        ArgumentCaptor<NoviBfdSession> removeBfdSessionArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).removeBfdSession(removeBfdSessionArgument.capture());
        verifyNoMoreInteractions(carrier);

        reset(bfdSessionRepository);
        reset(carrier);

        // speaker response
        mockBfdSessionLookup(bfdSession);

        service.speakerResponse(requestKeyRemove, alphaLogicalEndpoint,
                                new BfdSessionResponse(removeBfdSessionArgument.getValue(), null));

        ArgumentCaptor<BfdSession> createOrUpdateArgument = ArgumentCaptor.forClass(BfdSession.class);

        verify(bfdSessionRepository).delete(bfdSession);
        verify(bfdSessionRepository).createOrUpdate(createOrUpdateArgument.capture());

        BfdSession autostartSession = createOrUpdateArgument.getValue();
        verify(carrier).setupBfdSession(argThat(arg -> arg.getDiscriminator() == autostartSession.getDiscriminator()));
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void updateWhileActive() {
        setupAndEnable();

        // active
        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);
        BfdProperties update = BfdProperties.builder()
                .interval(Duration.ofMillis(genericBfdProperties.getInterval().toMillis() + 100))
                .multiplier((short) (genericBfdProperties.getMultiplier() + 1))
                .build();
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, update);

        verify(carrier).bfdKillNotification(alphaEndpoint);

        ArgumentCaptor<NoviBfdSession> speakerBfdRequestArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).removeBfdSession(speakerBfdRequestArgument.capture());
        NoviBfdSession speakerBfdSession = speakerBfdRequestArgument.getValue();

        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // reset
        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey);

        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN);
        BfdSessionResponse removeResponse = new BfdSessionResponse(speakerBfdSession, null);
        service.speakerResponse(removeRequestKey, alphaLogicalEndpoint, removeResponse);

        verify(carrier).setupBfdSession(speakerBfdRequestArgument.capture());
        speakerBfdSession = speakerBfdRequestArgument.getValue();

        Assert.assertEquals(update.getInterval().toMillis(), speakerBfdSession.getIntervalMs());
        Assert.assertEquals(update.getMultiplier(), speakerBfdSession.getMultiplier());

        verifyNoMoreInteractions(carrier);

        reset(carrier);
        reset(bfdSessionRepository);

        // do_setup
        BfdSession dbView = new BfdSession(
                alphaLogicalEndpoint.getDatapath(), alphaLogicalEndpoint.getPortNumber(), alphaEndpoint.getPortNumber(),
                genericBfdProperties);
        when(bfdSessionRepository.findBySwitchIdAndPort(
                alphaLogicalEndpoint.getDatapath(), alphaLogicalEndpoint.getPortNumber()))
                .thenReturn(Optional.of(dbView));
        BfdSessionResponse setupResponse = new BfdSessionResponse(speakerBfdSession, null);
        service.speakerResponse(setupRequestKey, alphaLogicalEndpoint, setupResponse);
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);
        verify(carrier).bfdUpNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        Assert.assertEquals(update.getInterval(), dbView.getInterval());
        Assert.assertEquals(update.getMultiplier(), dbView.getMultiplier());
        verify(bfdSessionRepository).createOrUpdate(dbView);

        // active
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.DOWN); // ensure we are reaction on link status update
        verify(carrier).bfdDownNotification(alphaEndpoint);
    }

    private NoviBfdSession setupAndEnable() {
        // setup
        setupController();

        // enable
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);
        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey);
        service.enableUpdate(alphaEndpoint, alphaToBetaIslRef, genericBfdProperties);

        ArgumentCaptor<NoviBfdSession> speakerBfdSetupRequestArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).setupBfdSession(speakerBfdSetupRequestArgument.capture());
        NoviBfdSession speakerBfdSession = speakerBfdSetupRequestArgument.getValue();

        verify(bfdSessionRepository).createOrUpdate(any(BfdSession.class));

        reset(carrier);
        reset(bfdSessionRepository);
        reset(switchRepository);

        // speaker response
        BfdSessionResponse response = new BfdSessionResponse(speakerBfdSession, null);
        service.speakerResponse(setupRequestKey, alphaLogicalEndpoint, response);

        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // port up
        service.updateLinkStatus(alphaLogicalEndpoint, LinkStatus.UP);
        verify(carrier).bfdUpNotification(alphaEndpoint);
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        return speakerBfdSession;
    }

    private NoviBfdSession forceCleanupAfterInit(BfdSession initialBfdSession) {
        when(bfdSessionRepository.findBySwitchIdAndPort(alphaLogicalEndpoint.getDatapath(),
                                                        alphaLogicalEndpoint.getPortNumber()))
                .thenReturn(Optional.of(initialBfdSession));
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);

        when(carrier.removeBfdSession(any(NoviBfdSession.class))).thenReturn(removeRequestKey);

        service.setup(alphaLogicalEndpoint, alphaEndpoint.getPortNumber());

        ArgumentCaptor<NoviBfdSession> setupBfdSessionArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).removeBfdSession(setupBfdSessionArgument.capture());

        reset(carrier);
        reset(bfdSessionRepository);

        return setupBfdSessionArgument.getValue();
    }

    private void setupController() {
        when(bfdSessionRepository.findBySwitchIdAndPort(alphaLogicalEndpoint.getDatapath(),
                                                        alphaLogicalEndpoint.getPortNumber()))
                .thenReturn(Optional.empty());

        service.setup(alphaLogicalEndpoint, alphaEndpoint.getPortNumber());

        verifyNoMoreInteractions(carrier);

        reset(carrier);
        reset(bfdSessionRepository);
    }

    private NoviBfdSession doEnable() {
        mockSwitchLookup(alphaSwitch);
        mockSwitchLookup(betaSwitch);
        mockMissingBfdSession(alphaLogicalEndpoint);

        when(carrier.setupBfdSession(any(NoviBfdSession.class))).thenReturn(setupRequestKey);

        service.enableUpdate(alphaEndpoint, new IslReference(alphaEndpoint, betaEndpoint), genericBfdProperties);

        ArgumentCaptor<NoviBfdSession> setupBfdSessionArgument = ArgumentCaptor.forClass(NoviBfdSession.class);
        verify(carrier).setupBfdSession(setupBfdSessionArgument.capture());
        verifyNoMoreInteractions(carrier);

        reset(carrier);

        return setupBfdSessionArgument.getValue();
    }

    private void doEnableBeforeSetup(IslReference reference) {
        try {
            service.enableUpdate(alphaEndpoint, reference, genericBfdProperties);
            throw new AssertionError(String.format(
                    "There is no expected BfdPortControllerNotFoundException from %s.enable(...)",
                    service.getClass().getName()));
        } catch (BfdPortControllerNotFoundException e) {
            // expected behaviour
        }
        verifyNoMoreInteractions(bfdSessionRepository);
        verifyNoMoreInteractions(carrier);
    }

    private void verifyTerminateSequence(NoviBfdSession expectedSession) {
        verify(carrier).removeBfdSession(expectedSession);
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        verifyKillSequence(expectedSession);
    }

    private void verifyKillSequence(NoviBfdSession expectedSession) {
        // install new handler
        service.setup(alphaLogicalEndpoint, alphaEndpoint.getPortNumber());

        when(bfdSessionRepository.findBySwitchIdAndPort(
                expectedSession.getTarget().getDatapath(), expectedSession.getLogicalPortNumber()))
                .thenReturn(Optional.of(
                        BfdSession.builder(expectedSession.getTarget().getDatapath(),
                                           expectedSession.getLogicalPortNumber(), alphaEndpoint.getPortNumber())
                                .ipAddress(expectedSession.getTarget().getInetAddress().toString())
                                .remoteSwitchId(expectedSession.getRemote().getDatapath())
                                .remoteIpAddress(expectedSession.getRemote().getInetAddress().toString())
                                .discriminator(expectedSession.getDiscriminator())
                                .build()));

        service.speakerResponse(removeRequestKey, alphaLogicalEndpoint, new BfdSessionResponse(expectedSession, null));
        verify(bfdSessionRepository).delete(argThat(arg ->
                arg.getDiscriminator() == expectedSession.getDiscriminator()
                        && arg.getSwitchId() == expectedSession.getTarget().getDatapath()
                        && arg.getPort() == expectedSession.getLogicalPortNumber()
                        && arg.getRemoteSwitchId() == expectedSession.getRemote().getDatapath()));
    }

    private BfdSession makeBfdSession(Integer discriminator) {
        return BfdSession.builder(
                alphaLogicalEndpoint.getDatapath(), alphaLogicalEndpoint.getPortNumber(), alphaEndpoint.getPortNumber())
                .ipAddress(alphaAddress)
                .remoteSwitchId(betaLogicalEndpoint.getDatapath())
                .remoteIpAddress(betaAddress)
                .discriminator(discriminator)
                .interval(genericBfdProperties.getInterval())
                .multiplier(genericBfdProperties.getMultiplier())
                .build();
    }

    private void mockSwitchLookup(Switch sw) {
        when(switchRepository.findById(sw.getSwitchId())).thenReturn(Optional.ofNullable(sw));
    }

    private void mockMissingBfdSession(Endpoint endpoint) {
        when(bfdSessionRepository.findBySwitchIdAndPort(endpoint.getDatapath(), endpoint.getPortNumber()))
                .thenReturn(Optional.empty());
    }

    private void mockBfdSessionLookup(BfdSession session) {
        when(bfdSessionRepository.findBySwitchIdAndPort(session.getSwitchId(), session.getPort()))
                .thenReturn(Optional.of(session));
    }

    private InetSocketAddress getSocketAddress(String host, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
