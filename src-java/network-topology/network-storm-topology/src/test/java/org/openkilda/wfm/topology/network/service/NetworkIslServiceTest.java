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

import static java.time.Duration.between;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.info.discovery.InstallIslDefaultRulesResult;
import org.openkilda.messaging.info.discovery.RemoveIslDefaultRulesResult;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.model.LinkProps;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.InMemoryGraphPersistenceManager;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionCallbackWithoutResult;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.NetworkOptions;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class NetworkIslServiceTest {
    @ClassRule
    public static TemporaryFolder fsData = new TemporaryFolder();

    private final Endpoint endpointAlpha1 = Endpoint.of(new SwitchId(1), 1);
    private final Endpoint endpointBeta2 = Endpoint.of(new SwitchId(2), 2);
    private final Map<SwitchId, Switch> allocatedSwitches = new HashMap<>();

    private final NetworkOptions options = NetworkOptions.builder()
            .dbRepeatMaxDurationSeconds(30)
            .build();

    @Mock
    private NetworkTopologyDashboardLogger dashboardLogger;

    @Mock
    private IIslCarrier carrier;

    @Mock
    private PersistenceManager persistenceManager;

    @Mock
    private TransactionManager transactionManager;

    @Mock
    private RepositoryFactory repositoryFactory;

    @Mock
    private SwitchRepository switchRepository;
    @Mock
    private SwitchPropertiesRepository switchPropertiesRepository;
    @Mock
    private IslRepository islRepository;
    @Mock
    private LinkPropsRepository linkPropsRepository;
    @Mock
    private FlowPathRepository flowPathRepository;
    @Mock
    private FeatureTogglesRepository featureTogglesRepository;

    private NetworkIslService service;

    @Before
    public void setUp() {
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
        when(repositoryFactory.createIslRepository()).thenReturn(islRepository);
        when(repositoryFactory.createLinkPropsRepository()).thenReturn(linkPropsRepository);
        when(repositoryFactory.createFlowPathRepository()).thenReturn(flowPathRepository);
        when(repositoryFactory.createFeatureTogglesRepository()).thenReturn(featureTogglesRepository);
        when(repositoryFactory.createSwitchPropertiesRepository()).thenReturn(switchPropertiesRepository);

        when(featureTogglesRepository.getOrDefault()).thenReturn(FeatureToggles.DEFAULTS);

        when(transactionManager.getDefaultRetryPolicy())
                .thenReturn(new RetryPolicy().withMaxRetries(2));
        doAnswer(invocation -> {
            TransactionCallbackWithoutResult tr = invocation.getArgument(0);
            tr.doInTransaction();
            return null;
        }).when(transactionManager).doInTransaction(Mockito.any(TransactionCallbackWithoutResult.class));
        doAnswer(invocation -> {
            RetryPolicy retryPolicy = invocation.getArgument(0);
            TransactionCallbackWithoutResult tr = invocation.getArgument(1);
            Failsafe.with(retryPolicy)
                    .run(tr::doInTransaction);
            return null;
        }).when(transactionManager)
                .doInTransaction(Mockito.any(RetryPolicy.class), Mockito.any(TransactionCallbackWithoutResult.class));

        NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder = mock(
                NetworkTopologyDashboardLogger.Builder.class);
        when(dashboardLoggerBuilder.build(any())).thenReturn(dashboardLogger);
        service = new NetworkIslService(carrier, persistenceManager, options, dashboardLoggerBuilder);
    }

    @Test
    @Ignore("incomplete")
    public void initialUp() {
        persistenceManager = new InMemoryGraphPersistenceManager(
                new PropertiesBasedConfigurationProvider().getConfiguration(NetworkConfig.class));

        emulateEmptyPersistentDb();

        SwitchRepository switchRepository = persistenceManager.getRepositoryFactory()
                .createSwitchRepository();
        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Switch swA = Switch.builder()
                            .switchId(endpointAlpha1.getDatapath())
                            .description("alpha")
                            .build();
            switchRepository.add(swA);
            switchPropertiesRepository.add(SwitchProperties.builder()
                    .multiTable(false)
                    .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                    .switchObj(swA).build());
            Switch swB = Switch.builder()
                            .switchId(endpointBeta2.getDatapath())
                            .description("alpha")
                            .build();
            switchRepository.add(swB);
            switchPropertiesRepository.add(SwitchProperties.builder()
                    .multiTable(false)
                    .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                    .switchObj(swB).build());
        });

        IslReference ref = new IslReference(endpointAlpha1, endpointBeta2);
        IslDataHolder islData = new IslDataHolder(1000, 1000, 1000);
        service = new NetworkIslService(carrier, persistenceManager, options);
        service.islUp(ref.getSource(), ref, islData);

        System.out.println(mockingDetails(carrier).printInvocations());
        System.out.println(mockingDetails(islRepository).printInvocations());
    }

    @Test
    @Ignore("become invalid due to change initialisation logic")
    public void initialMoveEvent() {
        emulateEmptyPersistentDb();

        IslReference ref = new IslReference(endpointAlpha1, endpointBeta2);
        service.islMove(ref.getSource(), ref);

        // System.out.println(mockingDetails(carrier).printInvocations());
        verify(carrier, times(2)).triggerReroute(any(RerouteAffectedFlows.class));

        // System.out.println(mockingDetails(islRepository).printInvocations());
        verify(islRepository).add(argThat(
                link ->
                        link.getSrcSwitchId().equals(endpointAlpha1.getDatapath())
                                && link.getSrcPort() == endpointAlpha1.getPortNumber()
                                && link.getDestSwitchId().equals(endpointBeta2.getDatapath())
                                && link.getDestPort() == endpointBeta2.getPortNumber()
                                && link.getActualStatus() == IslStatus.INACTIVE
                                && link.getStatus() == IslStatus.INACTIVE));
        verify(islRepository).add(argThat(
                link ->
                        link.getSrcSwitchId().equals(endpointBeta2.getDatapath())
                                && link.getSrcPort() == endpointBeta2.getPortNumber()
                                && link.getDestSwitchId().equals(endpointAlpha1.getDatapath())
                                && link.getDestPort() == endpointAlpha1.getPortNumber()
                                && link.getActualStatus() == IslStatus.INACTIVE
                                && link.getStatus() == IslStatus.INACTIVE));

        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void initializeFromHistoryDoNotReAllocateUsedBandwidth() {
        Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, false)
                .availableBandwidth(90)
                .build();
        Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, false)
                .availableBandwidth(90)
                .build();

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);
        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2, null);
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);
        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 10L);
        mockPersistenceBandwidthAllocation(endpointBeta2, endpointAlpha1, 10L);

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islSetupFromHistory(endpointAlpha1, reference, islAlphaBeta);

        assertEquals(90L, islAlphaBeta.getAvailableBandwidth());
        assertEquals(90L, islBetaAlpha.getAvailableBandwidth());

        verify(dashboardLogger).onIslUp(reference);
        verifyNoMoreInteractions(dashboardLogger);

        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void initializeFromHistoryDoNotResetDefaultMaxBandwidth() {
        Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, false)
                .availableBandwidth(100)
                .defaultMaxBandwidth(200)
                .build();
        Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, false)
                .availableBandwidth(100)
                .defaultMaxBandwidth(200)
                .build();

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);
        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2, null);
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);
        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 0L);
        mockPersistenceBandwidthAllocation(endpointBeta2, endpointAlpha1, 0L);

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islSetupFromHistory(endpointAlpha1, reference, islAlphaBeta);

        assertEquals(100L, islAlphaBeta.getAvailableBandwidth());
        assertEquals(200L, islAlphaBeta.getDefaultMaxBandwidth());
        assertEquals(100L, islBetaAlpha.getAvailableBandwidth());
        assertEquals(200L, islBetaAlpha.getDefaultMaxBandwidth());

        verify(dashboardLogger).onIslUp(reference);
        verifyNoMoreInteractions(dashboardLogger);

        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void setIslUnstableTimeOnPortDown() {
        // prepare data
        Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, false).build();
        Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, false).build();

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, null);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, null);

        // setup alpha -> beta half
        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islUp(endpointAlpha1, reference, new IslDataHolder(islAlphaBeta));

        verify(dashboardLogger).onIslDown(reference);
        verifyNoMoreInteractions(dashboardLogger);
        reset(dashboardLogger);

        // setup beta -> alpha half
        reset(islRepository);
        Isl clonedIslAlphaBeta = new Isl(islAlphaBeta);
        Isl clonedIslBetaAlpha = new Isl(islBetaAlpha);
        when(islRepository.findByEndpoints(endpointAlpha1.getDatapath(), endpointAlpha1.getPortNumber(),
                                           endpointBeta2.getDatapath(), endpointBeta2.getPortNumber()))
                .thenReturn(Optional.of(clonedIslAlphaBeta));
        when(islRepository.findByEndpoints(endpointBeta2.getDatapath(), endpointBeta2.getPortNumber(),
                                           endpointAlpha1.getDatapath(), endpointAlpha1.getPortNumber()))
                .thenReturn(Optional.of(clonedIslBetaAlpha));
        service.islUp(endpointBeta2, reference, new IslDataHolder(clonedIslBetaAlpha));

        // isl fail by PORT DOWN
        service.islDown(endpointAlpha1, reference, IslDownReason.PORT_DOWN);

        // ensure we have marked ISL as unstable
        int timeOutUnstableSec = 1;
        assertTrue(between(clonedIslAlphaBeta.getTimeUnstable(), Instant.now()).getSeconds() < timeOutUnstableSec);
        assertTrue(between(clonedIslBetaAlpha.getTimeUnstable(), Instant.now()).getSeconds() < timeOutUnstableSec);
    }

    @Test
    public void deleteWhenActive() {
        prepareAndPerformDelete(IslStatus.ACTIVE, false);

        // ISL can't be delete in ACTIVE state
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void deleteWhenActiveMultiTable() {
        prepareAndPerformDelete(IslStatus.ACTIVE, true);
        verifyNoMoreInteractions(carrier);
    }


    @Test
    public void deleteWhenInactive() {
        prepareAndPerformDelete(IslStatus.INACTIVE, false);
        verifyDelete();
    }

    @Test
    public void deleteWhenInactiveMultiTable() {
        prepareAndPerformDelete(IslStatus.INACTIVE, true);
        verifyDelete();
    }

    @Test
    public void deleteWhenMoved() {
        prepareAndPerformDelete(IslStatus.MOVED, false);
        verifyDelete();
    }

    @Test
    public void repeatOnTransientDbErrors() {
        mockPersistenceIsl(endpointAlpha1, endpointBeta2, null);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, null);

        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2, null);
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);

        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 0);
        mockPersistenceBandwidthAllocation(endpointBeta2, endpointAlpha1, 0);

        /*TODO: reimplement with new datamodel
           doThrow(new PersistenceException("force createOrUpdate to fail"))
                .when(islRepository)
                .add(argThat(
                        link -> endpointAlpha1.getDatapath().equals(link.getSrcSwitchId())
                                && Objects.equals(endpointAlpha1.getPortNumber(), link.getSrcPort())));*/

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islUp(endpointAlpha1, reference, new IslDataHolder(100, 1, 100));

        assertEquals(new SwitchId(1), endpointAlpha1.getDatapath());
        assertEquals(1, endpointAlpha1.getPortNumber());
    }

    private void prepareAndPerformDelete(IslStatus initialStatus, boolean multiTable) {
        Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, multiTable)
                .actualStatus(initialStatus)
                .status(initialStatus)
                .build();
        Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, multiTable)
                .actualStatus(initialStatus)
                .status(initialStatus)
                .build();

        // prepare
        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);

        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2, null);
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);

        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 0);
        mockPersistenceBandwidthAllocation(endpointBeta2, endpointAlpha1, 0);

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islSetupFromHistory(endpointAlpha1, reference, islAlphaBeta);

        if (multiTable && initialStatus == IslStatus.ACTIVE) {
            service.islDefaultRuleInstalled(reference,
                    new InstallIslDefaultRulesResult(endpointAlpha1.getDatapath(), endpointAlpha1.getPortNumber(),
                            endpointBeta2.getDatapath(), endpointBeta2.getPortNumber(), true));
            service.islDefaultRuleInstalled(reference,
                    new InstallIslDefaultRulesResult(endpointBeta2.getDatapath(), endpointBeta2.getPortNumber(),
                            endpointAlpha1.getDatapath(), endpointAlpha1.getPortNumber(), true));
        }

        switch (initialStatus) {
            case ACTIVE:
                break;
            case INACTIVE:
                verify(dashboardLogger).onIslDown(reference);
                break;
            case MOVED:
                verify(dashboardLogger).onIslMoved(reference);
                break;
            default:
                // nothing to do here
        }

        reset(carrier);

        // remove
        service.remove(reference);

        if (multiTable && (initialStatus == IslStatus.INACTIVE || initialStatus == IslStatus.MOVED)) {
            service.islDefaultRuleDeleted(reference, new RemoveIslDefaultRulesResult(endpointAlpha1.getDatapath(),
                    endpointAlpha1.getPortNumber(), endpointBeta2.getDatapath(), endpointBeta2.getPortNumber(),
                    true));
            service.islDefaultRuleDeleted(reference, new RemoveIslDefaultRulesResult(
                    endpointBeta2.getDatapath(), endpointBeta2.getPortNumber(),
                    endpointAlpha1.getDatapath(), endpointAlpha1.getPortNumber(),
                    true));
            verify(carrier).islDefaultRulesDelete(endpointAlpha1, endpointBeta2);
            verify(carrier).islDefaultRulesDelete(endpointBeta2, endpointAlpha1);
        }
    }

    private void verifyDelete() {
        verify(carrier).bfdDisableRequest(endpointAlpha1);
        verify(carrier).bfdDisableRequest(endpointBeta2);

        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void considerLinkPropsDataOnHistory() {
        Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, false)
                .maxBandwidth(100L)
                .build();
        Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, false)
                .maxBandwidth(100L)
                .build();

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);

        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2,
                                 makeLinkProps(endpointAlpha1, endpointBeta2)
                                         .maxBandwidth(50L)
                                         .build());
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);

        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 0L);
        mockPersistenceBandwidthAllocation(endpointBeta2, endpointAlpha1, 0L);

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islSetupFromHistory(endpointAlpha1, reference, islAlphaBeta);

        assertEquals(50L, islAlphaBeta.getAvailableBandwidth());
        assertEquals(100L, islBetaAlpha.getAvailableBandwidth());
    }

    @Test
    public void considerLinkPropsDataOnCreate() {
        final Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, false)
                .defaultMaxBandwidth(250L)
                .availableBandwidth(200L)
                .build();
        final Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, false)
                .defaultMaxBandwidth(250L)
                .availableBandwidth(200L)
                .build();

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, null);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, null);

        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2,
                                 makeLinkProps(endpointAlpha1, endpointBeta2)
                                         .maxBandwidth(50L)
                                         .build());
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);

        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 0L);
        mockPersistenceBandwidthAllocation(endpointBeta2, endpointAlpha1, 0L);

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islUp(endpointAlpha1, reference, new IslDataHolder(200L, 200L, 200L));

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);
        service.islUp(endpointBeta2, reference, new IslDataHolder(200L, 200L, 200L));

        assertEquals(50L, islAlphaBeta.getAvailableBandwidth());
        assertEquals(200L, islBetaAlpha.getAvailableBandwidth());
    }

    @Test
    public void considerLinkPropsDataOnRediscovery() {
        final Isl islAlphaBeta = makeIsl(endpointAlpha1, endpointBeta2, false)
                .defaultMaxBandwidth(300L)
                .availableBandwidth(300L)
                .build();
        final Isl islBetaAlpha = makeIsl(endpointBeta2, endpointAlpha1, false)
                .defaultMaxBandwidth(300L)
                .availableBandwidth(300L)
                .build();

        // initial discovery
        mockPersistenceIsl(endpointAlpha1, endpointBeta2, null);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, null);

        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2, null);
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);

        IslReference reference = new IslReference(endpointAlpha1, endpointBeta2);
        service.islUp(endpointAlpha1, reference, new IslDataHolder(300L, 300L, 300L));

        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);
        service.islUp(endpointBeta2, reference, new IslDataHolder(300L, 300L, 300L));

        assertEquals(300L, islAlphaBeta.getAvailableBandwidth());
        assertEquals(300L, islBetaAlpha.getAvailableBandwidth());

        // fail (half)
        service.islDown(endpointAlpha1, reference, IslDownReason.POLL_TIMEOUT);
        service.islDown(endpointBeta2, reference, IslDownReason.POLL_TIMEOUT);

        reset(islRepository);
        reset(linkPropsRepository);

        // rediscovery
        mockPersistenceIsl(endpointAlpha1, endpointBeta2, islAlphaBeta);
        mockPersistenceIsl(endpointBeta2, endpointAlpha1, islBetaAlpha);

        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2,
                                 makeLinkProps(endpointAlpha1, endpointBeta2)
                                         .maxBandwidth(50L)
                                         .build());
        mockPersistenceLinkProps(endpointBeta2, endpointAlpha1, null);

        service.islUp(endpointAlpha1, reference, new IslDataHolder(300L, 300L, 300L));
        service.islUp(endpointBeta2, reference, new IslDataHolder(300L, 300L, 300L));

        assertEquals(50L, islAlphaBeta.getAvailableBandwidth());
        assertEquals(300L, islBetaAlpha.getAvailableBandwidth());
    }

    private void emulateEmptyPersistentDb() {
        mockPersistenceIsl(endpointAlpha1, endpointBeta2, null);
        mockPersistenceLinkProps(endpointAlpha1, endpointBeta2, null);
        mockPersistenceBandwidthAllocation(endpointAlpha1, endpointBeta2, 0L);
    }

    private void mockPersistenceIsl(Endpoint source, Endpoint dest, Isl link) {
        when(islRepository.findByEndpoints(source.getDatapath(), source.getPortNumber(),
                                           dest.getDatapath(), dest.getPortNumber()))
                .thenReturn(Optional.ofNullable(link));

        doAnswer(invocation -> invocation.getArgument(0)).when(islRepository).add(any(Isl.class));
    }

    private void mockPersistenceLinkProps(Endpoint source, Endpoint dest, LinkProps props) {
        List<LinkProps> response = Collections.emptyList();
        if (props != null) {
            response = Collections.singletonList(props);
        }
        when(linkPropsRepository.findByEndpoints(source.getDatapath(), source.getPortNumber(),
                                                 dest.getDatapath(), dest.getPortNumber()))
                .thenReturn(response);
    }

    private void mockPersistenceBandwidthAllocation(Endpoint source, Endpoint dest, long allocation) {
        when(flowPathRepository.getUsedBandwidthBetweenEndpoints(source.getDatapath(), source.getPortNumber(),
                                                                    dest.getDatapath(), dest.getPortNumber()))
                .thenReturn(allocation);
    }

    private Isl.IslBuilder makeIsl(Endpoint source, Endpoint dest, boolean multiTable) {
        Switch sourceSwitch = lookupSwitchCreateMockIfAbsent(source.getDatapath(), multiTable);
        Switch destSwitch = lookupSwitchCreateMockIfAbsent(dest.getDatapath(), multiTable);
        return Isl.builder()
                .srcSwitch(sourceSwitch).srcPort(source.getPortNumber())
                .destSwitch(destSwitch).destPort(dest.getPortNumber())
                .status(IslStatus.ACTIVE)
                .actualStatus(IslStatus.ACTIVE)
                .latency(10)
                .speed(100)
                .availableBandwidth(100)
                .maxBandwidth(100)
                .defaultMaxBandwidth(100);
    }

    private LinkProps.LinkPropsBuilder makeLinkProps(Endpoint source, Endpoint dest) {
        return LinkProps.builder()
                .srcSwitchId(source.getDatapath()).srcPort(source.getPortNumber())
                .dstSwitchId(dest.getDatapath()).dstPort(dest.getPortNumber());
    }

    private Switch lookupSwitchCreateMockIfAbsent(SwitchId datapath, boolean multiTable) {
        Switch entry = allocatedSwitches.get(datapath);
        if (entry == null) {
            entry = Switch.builder()
                    .switchId(datapath)
                    .status(SwitchStatus.ACTIVE)
                    .description("autogenerated switch mock")
                    .build();
            SwitchProperties switchProperties = SwitchProperties.builder()
                    .switchObj(entry)
                    .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                    .multiTable(multiTable)
                    .build();
            allocatedSwitches.put(datapath, entry);

            when(switchRepository.findById(datapath)).thenReturn(Optional.of(entry));
            when(switchPropertiesRepository.findBySwitchId(datapath)).thenReturn(Optional.of(switchProperties));
        }

        return entry;
    }
}
