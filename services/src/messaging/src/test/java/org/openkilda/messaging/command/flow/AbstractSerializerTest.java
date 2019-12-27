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

package org.openkilda.messaging.command.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.openkilda.messaging.command.Constants.flowName;

import org.openkilda.messaging.AbstractSerializer;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.HealthCheckCommandData;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.NetworkInfoData;
import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.info.event.PortChangeType;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.info.event.SwitchChangeType;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.info.flow.FlowReadResponse;
import org.openkilda.messaging.info.flow.FlowRerouteResponse;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.info.flow.FlowStatusResponse;
import org.openkilda.messaging.info.flow.FlowsResponse;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.model.FlowPairDto;
import org.openkilda.messaging.model.SpeakerSwitchDescription;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.Metadata;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Ignore
public abstract class AbstractSerializerTest implements AbstractSerializer {
    private static final String FLOW_NAME = "test_flow";
    private static final PathId PATH_ID = new PathId("path_name");
    private static final SwitchId SWITCH_ID = new SwitchId("00:00:00:00:00:00:00:00");
    private static final SwitchId EGRESS_SWITCH_ID = new SwitchId("00:00:00:00:00:00:00:00");
    private static final String CORRELATION_ID = UUID.randomUUID().toString();
    private static final UUID TRANSACTION_ID = UUID.randomUUID();
    private static final long TIMESTAMP = System.currentTimeMillis();
    private static final int INPUT_PORT = 1;
    private static final int OUTPUT_PORT = 2;
    private static final int INPUT_VLAN_ID = 101;
    private static final int OUTPUT_VLAN_ID = 102;
    private static final int TRANSIT_ENCAPSULATION_ID = 103;
    private static final FlowEncapsulationType TRANSIT_ENCAPSULATION_TYPE = FlowEncapsulationType.TRANSIT_VLAN;
    private static final long BANDWIDTH = 10000L;
    private static final long COOKIE = 0x1L;
    private static final Long METER_ID = null;
    private static final OutputVlanType OUTPUT_VLAN_TYPE = OutputVlanType.REPLACE;
    private static final FlowState FLOW_STATUS = FlowState.UP;
    private static final PortChangeType PORT_CHANGE = PortChangeType.OTHER_UPDATE;
    private static final SwitchChangeType SWITCH_EVENT = SwitchChangeType.CHANGED;
    private static final Destination DESTINATION = null;

    private static final FlowIdStatusPayload flowIdStatusResponse = new FlowIdStatusPayload(FLOW_NAME, FLOW_STATUS);

    private static final String requester = "requester-id";
    private static final SwitchInfoData sw1 = new SwitchInfoData(new SwitchId("ff:01"),
            SwitchChangeType.ACTIVATED, "1.1.1.1", "ff:01", "switch-1", "kilda", false);
    private static final SwitchInfoData sw2 = new SwitchInfoData(new SwitchId("ff:02"),
            SwitchChangeType.ACTIVATED, "2.2.2.2", "ff:02", "switch-2", "kilda", false);
    private static final List<PathNode> nodes = Arrays.asList(
            new PathNode(new SwitchId("ff:01"), 1, 0, 0L),
            new PathNode(new SwitchId("ff:02"), 2, 1, 0L));
    private static final IslInfoData isl = IslInfoData.builder()
            .latency(0L)
            .source(nodes.get(0))
            .destination(nodes.get(1))
            .speed(1000L)
            .state(IslChangeType.DISCOVERED)
            .availableBandwidth(900L)
            .underMaintenance(false)
            .build();

    private static final PathInfoData path = new PathInfoData(0L, nodes);
    private static final FlowDto flowModel = FlowDto.builder()
            .flowId(FLOW_NAME)
            .bandwidth(1000)
            .ignoreBandwidth(false)
            .periodicPings(false)
            .cookie(COOKIE)
            .lastUpdated(String.valueOf(TIMESTAMP))
            .sourceSwitch(new SwitchId("ff:01")).sourcePort(10).sourcePort(100)
            .destinationSwitch(new SwitchId("ff:02")).destinationPort(20).destinationVlan(200)
            .meterId(1)
            .transitEncapsulationId(1024)
            .state(FLOW_STATUS)
            .build();

    @Test
    public void serializeInstallEgressFlowMessageTest() throws IOException, ClassNotFoundException {
        InstallEgressFlow data = new InstallEgressFlow(TRANSACTION_ID, FLOW_NAME, COOKIE,
                SWITCH_ID, INPUT_PORT, OUTPUT_PORT, TRANSIT_ENCAPSULATION_ID, TRANSIT_ENCAPSULATION_TYPE,
                OUTPUT_VLAN_ID, OUTPUT_VLAN_TYPE, false, new HashSet<>(), Metadata.builder().build());
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof InstallEgressFlow);

        InstallEgressFlow resultData = (InstallEgressFlow) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void serializeInstallIngressFlowMessageTest() throws IOException, ClassNotFoundException {
        InstallIngressFlow data = new InstallIngressFlow(TRANSACTION_ID, FLOW_NAME, COOKIE, SWITCH_ID,
                INPUT_PORT, OUTPUT_PORT, INPUT_VLAN_ID, TRANSIT_ENCAPSULATION_ID, TRANSIT_ENCAPSULATION_TYPE,
                OUTPUT_VLAN_TYPE, BANDWIDTH, METER_ID, true, EGRESS_SWITCH_ID, false, false, new HashSet<>(),
                Metadata.builder().build(), null);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof InstallIngressFlow);

        InstallIngressFlow resultData = (InstallIngressFlow) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void serializeInstallTransitFlowMessageTest() throws IOException, ClassNotFoundException {
        InstallTransitFlow data = new InstallTransitFlow(TRANSACTION_ID, FLOW_NAME, COOKIE,
                SWITCH_ID, INPUT_PORT, OUTPUT_PORT, TRANSIT_ENCAPSULATION_ID, TRANSIT_ENCAPSULATION_TYPE, false);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof InstallTransitFlow);

        InstallTransitFlow resultData = (InstallTransitFlow) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void serializeInstallOneSwitchFlowMessageTest() throws IOException, ClassNotFoundException {
        InstallOneSwitchFlow data = new InstallOneSwitchFlow(TRANSACTION_ID, FLOW_NAME, COOKIE, SWITCH_ID, INPUT_PORT,
                OUTPUT_PORT, INPUT_VLAN_ID, OUTPUT_VLAN_ID, OUTPUT_VLAN_TYPE, BANDWIDTH, METER_ID, true,
                false, false, new HashSet<>(), Metadata.builder().build());
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof InstallOneSwitchFlow);

        InstallOneSwitchFlow resultData = (InstallOneSwitchFlow) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void flowCreateRequestTest() throws IOException, ClassNotFoundException {
        FlowCreateRequest data = new FlowCreateRequest(flowModel);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof FlowCreateRequest);

        FlowCreateRequest resultData = (FlowCreateRequest) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(flowModel.hashCode(), resultData.getPayload().hashCode());
    }

    @Test
    public void flowUpdateRequestTest() throws IOException, ClassNotFoundException {
        FlowUpdateRequest data = new FlowUpdateRequest(flowModel);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof FlowUpdateRequest);

        FlowUpdateRequest resultData = (FlowUpdateRequest) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(flowModel.hashCode(), resultData.getPayload().hashCode());
    }

    @Test
    public void flowDeleteRequestTest() throws IOException, ClassNotFoundException {
        FlowDeleteRequest data = new FlowDeleteRequest(flowName);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof FlowDeleteRequest);

        FlowDeleteRequest resultData = (FlowDeleteRequest) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(data.getFlowId(), resultData.getFlowId());
    }

    @Test
    public void flowGetBidirectionalRequestTest() throws IOException, ClassNotFoundException {
        FlowReadRequest data = new FlowReadRequest(FLOW_NAME);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() instanceof FlowReadRequest);

        FlowReadRequest resultData = (FlowReadRequest) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(FLOW_NAME, resultData.getFlowId());
    }

    @Test
    public void flowGetFlowReadResponseTest() throws IOException, ClassNotFoundException {
        FlowReadResponse data = new FlowReadResponse(flowModel, Collections.singletonList(FLOW_NAME));
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof FlowReadResponse);

        FlowReadResponse resultData = (FlowReadResponse) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void flowRerouteResponseTest() throws IOException, ClassNotFoundException {
        FlowRerouteResponse data = new FlowRerouteResponse(path, true);
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof FlowRerouteResponse);

        FlowRerouteResponse resultData = (FlowRerouteResponse) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(path.hashCode(), resultData.getPayload().hashCode());
    }

    @Test
    public void flowStatusResponseTest() throws IOException, ClassNotFoundException {
        FlowStatusResponse data = new FlowStatusResponse(flowIdStatusResponse);
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof FlowStatusResponse);

        FlowStatusResponse resultData = (FlowStatusResponse) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(flowIdStatusResponse.hashCode(), resultData.getPayload().hashCode());
    }

    @Test
    public void flowResponseTest() throws IOException, ClassNotFoundException {
        FlowResponse data = new FlowResponse(flowModel);
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof FlowResponse);

        FlowResponse resultData = (FlowResponse) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(flowModel.hashCode(), resultData.getPayload().hashCode());
    }

    @Test
    public void flowsResponseTest() throws IOException, ClassNotFoundException {
        FlowsResponse data = new FlowsResponse(Collections.singletonList(flowModel.getFlowId()));
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof FlowsResponse);

        FlowsResponse resultData = (FlowsResponse) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(Collections.singletonList(flowModel.getFlowId()).hashCode(), resultData.getFlowIds().hashCode());
    }

    @Test
    public void eventIslInfoTest() throws IOException, ClassNotFoundException {
        PathNode payload = new PathNode(SWITCH_ID, INPUT_PORT, 0);
        IslInfoData data = IslInfoData.builder()
                .latency(0L)
                .source(payload)
                .destination(payload)
                .speed(1000000L)
                .state(IslChangeType.DISCOVERED)
                .availableBandwidth(900000L)
                .underMaintenance(false)
                .build();

        assertEquals(SWITCH_ID + "_" + String.valueOf(INPUT_PORT), data.getId());
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof IslInfoData);

        IslInfoData resultData = (IslInfoData) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
        assertEquals(payload.hashCode(), resultData.getSource().hashCode());
    }

    @Test
    public void eventPathInfoTest() throws IOException, ClassNotFoundException {
        PathInfoData data = new PathInfoData();
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof PathInfoData);

        PathInfoData resultData = (PathInfoData) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void eventPortInfoTest() throws IOException, ClassNotFoundException {
        PortInfoData data = new PortInfoData(SWITCH_ID, INPUT_PORT, PORT_CHANGE);
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof PortInfoData);

        PortInfoData resultData = (PortInfoData) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void eventSwitchInfoTest() throws IOException, ClassNotFoundException {
        SpeakerSwitchDescription description = new SpeakerSwitchDescription(
                "OF switch manufacturer",
                "OF cappable HW",
                "software",
                "aabbcc",
                "datapath description");
        SpeakerSwitchView switchView = new SpeakerSwitchView(
                SWITCH_ID,
                new InetSocketAddress(Inet4Address.getByName("127.1.0.1"), 40001),
                new InetSocketAddress(Inet4Address.getByName("127.1.0.254"), 6653),
                "OF_13",
                description, Collections.emptySet(), Collections.emptyList());
        SwitchInfoData data = new SwitchInfoData(
                SWITCH_ID, SWITCH_EVENT, "127.0.0.1", "localhost", "sw", "controller", false, switchView);
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION, null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() instanceof SwitchInfoData);

        SwitchInfoData resultData = (SwitchInfoData) resultInfo.getData();
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void errorMessageTest() throws IOException, ClassNotFoundException {
        ErrorData data = new ErrorData(ErrorType.AUTH_FAILED, FLOW_NAME, "Bad credentials");
        System.out.println(data);

        ErrorMessage info = new ErrorMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        info.setData(data);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof ErrorMessage);

        ErrorMessage resultInfo = (ErrorMessage) message;
        assertTrue(resultInfo.getData() != null);

        ErrorData resultData = resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void removeCommandTest() throws IOException, ClassNotFoundException {
        RemoveFlow data = RemoveFlow.builder()
                .transactionId(TRANSACTION_ID)
                .flowId(FLOW_NAME)
                .cookie(COOKIE)
                .switchId(SWITCH_ID)
                .meterId(METER_ID)
                .criteria(DeleteRulesCriteria.builder().cookie(COOKIE).build())
                .build();

        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        command.setData(data);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() != null);

        RemoveFlow resultData = (RemoveFlow) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void flowRerouteCommandTest() throws IOException, ClassNotFoundException {
        FlowRerouteRequest data = new FlowRerouteRequest(FLOW_NAME, false, Collections.singleton(PATH_ID), null);
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        command.setData(data);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() != null);

        FlowRerouteRequest resultData = (FlowRerouteRequest) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void dumpNetworkCommandTest() throws IOException, ClassNotFoundException {
        HealthCheckCommandData data = new HealthCheckCommandData("requester");
        System.out.println(data);

        CommandMessage command = new CommandMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION);
        command.setData(data);
        serialize(command);

        Message message = (Message) deserialize();
        assertTrue(message instanceof CommandMessage);

        CommandMessage resultCommand = (CommandMessage) message;
        assertTrue(resultCommand.getData() != null);

        HealthCheckCommandData resultData = (HealthCheckCommandData) resultCommand.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }

    @Test
    public void dumpNetworkResponseTest() throws IOException, ClassNotFoundException {
        NetworkInfoData data = new NetworkInfoData(requester,
                new HashSet<>(Arrays.asList(sw1, sw2)),
                new HashSet<>(),
                Collections.singleton(isl),
                Collections.singleton(new FlowPairDto<>(flowModel, flowModel)));
        System.out.println(data);

        InfoMessage info = new InfoMessage(data, System.currentTimeMillis(), CORRELATION_ID, DESTINATION,
                null);
        serialize(info);

        Message message = (Message) deserialize();
        assertTrue(message instanceof InfoMessage);

        InfoMessage resultInfo = (InfoMessage) message;
        assertTrue(resultInfo.getData() != null);

        NetworkInfoData resultData = (NetworkInfoData) resultInfo.getData();
        System.out.println(resultData);
        assertEquals(data, resultData);
        assertEquals(data.hashCode(), resultData.hashCode());
    }
}
