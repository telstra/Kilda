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

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FlowDeleteServiceTest extends AbstractFlowTest {
    private static final int SPEAKER_COMMAND_RETRIES_LIMIT = 3;

    @Mock
    private FlowDeleteHubCarrier carrier;

    @Before
    public void setUp() {
        doAnswer(getSpeakerCommandsAnswer()).when(carrier).sendSpeakerRequest(any());

        // must be done before first service create attempt, because repository objects are cached inside FSM actions
        setupFlowRepositorySpy();
        setupFlowPathRepositorySpy();
    }

    @Test
    public void shouldFailDeleteFlowIfNoFlowFound() {
        String flowId = "dummy-flow";

        // make sure flow is missing
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        Assert.assertFalse(repository.findById(flowId).isPresent());

        makeService().handleRequest(dummyRequestKey, commandContext, flowId);

        verifyNoSpeakerInteraction(carrier);
        verifyNorthboundErrorResponse(carrier, ErrorType.NOT_FOUND);
    }

    @Test
    public void shouldFailDeleteFlowOnLockedFlow() {
        Flow flow = makeFlow();
        setupFlowRepositorySpy().findById(flow.getFlowId())
                .ifPresent(foundFlow -> foundFlow.setStatus(FlowStatus.IN_PROGRESS));

        makeService().handleRequest(dummyRequestKey, commandContext, flow.getFlowId());

        verifyNoSpeakerInteraction(carrier);
        verifyNorthboundErrorResponse(carrier, ErrorType.REQUEST_INVALID);
        verifyFlowStatus(flow.getFlowId(), FlowStatus.IN_PROGRESS);
    }

    @Test
    public void shouldCompleteDeleteOnLockedSwitches() {
        String flowId = makeFlow().getFlowId();

        FlowDeleteService service = makeService();
        service.handleRequest(dummyRequestKey, commandContext, flowId);

        Flow flow = verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        produceSpeakerResponses(service);

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(flow);
    }

    @Test
    public void shouldCompleteDeleteOnUnsuccessfulSpeakerResponse() {
        testSpeakerErrorResponse(makeFlow().getFlowId(), ErrorCode.UNKNOWN);
    }

    @Test
    public void shouldCompleteDeleteOnTimeoutSpeakerResponse() {
        testSpeakerErrorResponse(makeFlow().getFlowId(), ErrorCode.OPERATION_TIMED_OUT);
    }

    private void testSpeakerErrorResponse(String flowId, ErrorCode errorCode) {
        FlowDeleteService service = makeService();
        service.handleRequest(dummyRequestKey, commandContext, flowId);

        Flow flow = verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, FlowErrorResponse.errorBuilder()
                    .errorCode(errorCode)
                    .description("Switch is unavailable")
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .messageContext(flowRequest.getMessageContext())
                    .build());
        }

        // 4 times sending 4 rules = 16 requests.
        verify(carrier, times(16)).sendSpeakerRequest(any());
        verifyFlowIsMissing(flow);
    }

    @Test
    public void shouldFailDeleteOnTimeoutDuringRuleRemoval() {
        String flowId = makeFlow().getFlowId();

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, flowId);
        verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        verify(carrier, times(4)).sendSpeakerRequest(any());

        service.handleTimeout(dummyRequestKey);

        // FIXME(surabujin): flow stays in IN_PROGRESS status, any further request can't be handled.
        //  em... there is no actual handling for timeout event, so FSM will stack in memory forever
        Flow flow = verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);

        // Flow delete will be completed only on timeout event(s) produced by {@link SpeakerWorkerBolt} for all produced
        // speaker requests.

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, FlowErrorResponse.errorBuilder()
                    .commandId(flowRequest.getCommandId())
                    .switchId(flowRequest.getSwitchId())
                    .metadata(flowRequest.getMetadata())
                    .errorCode(ErrorCode.OPERATION_TIMED_OUT)
                    .messageContext(flowRequest.getMessageContext())
                    .build());
        }

        verifyFlowIsMissing(flow);
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringCompletingFlowPathRemoval() {
        Flow target = makeFlow();
        FlowPath forwardPath = target.getForwardPath();
        Assert.assertNotNull(forwardPath);

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .remove(eq(forwardPath.getPathId()));

        FlowDeleteService service = makeService();
        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());

        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        produceSpeakerResponses(service);

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(target);
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringResourceDeallocation() {
        Flow target = makeFlow();
        FlowPath forwardPath = target.getForwardPath();
        Assert.assertNotNull(forwardPath);

        doThrow(new RuntimeException(injectedErrorMessage))
                .when(flowResourcesManager)
                .deallocatePathResources(MockitoHamcrest.argThat(
                        Matchers.hasProperty("forward",
                                Matchers.<PathResources>hasProperty("pathId", is(forwardPath.getPathId())))));

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        produceSpeakerResponses(service);

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(target);
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringRemovingFlow() {
        Flow target = makeFlow();

        FlowRepository repository = setupFlowRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .remove(eq(target.getFlowId()));

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        verify(carrier, times(4)).sendSpeakerRequest(any());
        produceSpeakerResponses(service);

        // FIXME(surabujin): The flow become untouchable from kilda API (because it stack in IN_PROGRESS state
        //  no one CRUD operation can perform on it). This is DB/persisted storage level error, we do not have
        //  a clear plan on the way how to handle it.
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
    }

    @Test
    public void shouldSuccessfullyDeleteFlow() {
        Flow target = makeFlow();

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        produceSpeakerResponses(service);

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(target);
    }

    private void verifyFlowIsMissing(Flow flow) {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        FlowRepository flowRepository = repositoryFactory.createFlowRepository();
        FlowPathRepository flowPathRepository = repositoryFactory.createFlowPathRepository();

        Assert.assertFalse(flowRepository.findById(flow.getFlowId()).isPresent());

        for (FlowPath path : flow.getPaths()) {
            Assert.assertFalse(
                    String.format("Flow path %s still exists", path.getPathId()),
                    flowPathRepository.findById(path.getPathId()).isPresent());
        }

        // TODO(surabujin): maybe we should make more deep scanning for flow related resources and nested objects
    }

    private void produceSpeakerResponses(FlowDeleteService service) {
        FlowSegmentRequest request;
        while ((request = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, SpeakerFlowSegmentResponse.builder()
                    .messageContext(request.getMessageContext())
                    .commandId(request.getCommandId())
                    .metadata(request.getMetadata())
                    .switchId(request.getSwitchId())
                    .success(true)
                    .build());
        }
    }

    private FlowDeleteService makeService() {
        return new FlowDeleteService(
                carrier, persistenceManager, flowResourcesManager,
                SPEAKER_COMMAND_RETRIES_LIMIT, new SimpleMeterRegistry());
    }
}
