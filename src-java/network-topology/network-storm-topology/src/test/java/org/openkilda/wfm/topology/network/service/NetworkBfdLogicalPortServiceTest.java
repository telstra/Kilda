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

package org.openkilda.wfm.topology.network.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.messaging.info.grpc.CreateLogicalPortResponse;
import org.openkilda.messaging.model.grpc.LogicalPort;
import org.openkilda.messaging.model.grpc.LogicalPortType;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.error.ControllerNotFoundException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;

@RunWith(MockitoJUnitRunner.class)
public class NetworkBfdLogicalPortServiceTest {
    private static final int LOGICAL_PORT_OFFSET = 200;

    private static final Endpoint physical = Endpoint.of(new SwitchId(1), 2);
    private static final Endpoint logical = Endpoint.of(
            physical.getDatapath(), physical.getPortNumber() + LOGICAL_PORT_OFFSET);
    private static final Endpoint remotePhysical = Endpoint.of(new SwitchId(3), 4);

    private static final IslReference reference = new IslReference(physical, remotePhysical);
    private static final BfdProperties propertiesEnabled = new BfdProperties(Duration.ofMillis(350), (short) 3);
    private static final BfdProperties propertiesDisabled = new BfdProperties();

    @Mock
    private IBfdLogicalPortCarrier carrier;

    @Test
    public void greenField() {
        NetworkBfdLogicalPortService service = makeService();

        final String createRequestId = "port-create-request";
        when(carrier.createLogicalPort(eq(logical), eq(physical.getPortNumber()))).thenReturn(createRequestId);
        service.apply(physical, reference, propertiesEnabled);
        verify(carrier).logicalPortControllerAddNotification(eq(physical));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        service.updateOnlineStatus(physical.getDatapath(), true);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        CreateLogicalPortResponse createResponse = new CreateLogicalPortResponse(
                "127.0.1.1",
                LogicalPort.builder()
                        .portNumber(physical.getPortNumber())
                        .logicalPortNumber(logical.getPortNumber())
                        .type(LogicalPortType.BFD)
                        .name(String.format("P%d", logical.getPortNumber()))
                        .build(),
                true);
        service.workerSuccess(createRequestId, logical, createResponse);
        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);

        verifyGenericWorkflow(service);
    }

    @Test
    public void brownField() {
        NetworkBfdLogicalPortService service = makeService();

        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);
        service.apply(physical, reference, propertiesEnabled);
    }

    private void verifyGenericWorkflow(NetworkBfdLogicalPortService service) {
        verify(carrier).createSession(eq(logical), eq(physical.getPortNumber()));
        verify(carrier).enableUpdateSession(eq(physical), eq(reference), eq(propertiesEnabled));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // proxy offline
        service.updateOnlineStatus(physical.getDatapath(), false);
        verify(carrier).updateSessionOnlineStatus(eq(logical), eq(false));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // proxy online
        service.updateOnlineStatus(physical.getDatapath(), true);
        verify(carrier).updateSessionOnlineStatus(eq(logical), eq(true));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // proxy disable
        service.disable(physical);
        verify(carrier).disableSession(eq(physical));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // proxy
        service.delete(physical);
        verify(carrier).deleteSession(eq(logical));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // delete when session is over
        final String deleteRequestId = "port-delete-request";
        when(carrier.deleteLogicalPort(eq(logical))).thenReturn(deleteRequestId);
        service.sessionDeleted(physical);
        verify(carrier).deleteLogicalPort(eq(logical));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        service.portDel(logical);

        try {
            service.disable(physical);
            Assert.fail("Expect controller not found exception");
        } catch (ControllerNotFoundException e) {
            // expected
        }
    }

    @Test
    public void testApply() {
        NetworkBfdLogicalPortService service = makeService();

        service.portAdd(logical, physical.getPortNumber());
        verify(carrier).logicalPortControllerAddNotification(eq(physical));
        reset(carrier);

        service.updateOnlineStatus(logical, true);
        verify(carrier).createSession(eq(logical), eq(physical.getPortNumber()));
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        service.apply(physical, reference, propertiesDisabled);
        verify(carrier).disableSession(eq(physical));
        verifyNoMoreInteractions(carrier);

        service.apply(physical, reference, propertiesEnabled);
        verify(carrier).enableUpdateSession(eq(physical), eq(reference), eq(propertiesEnabled));
        reset(carrier);

        service.apply(physical, reference, propertiesDisabled);
        verify(carrier).disableSession(eq(physical));
    }

    @Test
    public void replacePropertiesDuringCreate() {
        NetworkBfdLogicalPortService service = makeService();
        service.updateOnlineStatus(physical.getDatapath(), true);

        final String requestId = "port-create-request";
        when(carrier.createLogicalPort(eq(logical), eq(physical.getPortNumber()))).thenReturn(requestId);
        service.apply(physical, reference, propertiesEnabled);
        reset(carrier);

        BfdProperties altProperties = new BfdProperties(
                Duration.ofMillis(propertiesEnabled.getInterval().toMillis() + 100),
                (short) (propertiesEnabled.getMultiplier() + 1));
        service.apply(physical, reference, altProperties);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
        verifyNoMoreInteractions(carrier);

        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);
        verify(carrier).enableUpdateSession(eq(physical), eq(reference), eq(altProperties));
    }

    @Test
    public void enableDuringCleanup() {
        NetworkBfdLogicalPortService service = makeService();

        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);
        service.apply(physical, reference, propertiesEnabled);
        verify(carrier).logicalPortControllerAddNotification(eq(physical));
        reset(carrier);

        final String deleteRequestId = "port-delete-request";
        when(carrier.deleteLogicalPort(eq(logical))).thenReturn(deleteRequestId);
        service.delete(physical);
        service.sessionDeleted(physical);
        verify(carrier).deleteLogicalPort(eq(logical));
        reset(carrier);

        final String createRequestId = "port-create-request";
        when(carrier.createLogicalPort(eq(logical), eq(physical.getPortNumber()))).thenReturn(createRequestId);
        BfdProperties altProperties = new BfdProperties(
                Duration.ofMillis(propertiesEnabled.getInterval().toMillis() + 100),
                (short) (propertiesEnabled.getMultiplier() + 1));
        service.apply(physical, reference, altProperties);
        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);

        verify(carrier).createSession(eq(logical), eq(physical.getPortNumber()));
        verify(carrier).enableUpdateSession(eq(physical), eq(reference), eq(altProperties));
    }

    @Test
    public void offlineDuringCreate() {
        NetworkBfdLogicalPortService service = makeService();
        service.updateOnlineStatus(physical.getDatapath(), true);

        final String requestId = "port-create-request";
        when(carrier.createLogicalPort(eq(logical), eq(physical.getPortNumber()))).thenReturn(requestId);
        service.apply(physical, reference, propertiesEnabled);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
        reset(carrier);

        service.updateOnlineStatus(physical.getDatapath(), false);
        verifyNoMoreInteractions(carrier);

        when(carrier.createLogicalPort(eq(logical), eq(physical.getPortNumber()))).thenReturn(requestId);
        service.updateOnlineStatus(physical.getDatapath(), true);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
    }

    @Test
    public void offlineDuringRemoving() {
        NetworkBfdLogicalPortService service = makeService();

        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);
        service.delete(physical);

        final String deleteRequestId = "port-delete-request";
        when(carrier.deleteLogicalPort(eq(logical))).thenReturn(deleteRequestId);
        service.sessionDeleted(physical);
        verify(carrier).deleteLogicalPort(eq(logical));
        reset(carrier);

        service.updateOnlineStatus(physical.getDatapath(), false);
        verifyNoMoreInteractions(carrier);

        when(carrier.deleteLogicalPort(eq(logical))).thenReturn(deleteRequestId);
        service.updateOnlineStatus(physical.getDatapath(), true);
        verify(carrier).deleteLogicalPort(eq(logical));
        reset(carrier);

        service.updateOnlineStatus(physical.getDatapath(), false);

        // recreate path
        service.apply(physical, reference, propertiesEnabled);
        verifyNoMoreInteractions(carrier);

        final String createRequestId = "port-create-request";
        when(carrier.createLogicalPort(eq(logical), eq(physical.getPortNumber()))).thenReturn(createRequestId);
        service.updateOnlineStatus(physical.getDatapath(), true);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
    }

    @Test
    public void portRecreateRaceConditionHandling() {
        NetworkBfdLogicalPortService service = makeService();
        service.updateOnlineStatus(physical.getDatapath(), true);

        service.portAdd(logical, physical.getPortNumber());
        verify(carrier).logicalPortControllerAddNotification(eq(physical));
        reset(carrier);

        final String deleteRequestId = "port-delete-request";
        when(carrier.deleteLogicalPort(eq(logical))).thenReturn(deleteRequestId);
        service.delete(physical);
        verify(carrier).deleteLogicalPort(eq(logical));
        reset(carrier);

        final String createRequestId = "port-create-request";
        when(carrier.deleteLogicalPort(eq(logical))).thenReturn(createRequestId);
        service.apply(physical, reference, propertiesEnabled);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
        reset(carrier);

        service.portDel(logical);
        verify(carrier).createLogicalPort(eq(logical), eq(physical.getPortNumber()));
        reset(carrier);

        service.portAdd(logical, physical.getPortNumber());
        service.updateOnlineStatus(logical, true);
        verify(carrier).createSession(eq(logical), eq(physical.getPortNumber()));
    }

    private NetworkBfdLogicalPortService makeService() {
        return new NetworkBfdLogicalPortService(carrier, LOGICAL_PORT_OFFSET);
    }
}
