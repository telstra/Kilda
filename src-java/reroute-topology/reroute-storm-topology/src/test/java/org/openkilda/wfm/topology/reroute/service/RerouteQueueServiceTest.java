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

package org.openkilda.wfm.topology.reroute.service;

import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.reroute.RerouteResultInfoData;
import org.openkilda.messaging.info.reroute.error.RerouteInProgressError;
import org.openkilda.messaging.info.reroute.error.SpeakerRequestError;
import org.openkilda.model.Flow;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData.FlowThrottlingDataBuilder;
import org.openkilda.wfm.topology.reroute.model.RerouteQueue;

import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class RerouteQueueServiceTest {

    private static final String CORRELATION_ID = "CORRELATION_ID";
    private static final SwitchId SWITCH_ID_A = new SwitchId(1L);
    private static final Switch SWITCH_A = Switch.builder().switchId(SWITCH_ID_A).build();
    private static final SwitchId SWITCH_ID_B = new SwitchId(2L);
    private static final Switch SWITCH_B = Switch.builder().switchId(SWITCH_ID_B).build();
    private static final SwitchId SWITCH_ID_C = new SwitchId(3L);
    private static final Switch SWITCH_C = Switch.builder().switchId(SWITCH_ID_C).build();
    private static String FLOW_ID = "flow id";

    private Flow flow;

    @Mock
    private IRerouteQueueCarrier carrier;
    @Mock
    private FlowRepository flowRepository;

    private RerouteQueueService rerouteQueueService;

    @Before
    public void setup() throws Throwable {
        flow = Flow.builder().flowId(FLOW_ID).srcSwitch(SWITCH_A)
                .destSwitch(SWITCH_B).priority(2).timeCreate(Instant.now())
                .build();
        when(flowRepository.findById(FLOW_ID)).thenReturn(Optional.of(flow));

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);

        PersistenceManager persistenceManager = mock(PersistenceManager.class);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        rerouteQueueService = new RerouteQueueService(carrier, persistenceManager, 0, 3);
    }

    @Test
    public void shouldSendExtendTimeWindowEventForRerouteRequestInThrottling() {
        FlowThrottlingData actual = getFlowThrottlingData(flow, CORRELATION_ID).build();
        rerouteQueueService.processAutomaticRequest(FLOW_ID, actual);

        assertEquals(1, rerouteQueueService.getReroutes().size());
        assertNotNull(rerouteQueueService.getReroutes().get(FLOW_ID));
        RerouteQueue rerouteQueue = rerouteQueueService.getReroutes().get(FLOW_ID);
        assertNull(rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        assertEquals(actual, rerouteQueue.getThrottling());
        verify(carrier).sendExtendTimeWindowEvent();
    }

    @Test
    public void shouldMergeRequestsInThrottling() {
        FlowThrottlingData first = FlowThrottlingData.builder()
                .correlationId("another")
                .priority(1)
                .timeCreate(Instant.now().plus(1, MINUTES))
                .affectedIsl(Collections.singleton(new IslEndpoint(SWITCH_ID_A, 1)))
                .force(false)
                .effectivelyDown(false)
                .reason("another reason")
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, RerouteQueue.builder().throttling(first).build());

        FlowThrottlingData actual = getFlowThrottlingData(flow, CORRELATION_ID).build();
        rerouteQueueService.processAutomaticRequest(FLOW_ID, actual);

        assertEquals(1, rerouteQueueService.getReroutes().size());
        assertNotNull(rerouteQueueService.getReroutes().get(FLOW_ID));
        RerouteQueue rerouteQueue = rerouteQueueService.getReroutes().get(FLOW_ID);
        assertNull(rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        actual.setReason(first.getReason());
        assertEquals(actual, rerouteQueue.getThrottling());
        verify(carrier).sendExtendTimeWindowEvent();
    }

    @Test
    public void shouldSendManualRerouteRequestWithoutThrottling() {
        FlowThrottlingData throttling = getFlowThrottlingData(flow, "another one").build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .throttling(throttling)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        FlowThrottlingData actual = getFlowThrottlingData(flow, CORRELATION_ID).build();
        rerouteQueueService.processManualRequest(FLOW_ID, actual);

        assertEquals(1, rerouteQueueService.getReroutes().size());
        assertEquals(actual, rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        assertEquals(throttling, rerouteQueue.getThrottling());
        FlowRerouteRequest expected = getFlowRerouteRequest(FLOW_ID, actual);
        verify(carrier).sendRerouteRequest(eq(CORRELATION_ID), eq(expected));
    }

    @Test
    public void shouldSendCorrectErrorMessageForManualRerouteRequestWithNotExistentFlow() {
        String notExistentFlowId = "notExistentFlowId";
        when(flowRepository.findById(notExistentFlowId)).thenReturn(Optional.empty());

        FlowThrottlingData actual = getFlowThrottlingData(flow, CORRELATION_ID).build();
        rerouteQueueService.processManualRequest(notExistentFlowId, actual);

        assertEquals(0, rerouteQueueService.getReroutes().size());
        verify(carrier).emitFlowRerouteError(argThat(flowNotFoundErrorData(notExistentFlowId)));
    }

    @Test
    public void shouldSendCorrectErrorMessageForManualRerouteRequestWhenAnotherRerouteIsInProgress() {
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, "another one").build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        FlowThrottlingData actual = getFlowThrottlingData(flow, CORRELATION_ID).build();
        rerouteQueueService.processManualRequest(FLOW_ID, actual);

        verify(carrier).emitFlowRerouteError(argThat(rerouteIsInProgressErrorData(FLOW_ID)));
    }

    @Test
    public void shouldSendCorrectErrorMessageForManualRerouteRequestForPinnedFlow() {
        String flowId = "test flow";
        when(flowRepository.findById(flowId)).thenReturn(Optional.of(Flow.builder().flowId(flowId).srcSwitch(SWITCH_A)
                .destSwitch(SWITCH_B).priority(2).timeCreate(Instant.now()).pinned(true)
                .build()));

        FlowThrottlingData actual = getFlowThrottlingData(flow, CORRELATION_ID).build();
        rerouteQueueService.processManualRequest(flowId, actual);

        assertEquals(0, rerouteQueueService.getReroutes().size());
        verify(carrier).emitFlowRerouteError(argThat(pinnedFlowErrorData(flowId)));
    }

    @Test
    public void shouldMovePendingToInProcessWhenReceivedSuccessfulResult() {
        String pendingCorrelationId = "pending";
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, CORRELATION_ID).build();
        FlowThrottlingData pending = getFlowThrottlingData(flow, pendingCorrelationId).build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .pending(pending)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        RerouteResultInfoData rerouteResultInfoData = RerouteResultInfoData.builder()
                .flowId(FLOW_ID)
                .success(true)
                .build();
        rerouteQueueService.processRerouteResult(rerouteResultInfoData, CORRELATION_ID);

        assertNull(rerouteQueue.getPending());
        assertNull(rerouteQueue.getThrottling());
        FlowRerouteRequest expected = getFlowRerouteRequest(FLOW_ID, pending);
        verify(carrier).sendRerouteRequest(eq(pendingCorrelationId), eq(expected));
    }

    @Test
    public void shouldInjectRetryToThrottlingWhenReceivedFailedRerouteResult() {
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, CORRELATION_ID).build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        RerouteResultInfoData rerouteResultInfoData = RerouteResultInfoData.builder()
                .flowId(FLOW_ID)
                .success(false)
                .rerouteError(new RerouteInProgressError())
                .build();
        rerouteQueueService.processRerouteResult(rerouteResultInfoData, CORRELATION_ID);

        assertNull(rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        FlowThrottlingData expected = getFlowThrottlingData(flow,
                CORRELATION_ID + " : retry #1 ignore_bw false").build();
        expected.setRetryCounter(1);
        assertEquals(expected, rerouteQueue.getThrottling());
        verify(carrier).sendExtendTimeWindowEvent();
    }

    @Test
    public void shouldNotInjectRetryWhenReceivedFailedRuleInstallResponseOnTerminatingSwitch() {
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, CORRELATION_ID).build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        RerouteResultInfoData rerouteResultInfoData = RerouteResultInfoData.builder()
                .flowId(FLOW_ID)
                .success(false)
                .rerouteError(new SpeakerRequestError("Failed to install rules",
                        Collections.singleton(SWITCH_B.getSwitchId())))
                .build();
        rerouteQueueService.processRerouteResult(rerouteResultInfoData, CORRELATION_ID);

        assertNull(rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        assertNull(rerouteQueue.getThrottling());
    }

    @Test
    public void shouldMergeAndSendRetryWithPendingRequestWhenReceivedFailedRuleInstallResponseOnTransitSwitch() {
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, CORRELATION_ID).build();
        FlowThrottlingData pending = FlowThrottlingData.builder()
                .correlationId("pending")
                .priority(7)
                .timeCreate(flow.getTimeCreate())
                .affectedIsl(Collections.singleton(new IslEndpoint(SWITCH_ID_A, 1)))
                .force(false)
                .effectivelyDown(true)
                .reason("another reason")
                .build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .pending(pending)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        RerouteResultInfoData rerouteResultInfoData = RerouteResultInfoData.builder()
                .flowId(FLOW_ID)
                .success(false)
                .rerouteError(new SpeakerRequestError("Failed to install rules",
                        Collections.singleton(SWITCH_C.getSwitchId())))
                .build();
        rerouteQueueService.processRerouteResult(rerouteResultInfoData, CORRELATION_ID);

        String retryCorrelationId = CORRELATION_ID + " : retry #1 ignore_bw false";
        FlowThrottlingData expected = getFlowThrottlingData(flow, retryCorrelationId).build();
        expected.setPriority(pending.getPriority());
        expected.setReason(pending.getReason());
        assertEquals(expected, rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        assertNull(rerouteQueue.getThrottling());
        FlowRerouteRequest expectedRequest = getFlowRerouteRequest(FLOW_ID, expected);
        verify(carrier).sendRerouteRequest(eq(retryCorrelationId), eq(expectedRequest));
    }

    @Test
    public void shouldSendThrottledRequestOnFlushWindowEvent() {
        FlowThrottlingData throttling = getFlowThrottlingData(flow, CORRELATION_ID).build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .throttling(throttling)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        rerouteQueueService.flushThrottling();

        assertEquals(throttling, rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        assertNull(rerouteQueue.getThrottling());
        FlowRerouteRequest expectedRequest = getFlowRerouteRequest(FLOW_ID, throttling);
        verify(carrier).sendRerouteRequest(any(String.class), eq(expectedRequest));
    }

    @Test
    public void shouldPrioritizeLowBandwidthFlowWithCostAndAvailableBandwidthPathComputationStrategy() {
        FlowThrottlingData first = getFlowThrottlingData(flow, CORRELATION_ID)
                .priority(100)
                .build();
        FlowThrottlingData second = getFlowThrottlingData(flow, CORRELATION_ID)
                .pathComputationStrategy(PathComputationStrategy.COST_AND_AVAILABLE_BANDWIDTH)
                .bandwidth(100)
                .build();
        FlowThrottlingData third = getFlowThrottlingData(flow, CORRELATION_ID)
                .pathComputationStrategy(PathComputationStrategy.COST_AND_AVAILABLE_BANDWIDTH)
                .bandwidth(1000)
                .build();
        RerouteQueue firstQueue = RerouteQueue.builder()
                .throttling(first)
                .build();
        RerouteQueue secondQueue = RerouteQueue.builder()
                .throttling(second)
                .build();
        RerouteQueue thirdQueue = RerouteQueue.builder()
                .throttling(third)
                .build();
        rerouteQueueService.getReroutes().put("third flow", thirdQueue);
        rerouteQueueService.getReroutes().put("second flow", secondQueue);
        rerouteQueueService.getReroutes().put("first flow", firstQueue);

        rerouteQueueService.flushThrottling();

        verify(carrier).sendRerouteRequest(any(String.class), eq(getFlowRerouteRequest("first flow", first)));
        verify(carrier).sendRerouteRequest(any(String.class), eq(getFlowRerouteRequest("second flow", second)));
        verify(carrier).sendRerouteRequest(any(String.class), eq(getFlowRerouteRequest("third flow", third)));
    }

    @Test
    public void shouldMergeThrottledAndPendingRequestOnFlushWindowEvent() {
        FlowThrottlingData inProgress = FlowThrottlingData.builder()
                .correlationId("in progress")
                .priority(flow.getPriority())
                .timeCreate(flow.getTimeCreate())
                .affectedIsl(Collections.emptySet())
                .force(false)
                .effectivelyDown(false)
                .reason("first reason")
                .build();
        FlowThrottlingData pending = FlowThrottlingData.builder()
                .correlationId("pending")
                .priority(flow.getPriority())
                .timeCreate(flow.getTimeCreate())
                .affectedIsl(Collections.singleton(new IslEndpoint(SWITCH_ID_B, 1)))
                .force(false)
                .effectivelyDown(true)
                .reason("second reason")
                .build();
        FlowThrottlingData throttling = FlowThrottlingData.builder()
                .correlationId(CORRELATION_ID)
                .priority(7)
                .timeCreate(Instant.now().plus(1, MINUTES))
                .affectedIsl(Collections.singleton(new IslEndpoint(SWITCH_ID_A, 1)))
                .force(true)
                .effectivelyDown(false)
                .reason("third reason")
                .build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .pending(pending)
                .throttling(throttling)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        rerouteQueueService.flushThrottling();

        assertNotNull(rerouteQueue.getInProgress());
        FlowThrottlingData expected = FlowThrottlingData.builder()
                // Correlation id will be forked while flushing
                .correlationId(rerouteQueue.getPending().getCorrelationId())
                .priority(throttling.getPriority())
                .timeCreate(throttling.getTimeCreate())
                .affectedIsl(Sets.newHashSet(new IslEndpoint(SWITCH_ID_A, 1), new IslEndpoint(SWITCH_ID_B, 1)))
                .force(true)
                .effectivelyDown(true)
                .reason(pending.getReason())
                .build();
        assertEquals(expected, rerouteQueue.getPending());
        assertNull(rerouteQueue.getThrottling());
    }

    @Test
    public void shouldInjectRetryToThrottlingWhenReceivedTimeout() {
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, CORRELATION_ID).build();
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        rerouteQueueService.handleTimeout(CORRELATION_ID);

        assertNull(rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        FlowThrottlingData expected = getFlowThrottlingData(flow,
                CORRELATION_ID + " : retry #1 ignore_bw false").build();
        expected.setRetryCounter(1);
        assertEquals(expected, rerouteQueue.getThrottling());
        verify(carrier).sendExtendTimeWindowEvent();
    }

    @Test
    public void shouldNotInjectRetryWhenRetryCountIsExceeded() {
        FlowThrottlingData inProgress = getFlowThrottlingData(flow, CORRELATION_ID).build();
        inProgress.setRetryCounter(4);
        RerouteQueue rerouteQueue = RerouteQueue.builder()
                .inProgress(inProgress)
                .build();
        rerouteQueueService.getReroutes().put(FLOW_ID, rerouteQueue);

        rerouteQueueService.handleTimeout(CORRELATION_ID);

        assertNull(rerouteQueue.getInProgress());
        assertNull(rerouteQueue.getPending());
        assertNull(rerouteQueue.getThrottling());
    }

    private FlowThrottlingDataBuilder getFlowThrottlingData(Flow flow, String correlationId) {
        return FlowThrottlingData.builder()
                .correlationId(correlationId)
                .priority(flow.getPriority())
                .timeCreate(flow.getTimeCreate())
                .affectedIsl(Collections.emptySet())
                .force(true)
                .effectivelyDown(true)
                .reason("reason");
    }

    private FlowRerouteRequest getFlowRerouteRequest(String flowId, FlowThrottlingData flowThrottlingData) {
        return new FlowRerouteRequest(flowId, flowThrottlingData.isForce(), flowThrottlingData.isEffectivelyDown(),
                flowThrottlingData.isIgnoreBandwidth(),
                flowThrottlingData.getAffectedIsl(), flowThrottlingData.getReason());
    }

    private ArgumentMatcher<ErrorData> flowNotFoundErrorData(String flowId) {
        return (errorData) -> ErrorType.NOT_FOUND == errorData.getErrorType()
                && errorData.getErrorMessage().equals("Could not reroute flow")
                && errorData.getErrorDescription().equals(format("Flow %s not found", flowId));
    }

    private ArgumentMatcher<ErrorData> rerouteIsInProgressErrorData(String flowId) {
        return (errorData) -> ErrorType.UNPROCESSABLE_REQUEST == errorData.getErrorType()
                && errorData.getErrorMessage().equals("Could not reroute flow")
                && errorData.getErrorDescription().equals(format("Flow %s is in reroute process", flowId));
    }

    private ArgumentMatcher<ErrorData> pinnedFlowErrorData(String flowId) {
        return (errorData) -> ErrorType.UNPROCESSABLE_REQUEST == errorData.getErrorType()
                && errorData.getErrorMessage().equals("Could not reroute flow")
                && errorData.getErrorDescription().equals("Can't reroute pinned flow");
    }
}
