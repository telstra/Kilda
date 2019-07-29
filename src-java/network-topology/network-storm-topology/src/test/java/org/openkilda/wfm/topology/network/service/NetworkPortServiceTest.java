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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionCallbackWithoutResult;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.history.model.PortHistoryEvent;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.internal.verification.Times;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class NetworkPortServiceTest {
    @Mock
    private IPortCarrier carrier;

    @Mock
    private NetworkTopologyDashboardLogger dashboardLogger;

    @Mock
    private PersistenceManager persistenceManager;

    @Mock
    private TransactionManager transactionManager;

    @Mock
    private RepositoryFactory repositoryFactory;

    @Mock
    private PortPropertiesRepository portPropertiesRepository;

    @Mock
    private SwitchRepository switchRepository;

    private final SwitchId alphaDatapath = new SwitchId(1);

    @Before
    public void setup() {
        resetMocks();
    }

    private void resetMocks() {
        reset(carrier);
        reset(dashboardLogger);

        reset(persistenceManager);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);
        doAnswer(invocation -> {
            TransactionCallbackWithoutResult tr = invocation.getArgument(0);
            tr.doInTransaction();
            return null;
        }).when(transactionManager).doInTransaction(any(TransactionCallbackWithoutResult.class));

        reset(portPropertiesRepository);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(portPropertiesRepository).add(any());

        reset(switchRepository);

        reset(repositoryFactory);
        when(repositoryFactory.createPortPropertiesRepository()).thenReturn(portPropertiesRepository);
        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
    }

    @Test
    public void newPort() {
        NetworkPortService service = makeService();
        Endpoint port1 = Endpoint.of(alphaDatapath, 1);
        Endpoint port2 = Endpoint.of(alphaDatapath, 2);

        service.setup(port1, null);
        service.updateOnlineMode(port1, false);
        service.setup(port2, null);
        service.updateOnlineMode(port2, false);

        service.remove(port1);
        service.remove(port2);

        verify(carrier).setupUniIslHandler(Endpoint.of(alphaDatapath, 1), null);
        verify(carrier).removeUniIslHandler(Endpoint.of(alphaDatapath, 1));

        verify(carrier).setupUniIslHandler(Endpoint.of(alphaDatapath, 2), null);
        verify(carrier).removeUniIslHandler(Endpoint.of(alphaDatapath, 2));

        //System.out.println(mockingDetails(carrier).printInvocations());
    }

    @Test
    public void inOperationalUpDownPort() {
        NetworkPortService service = makeService();
        Endpoint port1 = Endpoint.of(alphaDatapath, 1);
        Endpoint port2 = Endpoint.of(alphaDatapath, 2);

        service.setup(port1, null);
        service.updateOnlineMode(port1, true);
        service.setup(port2, null);
        service.updateOnlineMode(port2, true);

        verify(carrier).setupUniIslHandler(Endpoint.of(alphaDatapath, 2), null);
        verifyZeroInteractions(dashboardLogger);

        resetMocks();

        // Port 1 from Unknown to UP then DOWN

        service.updateLinkStatus(port1, LinkStatus.UP);
        service.updateLinkStatus(port1, LinkStatus.DOWN);

        verify(dashboardLogger).onPortUp(port1);
        verify(dashboardLogger).onPortDown(port1);
        verifyNoMoreInteractions(dashboardLogger);

        verify(carrier).enableDiscoveryPoll(Endpoint.of(alphaDatapath, 1));
        verify(carrier).disableDiscoveryPoll(Endpoint.of(alphaDatapath, 1));
        verify(carrier).notifyPortPhysicalDown(Endpoint.of(alphaDatapath, 1));

        resetMocks();

        // Port 2 from Unknown to DOWN then UP

        service.updateLinkStatus(port2, LinkStatus.DOWN);
        service.updateLinkStatus(port2, LinkStatus.UP);

        verify(dashboardLogger).onPortDown(port2);
        verify(dashboardLogger).onPortUp(port2);
        verifyNoMoreInteractions(dashboardLogger);

        verify(carrier).notifyPortPhysicalDown(Endpoint.of(alphaDatapath, 2));
        verify(carrier).enableDiscoveryPoll(Endpoint.of(alphaDatapath, 2));

        //System.out.println(mockingDetails(carrier).printInvocations());
    }

    @Test
    public void inUnOperationalUpDownPort() {
        NetworkPortService service = makeService();
        Endpoint endpoint = Endpoint.of(alphaDatapath, 1);

        service.setup(endpoint, null);
        service.updateOnlineMode(endpoint, true);

        resetMocks();

        service.updateOnlineMode(endpoint, false);

        // Port 1 from Unknown to UP then DOWN

        service.updateLinkStatus(endpoint, LinkStatus.UP);
        service.updateLinkStatus(endpoint, LinkStatus.DOWN);

        verifyZeroInteractions(dashboardLogger);

        verify(carrier, never()).enableDiscoveryPoll(endpoint);
        verify(carrier, never()).disableDiscoveryPoll(endpoint);
        verify(carrier, never()).notifyPortPhysicalDown(endpoint);

        resetMocks();

        service.updateOnlineMode(endpoint, true);

        service.updateLinkStatus(endpoint, LinkStatus.UP);
        service.updateLinkStatus(endpoint, LinkStatus.DOWN);

        verify(dashboardLogger).onPortUp(endpoint);
        verify(dashboardLogger).onPortDown(endpoint);
        verifyNoMoreInteractions(dashboardLogger);

        verify(carrier).enableDiscoveryPoll(endpoint);
        verify(carrier).disableDiscoveryPoll(endpoint);
        verify(carrier).notifyPortPhysicalDown(endpoint);
        verify(carrier).sendPortStateChangedHistory(eq(endpoint), eq(PortHistoryEvent.PORT_UP), any(Instant.class));
        verify(carrier).sendPortStateChangedHistory(eq(endpoint), eq(PortHistoryEvent.PORT_DOWN), any(Instant.class));

        // System.out.println(mockingDetails(carrier).printInvocations());
    }

    @Test
    public void createPortProperties() {
        NetworkPortService service = makeService();
        int port = 7;
        Endpoint endpoint = Endpoint.of(alphaDatapath, port);

        when(portPropertiesRepository.getBySwitchIdAndPort(alphaDatapath, port))
                .thenReturn(Optional.empty());
        when(switchRepository.findById(alphaDatapath))
                .thenReturn(Optional.of(getDefaultSwitch()));

        service.setup(endpoint, null);
        service.updateOnlineMode(endpoint, true);
        service.updateLinkStatus(endpoint, LinkStatus.UP);
        service.updatePortProperties(endpoint, false);

        service.remove(endpoint);

        verify(carrier).setupUniIslHandler(endpoint, null);
        verify(carrier).sendPortStateChangedHistory(eq(endpoint), eq(PortHistoryEvent.PORT_UP), any(Instant.class));
        verify(carrier).enableDiscoveryPoll(endpoint);
        verify(carrier, new Times(2)).disableDiscoveryPoll(endpoint);
        verify(carrier).notifyPortDiscoveryFailed(endpoint);
        verify(carrier).notifyPortPropertiesChanged(any(PortProperties.class));
        verify(carrier).removeUniIslHandler(endpoint);

        verify(portPropertiesRepository).add(PortProperties.builder()
                .switchObj(getDefaultSwitch())
                .port(port)
                .discoveryEnabled(false)
                .build());
    }

    @Test
    public void disableDiscoveryWhenPortDown() {
        NetworkPortService service = makeService();
        int port = 7;
        Endpoint endpoint = Endpoint.of(alphaDatapath, port);

        when(portPropertiesRepository.getBySwitchIdAndPort(alphaDatapath, port))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(PortProperties.builder()
                        .switchObj(getDefaultSwitch())
                        .port(port)
                        .discoveryEnabled(false)
                        .build()));
        when(switchRepository.findById(alphaDatapath))
                .thenReturn(Optional.of(getDefaultSwitch()));

        service.setup(endpoint, null);
        service.updateOnlineMode(endpoint, true);
        service.updateLinkStatus(endpoint, LinkStatus.DOWN);
        service.updatePortProperties(endpoint, false);
        service.updateLinkStatus(endpoint, LinkStatus.UP);

        service.remove(endpoint);

        verify(carrier).setupUniIslHandler(endpoint, null);
        verify(carrier).sendPortStateChangedHistory(eq(endpoint), eq(PortHistoryEvent.PORT_DOWN), any(Instant.class));
        verify(carrier, new Times(2)).disableDiscoveryPoll(endpoint);
        verify(carrier).notifyPortPhysicalDown(endpoint);
        verify(carrier).notifyPortPropertiesChanged(any(PortProperties.class));
        verify(carrier).sendPortStateChangedHistory(eq(endpoint), eq(PortHistoryEvent.PORT_UP), any(Instant.class));
        verify(carrier, new Times(0)).enableDiscoveryPoll(endpoint);
        verify(carrier).removeUniIslHandler(endpoint);

        verify(portPropertiesRepository).add(PortProperties.builder()
                .switchObj(getDefaultSwitch())
                .port(port)
                .discoveryEnabled(false)
                .build());
    }

    private NetworkPortService makeService() {
        NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder = mock(
                NetworkTopologyDashboardLogger.Builder.class);
        when(dashboardLoggerBuilder.build(any(Logger.class))).thenReturn(dashboardLogger);

        return new NetworkPortService(carrier, persistenceManager, dashboardLoggerBuilder);
    }

    private Switch getDefaultSwitch() {
        return Switch.builder().switchId(alphaDatapath).build();
    }
}
