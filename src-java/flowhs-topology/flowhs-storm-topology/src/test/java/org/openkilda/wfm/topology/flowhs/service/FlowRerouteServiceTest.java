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

package org.openkilda.wfm.topology.flowhs.service;

import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static org.openkilda.model.PathComputationStrategy.LATENCY;
import static org.openkilda.model.PathComputationStrategy.MAX_LATENCY;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.flow.FlowRerouteResponse;
import org.openkilda.messaging.info.reroute.RerouteResultInfoData;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.pce.GetPathsResult;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class FlowRerouteServiceTest extends AbstractFlowTest {
    private static final int PATH_ALLOCATION_RETRIES_LIMIT = 10;
    private static final int PATH_ALLOCATION_RETRY_DELAY = 0;
    private static final int SPEAKER_COMMAND_RETRIES_LIMIT = 0;

    @Mock
    private FlowRerouteHubCarrier carrier;

    private String currentRequestKey = dummyRequestKey;

    @Before
    public void setUp() {
        doAnswer(getSpeakerCommandsAnswer()).when(carrier).sendSpeakerRequest(any());

        // must be done before first service create attempt, because repository objects are cached inside FSM actions
        setupFlowRepositorySpy();
        setupFlowPathRepositorySpy();
        setupIslRepositorySpy();
    }

    @Test
    public void shouldFailRerouteFlowIfNoPathAvailable() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), new UnroutableFlowException(injectedErrorMessage));
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        testExpectedFailure(dummyRequestKey, request, commandContext, origin, FlowStatus.DOWN, ErrorType.NOT_FOUND);

        verify(pathComputer, times(11))
                .getPath(makeFlowArgumentMatch(origin.getFlowId()), any(), any());
    }

    @Test
    public void shouldFailRerouteFlowIfRecoverableException() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), new RecoverableException(injectedErrorMessage));

        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        testExpectedFailure(dummyRequestKey, request, commandContext, origin, FlowStatus.UP, ErrorType.INTERNAL_ERROR);

        verify(pathComputer, times(PATH_ALLOCATION_RETRIES_LIMIT + 1))
                .getPath(makeFlowArgumentMatch(origin.getFlowId()), any(), any());
    }

    @Test
    public void shouldFailRerouteFlowIfMultipleOverprovisionBandwidth()
            throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        IslRepository repository = setupIslRepositorySpy();
        doReturn(-1L)
                .when(repository).updateAvailableBandwidth(any(), anyInt(), any(), anyInt());

        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        testExpectedFailure(dummyRequestKey, request, commandContext, origin, FlowStatus.UP, ErrorType.INTERNAL_ERROR);

        verify(repository, times(PATH_ALLOCATION_RETRIES_LIMIT + 1))
                .updateAvailableBandwidth(any(), anyInt(), any(), anyInt());
    }

    @Test
    public void shouldFailRerouteFlowIfNoResourcesAvailable()
            throws RecoverableException, UnroutableFlowException, ResourceAllocationException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        doThrow(new ResourceAllocationException(injectedErrorMessage))
                .when(flowResourcesManager).allocateFlowResources(makeFlowArgumentMatch(origin.getFlowId()),
                any(), any());

        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        testExpectedFailure(dummyRequestKey, request, commandContext, origin, FlowStatus.UP, ErrorType.INTERNAL_ERROR);

        verify(flowResourcesManager, times(PATH_ALLOCATION_RETRIES_LIMIT + 1))
                .allocateFlowResources(makeFlowArgumentMatch(origin.getFlowId()), any(), any());
    }

    @Test
    public void shouldFailRerouteFlowOnResourcesAllocationConstraint()
            throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .add(any(FlowPath.class));

        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        testExpectedFailure(dummyRequestKey, request, commandContext, origin, FlowStatus.UP, ErrorType.INTERNAL_ERROR);
    }

    private void testExpectedFailure(String key,
                                     FlowRerouteRequest request, CommandContext context,
                                     Flow origin, FlowStatus expectedFlowStatus, ErrorType expectedError) {
        makeService().handleRequest(key, request, context);

        verifyNoSpeakerInteraction(carrier);
        verifyNorthboundErrorResponse(carrier, expectedError);

        Flow result = verifyFlowStatus(origin.getFlowId(), expectedFlowStatus);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldSkipRerouteIfNoNewPathFound() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make2SwitchesPathPair());
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);

        makeService().handleRequest(dummyRequestKey, request, commandContext);

        verifyNoSpeakerInteraction(carrier);
        verifyNorthboundSuccessResponse(carrier);

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldFailRerouteOnUnsuccessfulInstallation() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        GetPathsResult newPathPair = make3SwitchesPathPair();
        preparePathComputation(origin.getFlowId(), newPathPair);

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            if (speakerRequest.isInstallRequest()) {
                service.handleAsyncResponse(currentRequestKey, FlowErrorResponse.errorBuilder()
                        .messageContext(speakerRequest.getMessageContext())
                        .errorCode(ErrorCode.UNKNOWN)
                        .description(injectedErrorMessage)
                        .commandId(speakerRequest.getCommandId())
                        .metadata(speakerRequest.getMetadata())
                        .switchId(speakerRequest.getSwitchId())
                        .build());
            } else {
                produceAsyncResponse(service, speakerRequest);
            }
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
        verify(carrier).sendRerouteResultStatus(eq(origin.getFlowId()),
                argThat(hasProperty("message", equalTo("Failed to install rules"))),
                any(String.class));
    }

    @Test
    public void shouldFailRerouteOnTimeoutDuringInstallation() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        GetPathsResult newPathPair = make3SwitchesPathPair();
        preparePathComputation(origin.getFlowId(), newPathPair);

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        service.handleTimeout(currentRequestKey);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
        verify(carrier).sendRerouteResultStatus(eq(origin.getFlowId()),
                argThat(hasProperty("message", equalTo("Failed to install rules"))),
                any(String.class));
    }

    @Test
    public void shouldFailRerouteOnUnsuccessfulValidation() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        GetPathsResult newPathPair = make3SwitchesPathPair();
        preparePathComputation(origin.getFlowId(), newPathPair);

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            if (speakerRequest.isVerifyRequest()) {
                service.handleAsyncResponse(currentRequestKey, FlowErrorResponse.errorBuilder()
                        .errorCode(ErrorCode.UNKNOWN)
                        .description(injectedErrorMessage)
                        .messageContext(speakerRequest.getMessageContext())
                        .commandId(speakerRequest.getCommandId())
                        .metadata(speakerRequest.getMetadata())
                        .switchId(speakerRequest.getSwitchId())
                        .build());
            } else {
                produceAsyncResponse(service, speakerRequest);
            }
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
        verify(carrier).sendRerouteResultStatus(eq(origin.getFlowId()),
                argThat(hasProperty("message", equalTo("Failed to validate rules"))),
                any(String.class));
    }

    @Test
    public void shouldFailRerouteOnTimeoutDuringValidation() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        GetPathsResult newPathPair = make3SwitchesPathPair();
        preparePathComputation(origin.getFlowId(), newPathPair);

        FlowRerouteService service = makeService();

        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            if (speakerRequest.isVerifyRequest()) {
                service.handleTimeout(currentRequestKey);
            } else {
                produceAsyncResponse(service, speakerRequest);
            }
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
        verify(carrier).sendRerouteResultStatus(eq(origin.getFlowId()),
                argThat(hasProperty("message", equalTo("Failed to validate rules"))),
                any(String.class));
    }

    @Ignore("FIXME: need to replace mocking of updateStatus with another approach")
    @Test
    public void shouldFailRerouteOnSwapPathsError() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowPathRepository flowPathRepository = setupFlowPathRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(flowPathRepository).updateStatus(eq(origin.getForwardPathId()),
                eq(FlowPathStatus.IN_PROGRESS));

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            if (speakerRequest.isVerifyRequest()) {
                service.handleAsyncResponse(currentRequestKey, buildResponseOnVerifyRequest(speakerRequest));
            } else {
                produceAsyncResponse(service, speakerRequest);
            }
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldFailRerouteOnErrorDuringCompletingFlowPathInstallation()
            throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        Set<PathId> originalPaths = origin.getPaths().stream()
                .map(FlowPath::getPathId)
                .collect(toSet());
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .updateStatus(
                        ArgumentMatchers.argThat(argument -> !originalPaths.contains(argument)),
                        eq(FlowPathStatus.ACTIVE));

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            if (speakerRequest.isVerifyRequest()) {
                service.handleAsyncResponse(currentRequestKey, buildResponseOnVerifyRequest(speakerRequest));
            } else {
                produceAsyncResponse(service, speakerRequest);
            }
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldCompleteRerouteOnErrorDuringCompletingFlowPathRemoval()
            throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .remove(eq(origin.getForwardPathId()));

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyPathReplace(origin, result);
    }

    @Test
    public void shouldCompleteRerouteOnErrorDuringResourceDeallocation()
            throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);


        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        doThrow(new RuntimeException(injectedErrorMessage))
                .when(flowResourcesManager).deallocatePathResources(argThat(
                hasProperty("forward",
                        hasProperty("pathId", equalTo(origin.getForwardPathId())))));

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            if (speakerRequest.isVerifyRequest()) {
                service.handleAsyncResponse(currentRequestKey, buildResponseOnVerifyRequest(speakerRequest));
            } else {
                produceAsyncResponse(service, speakerRequest);
            }
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyPathReplace(origin, result);
    }

    @Test
    public void shouldSuccessfullyRerouteFlow() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        origin.setStatus(FlowStatus.DOWN);
        transactionManager.doInTransaction(() ->
                repositoryFactory.createFlowRepository().updateStatus(origin.getFlowId(), FlowStatus.DOWN));

        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyPathReplace(origin, result);
        RerouteResultInfoData expected = RerouteResultInfoData.builder()
                .flowId(origin.getFlowId())
                .success(true)
                .build();
        verify(carrier).sendRerouteResultStatus(eq(origin.getFlowId()), isNull(), any(String.class));
    }

    @Test
    public void shouldSuccessfullyHandleOverlappingRequests() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        origin.setStatus(FlowStatus.DOWN);
        transactionManager.doInTransaction(() ->
                repositoryFactory.createFlowRepository().updateStatus(origin.getFlowId(), FlowStatus.DOWN));

        when(pathComputer.getPath(makeFlowArgumentMatch(origin.getFlowId()), any(), any()))
                .thenReturn(make2SwitchAltPathPair())
                .thenReturn(make3SwitchesPathPair());

        FlowRerouteService service = makeService();

        FlowRerouteRequest rerouteRequest = new FlowRerouteRequest(origin.getFlowId(), false, false,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, rerouteRequest, commandContext);


        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);

        String overlappingKey = dummyRequestKey + "2";
        service.handleRequest(overlappingKey, rerouteRequest, commandContext);
        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);

        FlowSegmentRequest request;
        while ((request = requests.poll()) != null) {
            produceAsyncResponse(service, request);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyPathReplace(origin, result);

        FlowPath forwardPath = result.getForwardPath();
        Assert.assertNotNull(forwardPath);
        Assert.assertEquals(1, forwardPath.getSegments().size());  // second request is dropped

        verify(carrier).sendRerouteResultStatus(eq(origin.getFlowId()),
                argThat(hasProperty("message", equalTo("Reroute is in progress"))),
                any(String.class));
    }

    @Test
    public void shouldMakeFlowDownOnTimeoutIfEffectivelyDown() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, true,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        service.handleTimeout(currentRequestKey);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.DOWN);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldIgnoreEffectivelyDownStateIfSamePaths() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        preparePathComputation(origin.getFlowId(), make2SwitchesPathPair());

        FlowRerouteService service = makeService();
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, true,
                false, Collections.emptySet(), null);
        service.handleRequest(currentRequestKey, request, commandContext);


        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldProcessRerouteForValidRequest() throws RecoverableException, UnroutableFlowException {
        Flow origin = makeFlow();
        origin.setTargetPathComputationStrategy(LATENCY);
        setupFlowRepositorySpy().findById(origin.getFlowId())
                .ifPresent(foundPath -> foundPath.setTargetPathComputationStrategy(LATENCY));
        preparePathComputation(origin.getFlowId(), make3SwitchesPathPair());

        FlowRerouteService service = makeService();
        IslEndpoint affectedEndpoint = extractIslEndpoint(origin);
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, true,
                false, Collections.singleton(affectedEndpoint), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyPathReplace(origin, result);
        assertEquals(LATENCY, result.getPathComputationStrategy());
        assertNull(result.getTargetPathComputationStrategy());
    }

    @Test
    public void shouldSkipRerouteOnOutdatedRequest() {
        Flow origin = makeFlow();

        FlowRerouteService service = makeService();
        IslEndpoint affectedEndpoint = extractIslEndpoint(origin);

        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, true,
                false, Collections.singleton(affectedEndpoint), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.UP);
        verifyNoPathReplace(origin, result);
    }

    @Test
    public void shouldMoveFlowToDegradedIfPathWithRequiredLatencyNotFound() throws Exception {
        Flow origin = makeFlow();
        origin.setTargetPathComputationStrategy(MAX_LATENCY);
        setupFlowRepositorySpy().findById(origin.getFlowId())
                .ifPresent(foundPath -> foundPath.setTargetPathComputationStrategy(MAX_LATENCY));
        when(pathComputer.getPath(makeFlowArgumentMatch(origin.getFlowId()),
                any(Collection.class))).thenReturn(make3SwitchesPathPair(true));

        FlowRerouteService service = makeService();
        IslEndpoint affectedEndpoint = extractIslEndpoint(origin);
        FlowRerouteRequest request = new FlowRerouteRequest(origin.getFlowId(), false, true,
                false, Collections.singleton(affectedEndpoint), null);
        service.handleRequest(currentRequestKey, request, commandContext);

        verifyFlowStatus(origin.getFlowId(), FlowStatus.IN_PROGRESS);

        FlowSegmentRequest speakerRequest;
        while ((speakerRequest = requests.poll()) != null) {
            produceAsyncResponse(service, speakerRequest);
        }

        Flow result = verifyFlowStatus(origin.getFlowId(), FlowStatus.DEGRADED);
        verifyPathReplace(origin, result);
        assertEquals(MAX_LATENCY, result.getPathComputationStrategy());
        assertNull(result.getTargetPathComputationStrategy());
    }

    protected void produceAsyncResponse(FlowRerouteService service, FlowSegmentRequest speakerRequest) {
        service.handleAsyncResponse(currentRequestKey, buildSpeakerResponse(speakerRequest));
    }

    private IslEndpoint extractIslEndpoint(Flow flow) {
        FlowPath forwardPath = flow.getForwardPath();
        assertNotNull(forwardPath);
        List<PathSegment> forwardSegments = forwardPath.getSegments();
        assertFalse(forwardSegments.isEmpty());
        PathSegment firstSegment = forwardSegments.get(0);

        return new IslEndpoint(firstSegment.getSrcSwitchId(), firstSegment.getSrcPort());
    }

    private void preparePathComputation(String flowId, Throwable error)
            throws RecoverableException, UnroutableFlowException {
        doThrow(error).when(pathComputer)
                .getPath(makeFlowArgumentMatch(flowId), any(), any());
    }

    private void preparePathComputation(String flowId, GetPathsResult pathPair)
            throws RecoverableException, UnroutableFlowException {
        when(pathComputer.getPath(makeFlowArgumentMatch(flowId), any(), any())).thenReturn(pathPair);
    }

    @Override
    protected void verifyNorthboundSuccessResponse(FlowGenericCarrier carrierMock) {
        verifyNorthboundSuccessResponse(carrierMock, FlowRerouteResponse.class);
    }

    private FlowRerouteService makeService() {
        return new FlowRerouteService(
                carrier, persistenceManager, pathComputer, flowResourcesManager,
                PATH_ALLOCATION_RETRIES_LIMIT, PATH_ALLOCATION_RETRY_DELAY, PATH_ALLOCATION_RETRIES_LIMIT,
                SPEAKER_COMMAND_RETRIES_LIMIT);
    }
}
