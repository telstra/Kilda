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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import org.openkilda.floodlight.api.request.EgressFlowSegmentInstallRequest;
import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.wfm.CommandContext;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FlowCreateServiceTest extends AbstractFlowTest {
    @Mock
    private FlowCreateHubCarrier carrier;

    @Before
    public void init() {
        doAnswer(getSpeakerCommandsAnswer()).when(carrier).sendSpeakerRequest(any(FlowSegmentRequest.class));
    }

    @Test
    public void shouldCreateFlowWithTransitSwitches() throws Exception {
        FlowRequest request = makeRequest()
                .flowId("test_successful_flow_id")
                .build();
        preparePathComputation(request.getFlowId(), make3SwitchesPathPair());
        testHappyPath(request, "successful_flow_create");
    }

    @Test
    public void shouldCreateOneSwitchFlow() throws Exception {
        FlowRequest request = makeRequest()
                .flowId("one_switch_flow")
                .destinationSwitch(SWITCH_SOURCE)
                .destinationPort(2)
                .destinationVlan(2)
                .build();
        preparePathComputation(request.getFlowId(), makeOneSwitchPathPair());
        testHappyPath(request, "successful_flow_create");
    }

    @Test
    public void shouldCreatePinnedFlow() throws Exception {
        FlowRequest request = makeRequest()
                .flowId("test_successful_flow_id")
                .pinned(true)
                .build();
        preparePathComputation(request.getFlowId(), make3SwitchesPathPair());
        Flow result = testHappyPath(request, "successful_flow_create");
        Assert.assertTrue(result.isPinned());
    }

    @Test
    public void shouldCreateFlowWithProtectedPath() throws Exception {
        FlowRequest request = makeRequest()
                .flowId("test_successful_flow_id")
                .allocateProtectedPath(true)
                .build();

        when(pathComputer.getPath(makeFlowArgumentMatch(request.getFlowId())))
                .thenReturn(make2SwitchesPathPair())
                .thenReturn(make3SwitchesPathPair());

        Flow result = testHappyPath(request, "successful_flow_create");
        Assert.assertTrue(result.isAllocateProtectedPath());
        verifyFlowPathStatus(result.getProtectedForwardPath(), FlowPathStatus.ACTIVE, "protected-forward");
        verifyFlowPathStatus(result.getProtectedReversePath(), FlowPathStatus.ACTIVE, "protected-reverse");
    }

    private Flow testHappyPath(FlowRequest flowRequest, String key) {
        FlowCreateService service = makeService();
        service.handleRequest(key, new CommandContext(), flowRequest);

        Flow inProgress = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyFlowPathStatus(inProgress.getForwardPath(), FlowPathStatus.IN_PROGRESS, "forward");
        verifyFlowPathStatus(inProgress.getReversePath(), FlowPathStatus.IN_PROGRESS, "reverse");

        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest request;
        while ((request = requests.poll()) != null) {
            if (request.isVerifyRequest()) {
                service.handleAsyncResponse(key, buildResponseOnVerifyRequest(request));
            } else {
                handleResponse(service, key, request);
            }
        }

        Flow result = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.UP);
        verifyFlowPathStatus(result.getForwardPath(), FlowPathStatus.ACTIVE, "forward");
        verifyFlowPathStatus(result.getReversePath(), FlowPathStatus.ACTIVE, "reverse");

        return result;
    }

    @Test
    public void shouldRollbackIfEgressRuleNotInstalled() throws Exception {
        when(pathComputer.getPath(any(Flow.class))).thenReturn(make3SwitchesPathPair());

        String key = "failed_flow_create";
        FlowRequest flowRequest = makeRequest()
                .flowId("failed_flow_id")
                .build();

        FlowCreateService service = makeService();
        service.handleRequest(key, new CommandContext(), flowRequest);

        Flow inProgress = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyFlowPathStatus(inProgress.getForwardPath(), FlowPathStatus.IN_PROGRESS, "forward");
        verifyFlowPathStatus(inProgress.getReversePath(), FlowPathStatus.IN_PROGRESS, "reverse");

        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest request;
        int installCommands = 0;
        int deleteCommands = 0;
        while ((request = requests.poll()) != null) {
            if (request.isVerifyRequest()) {
                service.handleAsyncResponse(key, buildResponseOnVerifyRequest(request));
            } else if (request.isInstallRequest()) {
                installCommands++;
                if (requests.size() > 1) {
                    handleResponse(service, key, request);
                } else {
                    handleErrorResponse(service, key, request, ErrorCode.UNKNOWN);
                }
            } else if (request.isRemoveRequest()) {
                deleteCommands++;
                handleResponse(service, key, request);
            }
        }

        assertEquals("All installed rules should be deleted", installCommands, deleteCommands);

        Flow result = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.DOWN);
        // TODO(surabujin): do we really want to create flow without paths?
        Assert.assertNull(result.getForwardPath());
        Assert.assertNull(result.getReversePath());
    }

    @Test
    public void shouldRollbackIfIngressRuleNotInstalled() throws Exception {
        when(pathComputer.getPath(any(Flow.class))).thenReturn(make3SwitchesPathPair());

        String key = "failed_flow_create";
        FlowRequest flowRequest = makeRequest()
                .flowId("failed_flow_id")
                .build();

        FlowCreateService service = makeService();
        service.handleRequest(key, new CommandContext(), flowRequest);

        Flow inProgress = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyFlowPathStatus(inProgress.getForwardPath(), FlowPathStatus.IN_PROGRESS, "forward");
        verifyFlowPathStatus(inProgress.getReversePath(), FlowPathStatus.IN_PROGRESS, "reverse");

        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest request;
        int installCommands = 0;
        int deleteCommands = 0;
        while ((request = requests.poll()) != null) {
            if (request.isVerifyRequest()) {
                service.handleAsyncResponse(key, buildResponseOnVerifyRequest(request));
            } else if (request.isInstallRequest()) {
                installCommands++;
                if (requests.size() > 1 || request instanceof EgressFlowSegmentInstallRequest) {
                    handleResponse(service, key, request);
                } else {
                    handleErrorResponse(service, key, request, ErrorCode.UNKNOWN);
                }
            } else if (request.isRemoveRequest()) {
                deleteCommands++;
                handleResponse(service, key, request);
            }
        }

        assertEquals("All installed rules should be deleted", installCommands, deleteCommands);
        Flow result = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.DOWN);
        Assert.assertNull(result.getForwardPath());
        Assert.assertNull(result.getReversePath());
    }

    @Test
    public void shouldCreateFlowWithRetryNonIngressRuleIfSwitchIsUnavailable() throws Exception {
        when(pathComputer.getPath(any(Flow.class))).thenReturn(make3SwitchesPathPair());


        String key = "retries_non_ingress_installation";
        FlowRequest flowRequest = makeRequest()
                .flowId("failed_flow_id")
                .build();

        int retriesLimit = 10;
        FlowCreateService service = makeService(retriesLimit);

        service.handleRequest(key, new CommandContext(), flowRequest);

        Flow inProgress = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyFlowPathStatus(inProgress.getForwardPath(), FlowPathStatus.IN_PROGRESS, "forward");
        verifyFlowPathStatus(inProgress.getReversePath(), FlowPathStatus.IN_PROGRESS, "reverse");

        verifyNorthboundSuccessResponse(carrier);

        int remainingRetries = retriesLimit;
        FlowSegmentRequest request;
        while ((request = requests.poll()) != null) {
            if (request.isVerifyRequest()) {
                service.handleAsyncResponse(key, buildResponseOnVerifyRequest(request));
            } else {
                if (request instanceof EgressFlowSegmentInstallRequest && remainingRetries > 0) {
                    handleErrorResponse(service, key, request, ErrorCode.SWITCH_UNAVAILABLE);
                    remainingRetries--;
                } else {
                    handleResponse(service, key, request);
                }
            }
        }

        assertEquals(0, remainingRetries);
        Flow result = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.UP);
        verifyFlowPathStatus(result.getForwardPath(), FlowPathStatus.ACTIVE, "forward");
        verifyFlowPathStatus(result.getReversePath(), FlowPathStatus.ACTIVE, "reverse");
    }

    @Test
    public void shouldCreateFlowWithRetryIngressRuleIfSwitchIsUnavailable() throws Exception {
        when(pathComputer.getPath(any(Flow.class))).thenReturn(make3SwitchesPathPair());

        String key = "retries_non_ingress_installation";
        FlowRequest flowRequest = makeRequest()
                .flowId("failed_flow_id")
                .build();

        int retriesLimit = 10;
        FlowCreateService service = makeService(retriesLimit);

        service.handleRequest(key, new CommandContext(), flowRequest);

        Flow inProgress = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyFlowPathStatus(inProgress.getForwardPath(), FlowPathStatus.IN_PROGRESS, "forward");
        verifyFlowPathStatus(inProgress.getReversePath(), FlowPathStatus.IN_PROGRESS, "reverse");

        verifyNorthboundSuccessResponse(carrier);

        int remainingRetries = retriesLimit;
        FlowSegmentRequest request;
        while ((request = requests.poll()) != null) {
            if (request.isVerifyRequest()) {
                service.handleAsyncResponse(key, buildResponseOnVerifyRequest(request));
            } else {
                if (remainingRetries > 0) {
                    handleErrorResponse(service, key, request, ErrorCode.SWITCH_UNAVAILABLE);
                    remainingRetries--;
                } else {
                    handleResponse(service, key, request);
                }
            }
        }

        assertEquals(0, remainingRetries);
        Flow result = verifyFlowStatus(flowRequest.getFlowId(), FlowStatus.UP);
        verifyFlowPathStatus(result.getForwardPath(), FlowPathStatus.ACTIVE, "forward");
        verifyFlowPathStatus(result.getReversePath(), FlowPathStatus.ACTIVE, "reverse");
    }

    private void handleResponse(FlowCreateService service, String key, FlowSegmentRequest request) {
        service.handleAsyncResponse(key, SpeakerFlowSegmentResponse.builder()
                .messageContext(request.getMessageContext())
                .metadata(request.getMetadata())
                .commandId(request.getCommandId())
                .switchId(request.getSwitchId())
                .success(true)
                .build());
    }

    private void handleErrorResponse(
            FlowCreateService service, String key, FlowSegmentRequest request, ErrorCode errorCode) {
        service.handleAsyncResponse(key, FlowErrorResponse.errorBuilder()
                .messageContext(request.getMessageContext())
                .metadata(request.getMetadata())
                .commandId(request.getCommandId())
                .switchId(request.getSwitchId())
                .errorCode(errorCode)
                .build());
    }

    private void preparePathComputation(String flowId, PathPair pathPair)
            throws RecoverableException, UnroutableFlowException {
        when(pathComputer.getPath(makeFlowArgumentMatch(flowId))).thenReturn(pathPair);
    }

    private FlowCreateService makeService() {
        return makeService(0);
    }

    private FlowCreateService makeService(int retriesLimit) {
        return new FlowCreateService(carrier, persistenceManager, pathComputer, flowResourcesManager,
                0, 3, retriesLimit, new SimpleMeterRegistry());
    }
}
