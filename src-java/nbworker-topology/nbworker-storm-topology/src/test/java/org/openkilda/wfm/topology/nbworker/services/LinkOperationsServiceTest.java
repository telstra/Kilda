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

package org.openkilda.wfm.topology.nbworker.services;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import org.openkilda.model.BfdProperties;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IllegalIslStateException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.share.mappers.IslMapper;
import org.openkilda.wfm.share.model.Endpoint;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class LinkOperationsServiceTest extends Neo4jBasedTest {
    private static IslRepository islRepository;
    private static SwitchRepository switchRepository;
    private static FlowRepository flowRepository;
    private static FlowPathRepository flowPathRepository;
    private static PathSegmentRepository pathSegmentRepository;
    private static LinkOperationsService linkOperationsService;

    private static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    private static final int TEST_SWITCH_A_PORT = 1;
    private static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);
    private static final int TEST_SWITCH_B_PORT = 1;

    private final BfdProperties defaultBfdProperties = BfdProperties.builder()
            .interval(Duration.ofMillis(350))
            .multiplier((short) 3)
            .build();

    @Mock
    private ILinkOperationsServiceCarrier carrier;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void setUpOnce() {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        islRepository = repositoryFactory.createIslRepository();
        switchRepository = repositoryFactory.createSwitchRepository();
        flowRepository = repositoryFactory.createFlowRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        pathSegmentRepository = repositoryFactory.createPathSegmentRepository();
    }

    @Before
    public void setUp() {
        linkOperationsService = new LinkOperationsService(carrier, persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager());
    }

    @Test
    public void shouldUpdateLinkUnderMaintenanceFlag() throws IslNotFoundException {
        createIsl(IslStatus.ACTIVE);

        for (int i = 0; i < 2; i++) {
            List<Isl> link = linkOperationsService
                    .updateLinkUnderMaintenanceFlag(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT,
                            TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, true);
            assertEquals(2, link.size());
            assertTrue(link.get(0).isUnderMaintenance());
            assertTrue(link.get(1).isUnderMaintenance());
        }

        for (int i = 0; i < 2; i++) {
            List<Isl> link = linkOperationsService
                    .updateLinkUnderMaintenanceFlag(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT,
                            TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, false);
            assertEquals(2, link.size());
            assertFalse(link.get(0).isUnderMaintenance());
            assertFalse(link.get(1).isUnderMaintenance());
        }

    }

    @Test
    public void shouldDeleteInactiveIsl() throws IslNotFoundException, IllegalIslStateException {
        createIsl(IslStatus.INACTIVE);
        linkOperationsService
                .deleteIsl(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, false);

        assertTrue(islRepository.findAll().isEmpty());
    }

    @Test(expected = IllegalIslStateException.class)
    public void shouldNotDeleteActiveIsl() throws IslNotFoundException, IllegalIslStateException {
        createIsl(IslStatus.ACTIVE);
        linkOperationsService
                .deleteIsl(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, false);
    }

    @Test(expected = IllegalIslStateException.class)
    public void shouldNotDeleteBusyIsl() throws IslNotFoundException, IllegalIslStateException {
        createIsl(IslStatus.INACTIVE);
        createFlow("test_flow_id", TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT);
        linkOperationsService
                .deleteIsl(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, false);
    }

    @Test
    public void shouldForceDeleteActiveIsl() throws IslNotFoundException, IllegalIslStateException {
        createIsl(IslStatus.ACTIVE);
        linkOperationsService
                .deleteIsl(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, true);

        assertTrue(islRepository.findAll().isEmpty());
    }

    @Test
    public void shouldForceDeleteBusyIsl() throws IslNotFoundException, IllegalIslStateException {
        createIsl(IslStatus.INACTIVE);
        createFlow("test_flow_id", TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT);
        linkOperationsService
                .deleteIsl(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, true);

        assertTrue(islRepository.findAll().isEmpty());
    }

    @Test
    public void shouldPropagateBfdEnableFlagUpdateOnFullUpdate() throws IslNotFoundException {
        createIsl(IslStatus.ACTIVE);

        Endpoint source = Endpoint.of(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT);
        Endpoint destination = Endpoint.of(TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT);
        linkOperationsService.writeBfdProperties(source, destination, defaultBfdProperties);

        verifyBfdProperties(defaultBfdProperties);
        verify(carrier, times(1)).islBfdPropertiesChanged(eq(source), eq(destination));

        verifyNoMoreInteractions(carrier);

        reset(carrier);

        // no propagation on subsequent request
        linkOperationsService.writeBfdProperties(source, destination, defaultBfdProperties);
        verifyZeroInteractions(carrier);
    }

    @Test
    public void shouldPropagateBfdEnableFlagUpdateOnPartialUpdate() throws IslNotFoundException {
        createIsl(IslStatus.ACTIVE);

        islRepository.findByEndpoints(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT, TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT)
                .ifPresent(isl -> {
                    isl.setEnableBfd(true);
                    islRepository.createOrUpdate(isl);
                });

        Endpoint source = Endpoint.of(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT);
        Endpoint destination = Endpoint.of(TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT);
        linkOperationsService.writeBfdProperties(source, destination, defaultBfdProperties);

        verifyBfdProperties(defaultBfdProperties);
        verify(carrier, times(1)).islBfdPropertiesChanged(eq(source), eq(destination));

        verifyNoMoreInteractions(carrier);
    }

    @Test(expected = IslNotFoundException.class)
    public void shouldFailIfThereIsNoIslForBfdEnableFlagUpdateRequest() throws IslNotFoundException {
        linkOperationsService.writeBfdProperties(
                Endpoint.of(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT),
                Endpoint.of(TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT), defaultBfdProperties);
    }

    @Test(expected = IslNotFoundException.class)
    public void shouldFailIfThereIsUniIslOnlyForBfdEnableFlagUpdateRequest() throws IslNotFoundException {
        Isl isl = new Isl();
        isl.setSrcSwitch(createSwitchIfNotExist(TEST_SWITCH_A_ID));
        isl.setSrcPort(TEST_SWITCH_A_PORT);
        isl.setDestSwitch(createSwitchIfNotExist(TEST_SWITCH_B_ID));
        isl.setDestPort(TEST_SWITCH_B_PORT);
        isl.setCost(0);
        isl.setStatus(IslStatus.ACTIVE);
        islRepository.createOrUpdate(isl);

        islRepository.createOrUpdate(isl);
        linkOperationsService.writeBfdProperties(
                Endpoint.of(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT),
                Endpoint.of(TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT), defaultBfdProperties);
    }

    private void verifyBfdProperties(BfdProperties expectedValue) {
        Endpoint source = Endpoint.of(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT);
        Endpoint destination = Endpoint.of(TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT);
        verifyBfdProperties(source, destination, expectedValue);
        verifyBfdProperties(destination, source, expectedValue);
    }

    private void verifyBfdProperties(Endpoint leftEnd, Endpoint rightEnd, BfdProperties expectedValue) {
        Optional<Isl> potentialIsl = islRepository.findByEndpoints(
                leftEnd.getDatapath(), leftEnd.getPortNumber(), rightEnd.getDatapath(), rightEnd.getPortNumber());
        Assert.assertTrue(potentialIsl.isPresent());
        BfdProperties actualValue = IslMapper.INSTANCE.readBfdProperties(potentialIsl.get());
        Assert.assertEquals(expectedValue, actualValue);
    }

    private void createIsl(IslStatus status) {
        Isl isl = new Isl();
        isl.setSrcSwitch(createSwitchIfNotExist(TEST_SWITCH_A_ID));
        isl.setSrcPort(TEST_SWITCH_A_PORT);
        isl.setDestSwitch(createSwitchIfNotExist(TEST_SWITCH_B_ID));
        isl.setDestPort(TEST_SWITCH_B_PORT);
        isl.setCost(0);
        isl.setStatus(status);

        islRepository.createOrUpdate(isl);

        isl = new Isl();
        isl.setSrcSwitch(createSwitchIfNotExist(TEST_SWITCH_B_ID));
        isl.setSrcPort(TEST_SWITCH_B_PORT);
        isl.setDestSwitch(createSwitchIfNotExist(TEST_SWITCH_A_ID));
        isl.setDestPort(TEST_SWITCH_A_PORT);
        isl.setCost(0);
        isl.setStatus(status);

        islRepository.createOrUpdate(isl);
    }

    private Switch createSwitchIfNotExist(SwitchId switchId) {
        return switchRepository.findById(switchId).orElseGet(() -> {
            Switch sw = Switch.builder().switchId(switchId).status(SwitchStatus.ACTIVE).build();
            switchRepository.createOrUpdate(sw);
            return sw;
        });
    }

    private Flow createFlow(String flowId, SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        Switch srcSwitch = createSwitchIfNotExist(srcSwitchId);
        Switch dstSwitch = createSwitchIfNotExist(dstSwitchId);
        Flow flow = Flow.builder()
                .flowId(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .status(FlowStatus.UP)
                .build();


        FlowPath forwardPath = FlowPath.builder()
                .flow(flow)
                .pathId(new PathId("forward_path_id"))
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .cookie(new FlowSegmentCookie(1L))
                .build();

        FlowPath reversePath = FlowPath.builder()
                .flow(flow)
                .pathId(new PathId("reverse_path_id"))
                .srcSwitch(dstSwitch)
                .destSwitch(srcSwitch)
                .cookie(new FlowSegmentCookie(1L))
                .build();

        forwardPath.setSegments(newArrayList(createPathSegment(srcSwitch, srcPort, dstSwitch, dstPort)));
        reversePath.setSegments(newArrayList(createPathSegment(dstSwitch, dstPort, srcSwitch, srcPort)));

        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);
        flowRepository.createOrUpdate(flow);
        flowPathRepository.createOrUpdate(forwardPath);
        flowPathRepository.createOrUpdate(reversePath);

        return flow;
    }

    private PathSegment createPathSegment(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort) {
        PathSegment pathSegment = PathSegment.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .build();
        pathSegmentRepository.createOrUpdate(pathSegment);
        return pathSegment;
    }
}
