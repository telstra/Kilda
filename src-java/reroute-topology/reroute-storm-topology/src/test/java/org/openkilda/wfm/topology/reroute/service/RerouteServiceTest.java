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

package org.openkilda.wfm.topology.reroute.service;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.command.reroute.RerouteInactiveFlows;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.info.reroute.SwitchStateChanged;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.EntityNotFoundException;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.tx.TransactionCallbackWithoutResult;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.topology.reroute.bolts.MessageSender;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class RerouteServiceTest {

    private static final SwitchId SWITCH_ID_A = new SwitchId(1L);
    private static final Switch SWITCH_A = Switch.builder().switchId(SWITCH_ID_A).build();
    private static final SwitchId SWITCH_ID_B = new SwitchId(2L);
    private static final Switch SWITCH_B = Switch.builder().switchId(SWITCH_ID_B).build();
    private static final SwitchId SWITCH_ID_C = new SwitchId(3L);
    private static final Switch SWITCH_C = Switch.builder().switchId(SWITCH_ID_C).build();
    private static final int PORT = 1;

    private static final PathNode PATH_NODE = new PathNode(SWITCH_ID_A, PORT, 1);
    private static final String REASON = "REASON";
    private static final RerouteAffectedFlows REROUTE_AFFECTED_FLOWS_COMMAND = new RerouteAffectedFlows(PATH_NODE,
            REASON);

    private static final RerouteInactiveFlows REROUTE_INACTIVE_FLOWS_COMMAND = new RerouteInactiveFlows(PATH_NODE,
            REASON);

    private static final String FLOW_ID = "TEST_FLOW";
    private static final String CORRELATION_ID = "CORRELATION_ID";

    private Flow regularFlow;
    private Flow pinnedFlow;
    private Flow oneSwitchFlow;
    @Mock
    private TransactionManager transactionManager;

    @Mock
    MessageSender carrier;

    @Before
    public void setup() throws Throwable {
        doAnswer(invocation -> {
            TransactionCallbackWithoutResult<?> arg = invocation.getArgument(0);
            arg.doInTransaction();
            return null;
        }).when(transactionManager).doInTransaction(Mockito.<TransactionCallbackWithoutResult<?>>any());

        pinnedFlow = Flow.builder().flowId(FLOW_ID).srcSwitch(SWITCH_A)
                .destSwitch(SWITCH_C).pinned(true).build();
        FlowPath pinnedFlowForwardPath = FlowPath.builder()
                .pathId(new PathId("1"))
                .srcSwitch(SWITCH_A).destSwitch(SWITCH_C)
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 1))
                .build();
        List<PathSegment> pinnedFlowForwardSegments = new ArrayList<>();
        pinnedFlowForwardSegments.add(PathSegment.builder()
                .pathId(pinnedFlowForwardPath.getPathId())
                .srcSwitch(SWITCH_A)
                .srcPort(1)
                .destSwitch(SWITCH_B)
                .destPort(1)
                .build());
        pinnedFlowForwardSegments.add(PathSegment.builder()
                .pathId(pinnedFlowForwardPath.getPathId())
                .srcSwitch(SWITCH_B)
                .srcPort(2)
                .destSwitch(SWITCH_C)
                .destPort(1)
                .build());
        pinnedFlowForwardPath.setSegments(pinnedFlowForwardSegments);

        FlowPath pinnedFlowReversePath = FlowPath.builder().pathId(new PathId("2"))
                .srcSwitch(SWITCH_C).destSwitch(SWITCH_A)
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 2))
                .build();
        List<PathSegment> pinnedFlowReverseSegments = new ArrayList<>();
        pinnedFlowReverseSegments.add(PathSegment.builder()
                .pathId(pinnedFlowReversePath.getPathId())
                .srcSwitch(SWITCH_C)
                .srcPort(1)
                .destSwitch(SWITCH_B)
                .destPort(2)
                .build());
        pinnedFlowReverseSegments.add(PathSegment.builder()
                .pathId(pinnedFlowReversePath.getPathId())
                .srcSwitch(SWITCH_B)
                .srcPort(1)
                .destSwitch(SWITCH_A)
                .destPort(1)
                .build());
        pinnedFlowReversePath.setSegments(pinnedFlowReverseSegments);
        pinnedFlow.setForwardPath(pinnedFlowForwardPath);
        pinnedFlow.setReversePath(pinnedFlowReversePath);

        regularFlow = Flow.builder().flowId(FLOW_ID).srcSwitch(SWITCH_A)
                .destSwitch(SWITCH_C).pinned(false)
                .priority(2)
                .build();
        FlowPath regularFlowForwardPath = FlowPath.builder().pathId(new PathId("3"))
                .srcSwitch(SWITCH_A).destSwitch(SWITCH_C)
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 3))
                .status(FlowPathStatus.ACTIVE)
                .build();
        List<PathSegment> unpinnedFlowForwardSegments = new ArrayList<>();
        unpinnedFlowForwardSegments.add(PathSegment.builder()
                .pathId(regularFlowForwardPath.getPathId())
                .srcSwitch(SWITCH_A)
                .srcPort(1)
                .destSwitch(SWITCH_B)
                .destPort(1)
                .build());
        unpinnedFlowForwardSegments.add(PathSegment.builder()
                .pathId(regularFlowForwardPath.getPathId())
                .srcSwitch(SWITCH_B)
                .srcPort(2)
                .destSwitch(SWITCH_C)
                .destPort(1)
                .build());
        regularFlowForwardPath.setSegments(unpinnedFlowForwardSegments);

        FlowPath regularFlowReversePath = FlowPath.builder().pathId(new PathId("4"))
                .srcSwitch(SWITCH_C).destSwitch(SWITCH_A)
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 3))
                .status(FlowPathStatus.ACTIVE)
                .build();
        List<PathSegment> unpinnedFlowReverseSegments = new ArrayList<>();
        unpinnedFlowReverseSegments.add(PathSegment.builder()
                .pathId(regularFlowReversePath.getPathId())
                .srcSwitch(SWITCH_C)
                .srcPort(1)
                .destSwitch(SWITCH_B)
                .destPort(2)
                .build());
        unpinnedFlowReverseSegments.add(PathSegment.builder()
                .pathId(regularFlowReversePath.getPathId())
                .srcSwitch(SWITCH_B)
                .srcPort(1)
                .destSwitch(SWITCH_A)
                .destPort(1)
                .build());
        regularFlowReversePath.setSegments(unpinnedFlowReverseSegments);
        regularFlow.setForwardPath(regularFlowForwardPath);
        regularFlow.setReversePath(regularFlowReversePath);

        oneSwitchFlow = Flow.builder().flowId(FLOW_ID).srcSwitch(SWITCH_A)
                .destSwitch(SWITCH_A)
                .build();
        FlowPath oneSwitchFlowForwardPath = FlowPath.builder().pathId(new PathId("5"))
                .srcSwitch(SWITCH_A).destSwitch(SWITCH_A)
                .cookie(new FlowSegmentCookie(FlowPathDirection.FORWARD, 4))
                .status(FlowPathStatus.ACTIVE)
                .build();
        FlowPath oneSwitchFlowReversePath = FlowPath.builder().pathId(new PathId("6"))
                .srcSwitch(SWITCH_A).destSwitch(SWITCH_A)
                .cookie(new FlowSegmentCookie(FlowPathDirection.REVERSE, 4))
                .status(FlowPathStatus.ACTIVE)
                .build();
        oneSwitchFlow.setForwardPath(oneSwitchFlowForwardPath);
        oneSwitchFlow.setReversePath(oneSwitchFlowReversePath);
    }


    @Test
    public void testRerouteInactivePinnedFlowsOneFailedSegment() {
        pinnedFlow.setStatus(FlowStatus.DOWN);
        for (FlowPath flowPath : pinnedFlow.getPaths()) {
            flowPath.setStatus(FlowPathStatus.INACTIVE);
            for (PathSegment pathSegment : flowPath.getSegments()) {
                if (pathSegment.containsNode(SWITCH_ID_A, PORT)) {
                    pathSegment.setFailed(true);
                }
            }
        }
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        FlowRepository flowRepository = mock(FlowRepository.class);
        when(flowRepository.findInactiveFlows())
                .thenReturn(Collections.singletonList(pinnedFlow));
        doAnswer(invocation -> {
            FlowStatus status = invocation.getArgument(1);
            pinnedFlow.setStatus(status);
            return null;
        }).when(flowRepository).updateStatusSafe(eq(pinnedFlow), any(), any());
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        FlowPathRepository pathRepository = mock(FlowPathRepository.class);
        when(repositoryFactory.createFlowPathRepository()).thenReturn(pathRepository);
        PathSegmentRepository pathSegmentRepository = mock(PathSegmentRepository.class);
        when(repositoryFactory.createPathSegmentRepository()).thenReturn(pathSegmentRepository);
        MessageSender messageSender = mock(MessageSender.class);
        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        TransactionManager transactionManager = mock(TransactionManager.class);
        doAnswer(invocation -> {
            TransactionCallbackWithoutResult arg = invocation.getArgument(0);
            arg.doInTransaction();
            return null;
        }).when(transactionManager).doInTransaction(Mockito.<TransactionCallbackWithoutResult>any());
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);
        RerouteService rerouteService = new RerouteService(persistenceManager);
        rerouteService.rerouteInactiveFlows(messageSender, CORRELATION_ID,
                REROUTE_INACTIVE_FLOWS_COMMAND);
        assertEquals(FlowStatus.UP, pinnedFlow.getStatus());
        for (FlowPath fp : pinnedFlow.getPaths()) {
            assertEquals(FlowPathStatus.ACTIVE, fp.getStatus());
            for (PathSegment ps : fp.getSegments()) {
                if (ps.containsNode(SWITCH_ID_A, PORT)) {
                    assertFalse(ps.isFailed());
                }
            }
        }
    }

    @Test
    public void testRerouteInactivePinnedFlowsTwoFailedSegments() {
        pinnedFlow.setStatus(FlowStatus.DOWN);
        for (FlowPath flowPath : pinnedFlow.getPaths()) {
            flowPath.setStatus(FlowPathStatus.INACTIVE);
            for (PathSegment pathSegment : flowPath.getSegments()) {
                pathSegment.setFailed(true);
            }
        }
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        FlowRepository flowRepository = mock(FlowRepository.class);
        when(flowRepository.findInactiveFlows())
                .thenReturn(Collections.singletonList(pinnedFlow));
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        FlowPathRepository pathRepository = mock(FlowPathRepository.class);
        when(repositoryFactory.createFlowPathRepository()).thenReturn(pathRepository);
        PathSegmentRepository pathSegmentRepository = mock(PathSegmentRepository.class);
        when(repositoryFactory.createPathSegmentRepository()).thenReturn(pathSegmentRepository);
        MessageSender messageSender = mock(MessageSender.class);
        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);
        RerouteService rerouteService = new RerouteService(persistenceManager);
        rerouteService.rerouteInactiveFlows(messageSender, CORRELATION_ID,
                REROUTE_INACTIVE_FLOWS_COMMAND);

        verify(pathRepository, times(0)).updateStatus(any(), any());
        assertTrue(FlowStatus.DOWN.equals(pinnedFlow.getStatus()));
        for (FlowPath fp : pinnedFlow.getPaths()) {
            assertTrue(FlowPathStatus.INACTIVE.equals(fp.getStatus()));
            for (PathSegment ps : fp.getSegments()) {
                if (ps.containsNode(SWITCH_ID_A, PORT)) {
                    assertFalse(ps.isFailed());
                } else {
                    assertTrue(ps.isFailed());
                }
            }
        }
    }

    @Test
    public void handlePathNoFoundException() {
        PathNode islSide = new PathNode(SWITCH_A.getSwitchId(), 1, 0);

        FlowPathRepository pathRepository = mock(FlowPathRepository.class);
        when(pathRepository.findBySegmentEndpoint(eq(islSide.getSwitchId()), eq(islSide.getPortNo())))
                .thenReturn(Arrays.asList(regularFlow.getForwardPath(), regularFlow.getReversePath()));

        FlowRepository flowRepository = mock(FlowRepository.class);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createPathSegmentRepository())
                .thenReturn(mock(PathSegmentRepository.class));
        when(repositoryFactory.createFlowPathRepository())
                .thenReturn(pathRepository);
        when(repositoryFactory.createFlowRepository())
                .thenReturn(flowRepository);

        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);

        RerouteService rerouteService = new RerouteService(persistenceManager);

        RerouteAffectedFlows request = new RerouteAffectedFlows(islSide, "dummy-reason - unittest");
        rerouteService.rerouteAffectedFlows(carrier, CORRELATION_ID, request);

        verify(flowRepository).updateStatusSafe(eq(regularFlow), eq(FlowStatus.DOWN), any());
        FlowThrottlingData expected = FlowThrottlingData.builder()
                .correlationId(CORRELATION_ID)
                .priority(regularFlow.getPriority())
                .timeCreate(regularFlow.getTimeCreate())
                .affectedIsl(Collections.singleton(new IslEndpoint(islSide.getSwitchId(), islSide.getPortNo())))
                .force(false)
                .effectivelyDown(true)
                .reason(request.getReason())
                .build();
        verify(carrier).emitRerouteCommand(eq(regularFlow.getFlowId()), eq(expected));
    }

    @Test
    public void handleUpdateSingleSwitchFlows() {
        FlowRepository flowRepository = mock(FlowRepository.class);
        when(flowRepository.findOneSwitchFlows(oneSwitchFlow.getSrcSwitch().getSwitchId()))
                .thenReturn(Arrays.asList(oneSwitchFlow));
        FlowPathRepository flowPathRepository = mock(FlowPathRepository.class);
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createFlowRepository())
                .thenReturn(flowRepository);
        when(repositoryFactory.createFlowPathRepository())
                .thenReturn(flowPathRepository);

        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);

        RerouteService rerouteService = new RerouteService(persistenceManager);

        rerouteService.processSingleSwitchFlowStatusUpdate(
                new SwitchStateChanged(oneSwitchFlow.getSrcSwitchId(), SwitchStatus.INACTIVE));

        assertEquals(format("Switch %s is inactive", oneSwitchFlow.getSrcSwitchId()),
                FlowStatus.DOWN, oneSwitchFlow.getStatus());
    }

    @Test
    public void shouldSkipRerouteRequestsForFlowWithoutAffectedPathSegment() {
        PathNode islSide = new PathNode(SWITCH_A.getSwitchId(), 1, 0);

        FlowPathRepository pathRepository = mock(FlowPathRepository.class);
        when(pathRepository.findBySegmentEndpoint(eq(islSide.getSwitchId()), eq(islSide.getPortNo())))
                .thenReturn(Arrays.asList(regularFlow.getForwardPath(), regularFlow.getReversePath()));

        FlowRepository flowRepository = mock(FlowRepository.class);

        PathSegmentRepository pathSegmentRepository = mock(PathSegmentRepository.class);
        doThrow(new EntityNotFoundException("Not found"))
                .when(pathSegmentRepository).updateFailedStatus(any(), any(), anyBoolean());

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createPathSegmentRepository())
                .thenReturn(pathSegmentRepository);
        when(repositoryFactory.createFlowPathRepository())
                .thenReturn(pathRepository);
        when(repositoryFactory.createFlowRepository())
                .thenReturn(flowRepository);

        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);

        RerouteService rerouteService = new RerouteService(persistenceManager);

        RerouteAffectedFlows request = new RerouteAffectedFlows(islSide, "dummy-reason - unittest");
        rerouteService.rerouteAffectedFlows(carrier, CORRELATION_ID, request);

        verifyZeroInteractions(carrier);
    }

    @Test
    public void handleRerouteInactiveAffectedFlows() {
        FlowPathRepository pathRepository = mock(FlowPathRepository.class);
        when(pathRepository.findInactiveBySegmentSwitch(regularFlow.getSrcSwitchId()))
                .thenReturn(Arrays.asList(regularFlow.getForwardPath(), regularFlow.getReversePath()));

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createFlowPathRepository())
                .thenReturn(pathRepository);

        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);

        RerouteService rerouteService = new RerouteService(persistenceManager);

        regularFlow.setStatus(FlowStatus.DOWN);
        rerouteService.rerouteInactiveAffectedFlows(carrier, CORRELATION_ID, regularFlow.getSrcSwitchId());

        FlowThrottlingData expected = FlowThrottlingData.builder()
                .correlationId(CORRELATION_ID)
                .priority(regularFlow.getPriority())
                .timeCreate(regularFlow.getTimeCreate())
                .affectedIsl(Collections.emptySet())
                .force(false)
                .effectivelyDown(true)
                .reason(format("Switch '%s' online", regularFlow.getSrcSwitchId()))
                .build();
        verify(carrier).emitRerouteCommand(eq(regularFlow.getFlowId()), eq(expected));

        regularFlow.setStatus(FlowStatus.UP);
    }

    @Test
    public void processManualRerouteRequest() {
        FlowRepository flowRepository = mock(FlowRepository.class);
        when(flowRepository.findById(regularFlow.getFlowId()))
                .thenReturn(Optional.of(regularFlow));

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createFlowRepository())
                .thenReturn(flowRepository);

        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
        when(persistenceManager.getTransactionManager()).thenReturn(transactionManager);

        RerouteService rerouteService = new RerouteService(persistenceManager);

        FlowRerouteRequest request = new FlowRerouteRequest(regularFlow.getFlowId(), true, true, false,
                Collections.emptySet(), "reason");
        rerouteService.processManualRerouteRequest(carrier, CORRELATION_ID, request);

        FlowThrottlingData expected = FlowThrottlingData.builder()
                .correlationId(CORRELATION_ID)
                .priority(regularFlow.getPriority())
                .timeCreate(regularFlow.getTimeCreate())
                .affectedIsl(Collections.emptySet())
                .force(true)
                .effectivelyDown(true)
                .reason("reason")
                .build();
        verify(carrier).emitManualRerouteCommand(eq(regularFlow.getFlowId()), eq(expected));
    }
}
