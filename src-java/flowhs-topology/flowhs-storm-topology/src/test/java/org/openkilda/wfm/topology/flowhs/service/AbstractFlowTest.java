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
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.model.Cookie;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.Path;
import org.openkilda.pce.Path.Segment;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.dummy.IslDirectionalReference;
import org.openkilda.persistence.dummy.PersistenceDummyEntityFactory;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;

import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.stubbing.Answer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public abstract class AbstractFlowTest extends InMemoryGraphBasedTest {
    protected static final SwitchId SWITCH_SOURCE = new SwitchId(1);
    protected static final SwitchId SWITCH_DEST = new SwitchId(2);
    protected static final SwitchId SWITCH_TRANSIT = new SwitchId(3L);

    protected final IslDirectionalReference islSourceDest = new IslDirectionalReference(
            new IslEndpoint(SWITCH_SOURCE, 24),
            new IslEndpoint(SWITCH_DEST, 24));
    protected final IslDirectionalReference islSourceDestAlt = new IslDirectionalReference(
            new IslEndpoint(SWITCH_SOURCE, 30),
            new IslEndpoint(SWITCH_DEST, 30));
    protected final IslDirectionalReference islSourceTransit = new IslDirectionalReference(
            new IslEndpoint(SWITCH_SOURCE, 25),
            new IslEndpoint(SWITCH_TRANSIT, 25));
    protected final IslDirectionalReference islTransitDest = new IslDirectionalReference(
            new IslEndpoint(SWITCH_TRANSIT, 26),
            new IslEndpoint(SWITCH_DEST, 26));

    protected final FlowEndpoint flowSource = new FlowEndpoint(SWITCH_SOURCE, 1, 101);
    protected final FlowEndpoint flowDestination = new FlowEndpoint(SWITCH_DEST, 2, 102);

    protected static PersistenceDummyEntityFactory dummyFactory;

    private FlowRepository flowRepositorySpy = null;
    private FlowPathRepository flowPathRepositorySpy = null;
    private IslRepository islRepositorySpy = null;

    protected FlowResourcesManager flowResourcesManager = null;

    protected final String dummyRequestKey = "test-key";
    protected final String injectedErrorMessage = "Unit-test injected failure";

    protected CommandContext commandContext = new CommandContext();

    @Mock
    SwitchPropertiesRepository switchPropertiesRepository;
    @Mock
    PathComputer pathComputer;

    final Queue<FlowSegmentRequest> requests = new ArrayDeque<>();
    final Map<SwitchId, Map<Cookie, FlowSegmentRequest>> installedSegments = new HashMap<>();

    @Before
    public void before() {
        dummyFactory = new PersistenceDummyEntityFactory(persistenceManager);
        
        FlowResourcesConfig resourceConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        flowResourcesManager = spy(new FlowResourcesManager(persistenceManager, resourceConfig));

        alterFeatureToggles(true, true, true);

        dummyFactory.makeSwitch(SWITCH_SOURCE);
        dummyFactory.makeSwitch(SWITCH_DEST);
        dummyFactory.makeSwitch(SWITCH_TRANSIT);
        for (IslDirectionalReference reference : new IslDirectionalReference[]{
                islSourceDest, islSourceDestAlt, islSourceTransit, islTransitDest}) {
            dummyFactory.makeIsl(reference.getSourceEndpoint(), reference.getDestEndpoint());
            dummyFactory.makeIsl(reference.getDestEndpoint(), reference.getSourceEndpoint());
        }
    }

    @After
    public void tearDown() throws Exception {
        if (flowRepositorySpy != null) {
            reset(flowRepositorySpy);
        }
        if (flowPathRepositorySpy != null) {
            reset(flowPathRepositorySpy);
        }
        if (islRepositorySpy != null) {
            reset(islRepositorySpy);
        }
    }

    protected SpeakerFlowSegmentResponse buildSpeakerResponse(FlowSegmentRequest flowRequest) {
        return SpeakerFlowSegmentResponse.builder()
                        .messageContext(flowRequest.getMessageContext())
                        .commandId(flowRequest.getCommandId())
                        .metadata(flowRequest.getMetadata())
                        .switchId(flowRequest.getSwitchId())
                        .success(true)
                        .build();
    }

    Answer getSpeakerCommandsAnswer() {
        return invocation -> {
            FlowSegmentRequest request = invocation.getArgument(0);
            requests.offer(request);

            if (request.isInstallRequest()) {
                installedSegments.computeIfAbsent(request.getSwitchId(), ignore -> new HashMap<>())
                        .put(request.getCookie(), request);
            }

            return request;
        };
    }

    SpeakerFlowSegmentResponse buildResponseOnVerifyRequest(FlowSegmentRequest request) {
        return SpeakerFlowSegmentResponse.builder()
                .commandId(request.getCommandId())
                .metadata(request.getMetadata())
                .messageContext(request.getMessageContext())
                .switchId(request.getSwitchId())
                .success(true)
                .build();
    }

    protected Flow fetchFlow(String flowId) {
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        return repository.findById(flowId)
                .orElseThrow(() -> new AssertionError(String.format(
                        "Flow %s not found in persistent storage", flowId)));
    }

    protected FlowRepository setupFlowRepositorySpy() {
        if (flowRepositorySpy == null) {
            flowRepositorySpy = spy(persistenceManager.getRepositoryFactory().createFlowRepository());
            when(repositoryFactory.createFlowRepository()).thenReturn(flowRepositorySpy);
        }
        return flowRepositorySpy;
    }

    protected FlowPathRepository setupFlowPathRepositorySpy() {
        if (flowPathRepositorySpy == null) {
            flowPathRepositorySpy = spy(persistenceManager.getRepositoryFactory().createFlowPathRepository());
            when(repositoryFactory.createFlowPathRepository()).thenReturn(flowPathRepositorySpy);
        }
        return flowPathRepositorySpy;
    }

    protected IslRepository setupIslRepositorySpy() {
        if (islRepositorySpy == null) {
            islRepositorySpy = spy(persistenceManager.getRepositoryFactory().createIslRepository());
            when(repositoryFactory.createIslRepository()).thenReturn(islRepositorySpy);
        }
        return islRepositorySpy;
    }

    protected Flow verifyFlowStatus(String flowId, FlowStatus expectedStatus) {
        Flow flow = fetchFlow(flowId);
        assertEquals(expectedStatus, flow.getStatus());
        return flow;
    }

    protected void verifyFlowPathStatus(FlowPath path, FlowPathStatus expectedStatus, String name) {
        Assert.assertNotNull(String.format("%s flow path not defined (is null)", name), path);
        Assert.assertSame(
                String.format("%s flow path status is invalid", name),
                expectedStatus, path.getStatus());
    }

    protected void verifyNorthboundSuccessResponse(FlowGenericCarrier carrierMock) {
        verifyNorthboundSuccessResponse(carrierMock, FlowResponse.class);
    }

    protected void verifyNorthboundSuccessResponse(FlowGenericCarrier carrierMock, Class<?> expectedPayloadType) {
        ArgumentCaptor<Message> responseCaptor = ArgumentCaptor.forClass(Message.class);
        verify(carrierMock).sendNorthboundResponse(responseCaptor.capture());

        Message rawResponse = responseCaptor.getValue();
        Assert.assertNotNull(rawResponse);
        Assert.assertTrue(rawResponse instanceof InfoMessage);

        InfoData rawPayload = ((InfoMessage) rawResponse).getData();
        Assert.assertTrue(expectedPayloadType.isInstance(rawPayload));
    }

    protected void verifyNorthboundErrorResponse(FlowGenericCarrier carrier, ErrorType expectedErrorType) {
        ArgumentCaptor<Message> responseCaptor = ArgumentCaptor.forClass(Message.class);
        verify(carrier).sendNorthboundResponse(responseCaptor.capture());

        Message rawResponse = responseCaptor.getValue();
        Assert.assertNotNull(rawResponse);
        Assert.assertTrue(rawResponse instanceof ErrorMessage);
        ErrorMessage response = (ErrorMessage) rawResponse;

        Assert.assertSame(expectedErrorType, response.getData().getErrorType());
    }

    protected void verifyNoSpeakerInteraction(FlowGenericCarrier carrier) {
        verify(carrier, never()).sendSpeakerRequest(any());
    }

    protected void verifyNoPathReplace(Flow origin, Flow result) {
        Assert.assertEquals(origin.getForwardPathId(), result.getForwardPathId());
        Assert.assertEquals(origin.getReversePathId(), result.getReversePathId());
    }

    protected void verifyPathReplace(Flow origin, Flow result) {
        Assert.assertNotEquals(origin.getForwardPathId(), result.getForwardPathId());
        Assert.assertNotEquals(origin.getReversePathId(), result.getReversePathId());
    }

    protected void alterFeatureToggles(Boolean isCreateAllowed, Boolean isUpdateAllowed, Boolean isDeleteAllowed) {
        FeatureTogglesRepository repository = persistenceManager
                .getRepositoryFactory().createFeatureTogglesRepository();

        FeatureToggles toggles = repository.find()
                .orElseGet(() -> {
                    FeatureToggles newToggles = FeatureToggles.builder().build();
                    repository.add(newToggles);
                    return newToggles;
                });

        if (isCreateAllowed != null) {
            toggles.setCreateFlowEnabled(isCreateAllowed);
        }
        if (isUpdateAllowed != null) {
            toggles.setUpdateFlowEnabled(isUpdateAllowed);
        }
        if (isDeleteAllowed != null) {
            toggles.setDeleteFlowEnabled(isDeleteAllowed);
        }
    }

    protected FlowRequest.FlowRequestBuilder makeRequest() {
        return FlowRequest.builder()
                .bandwidth(1000L)
                .sourceSwitch(flowSource.getSwitchId())
                .sourcePort(flowSource.getPortNumber())
                .sourceVlan(flowSource.getVlanId())
                .destinationSwitch(flowDestination.getSwitchId())
                .destinationPort(flowDestination.getPortNumber())
                .destinationVlan(flowDestination.getVlanId());
    }

    protected PathPair makeOneSwitchPathPair() {
        return PathPair.builder()
                .forward(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(Collections.emptyList())
                        .build())
                .reverse(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(Collections.emptyList())
                        .build())
                .build();
    }

    protected PathPair make2SwitchesPathPair() {
        return makeSourceDestPathPair(islSourceDest);
    }

    protected PathPair make2SwitchAltPathPair() {
        return makeSourceDestPathPair(islSourceDestAlt);
    }

    private PathPair makeSourceDestPathPair(IslDirectionalReference islReference) {
        List<Segment> forwardSegments = ImmutableList.of(
                makePathSegment(islReference));
        List<Segment> reverseSegments = ImmutableList.of(
                makePathSegment(islReference.makeOpposite()));

        return PathPair.builder()
                .forward(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_DEST)
                        .segments(forwardSegments)
                        .build())
                .reverse(Path.builder()
                        .srcSwitchId(SWITCH_DEST)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(reverseSegments)
                        .build())
                .build();
    }

    protected PathPair make3SwitchesPathPair() {
        List<Segment> forwardSegments = ImmutableList.of(
                makePathSegment(islSourceTransit),
                makePathSegment(islTransitDest));
        List<Segment> reverseSegments = ImmutableList.of(
                makePathSegment(islTransitDest.makeOpposite()),
                makePathSegment(islSourceTransit.makeOpposite()));

        return PathPair.builder()
                .forward(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_DEST)
                        .segments(forwardSegments)
                        .build())
                .reverse(Path.builder()
                        .srcSwitchId(SWITCH_DEST)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(reverseSegments)
                        .build())
                .build();
    }

    private Segment makePathSegment(IslDirectionalReference reference) {
        IslEndpoint source = reference.getSourceEndpoint();
        IslEndpoint dest = reference.getDestEndpoint();
        return Segment.builder()
                .srcSwitchId(source.getSwitchId())
                .srcPort(source.getPortNumber())
                .destSwitchId(dest.getSwitchId())
                .destPort(dest.getPortNumber())
                .build();
    }

    protected Flow makeFlow() {
        return makeFlow(flowSource, flowDestination);
    }

    private Flow makeFlow(FlowEndpoint source, FlowEndpoint dest) {
        return new Flow(dummyFactory.makeFlow(source, dest, islSourceDest));
    }

    protected Flow makeFlowArgumentMatch(String flowId) {
        return MockitoHamcrest.argThat(
                Matchers.hasProperty("flowId", is(flowId)));
    }
}
