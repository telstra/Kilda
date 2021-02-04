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

package org.openkilda.server42.control.kafka;

import org.openkilda.model.SwitchId;
import org.openkilda.server42.control.config.SwitchToVlanMapping;
import org.openkilda.server42.control.messaging.flowrtt.AddFlow;
import org.openkilda.server42.control.messaging.flowrtt.ClearFlows;
import org.openkilda.server42.control.messaging.flowrtt.Control;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacket;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacket.Builder;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacket.Type;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacketResponse;
import org.openkilda.server42.control.messaging.flowrtt.Control.Flow;
import org.openkilda.server42.control.messaging.flowrtt.Control.Flow.EncapsulationType;
import org.openkilda.server42.control.messaging.flowrtt.ListFlowsOnSwitch;
import org.openkilda.server42.control.messaging.flowrtt.ListFlowsRequest;
import org.openkilda.server42.control.messaging.flowrtt.ListFlowsResponse;
import org.openkilda.server42.control.messaging.flowrtt.PushSettings;
import org.openkilda.server42.control.messaging.flowrtt.RemoveFlow;
import org.openkilda.server42.control.zeromq.ZeroMqClient;
import org.openkilda.server42.messaging.FlowDirection;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Main class for that server. It listen messages from storm by kafka filtered by KafkaRecordFilter than repack it
 * to protobuf and retranslate it to Server42cpp application by ZeroMq.
 */
@Service
@Slf4j
@KafkaListener(id = "server42-control",
        topics = "${openkilda.server42.control.kafka.topic.from_storm}",
        idIsGroup = false
)
public class Gate {

    private final KafkaTemplate<String, Object> template;

    private final ZeroMqClient zeroMqClient;

    @Value("${openkilda.server42.control.kafka.topic.to_storm}")
    private String toStorm;

    @Value("${openkilda.server42.control.flow_rtt.udp_src_port_offset}")
    private Integer udpSrcPortOffset;

    private Map<String, Long> switchToVlanMap;

    public Gate(@Autowired KafkaTemplate<String, Object> template,
                @Autowired ZeroMqClient zeroMqClient,
                @Autowired SwitchToVlanMapping switchToVlanMapping
    ) {
        this.template = template;
        this.zeroMqClient = zeroMqClient;
        this.switchToVlanMap = switchToVlanMapping.getVlan().entrySet().stream().flatMap(
                vlanToSwitches -> vlanToSwitches.getValue().stream().map(
                        switchId -> new SimpleEntry<>(switchId, vlanToSwitches.getKey()))
        ).collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue));
    }

    @KafkaHandler
    void listen(@Payload AddFlow data,
                        @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String switchIdKey) {

        SwitchId switchId = new SwitchId(switchIdKey);

        Builder builder = CommandPacket.newBuilder();
        Flow flow = Flow.newBuilder()
                .setFlowId(data.getFlowId())
                .setEncapsulationType(EncapsulationType.VLAN)
                .setTunnelId(data.getTunnelId())
                .setTransitEncapsulationType(EncapsulationType.VLAN)
                .setInnerTunnelId(data.getInnerTunnelId())
                .setTransitTunnelId(switchToVlanMap.get(switchIdKey))
                .setDirection(FlowDirection.toBoolean(data.getDirection()))
                .setUdpSrcPort(udpSrcPortOffset + data.getPort())
                .setDstMac(switchId.toMacAddress())
                .setHashCode(data.hashCode())
                .build();

        Control.AddFlow addFlow = Control.AddFlow.newBuilder().setFlow(flow).build();
        builder.setType(Type.ADD_FLOW);
        builder.addCommand(Any.pack(addFlow));
        CommandPacket packet = builder.build();
        try {
            zeroMqClient.send(packet);
        } catch (InvalidProtocolBufferException e) {
            log.error("Marshalling error on {}", data);
        }
    }

    @KafkaHandler
    void listen(ClearFlows data,
                @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String switchIdKey) {
        Builder builder = CommandPacket.newBuilder();
        builder.setType(Type.CLEAR_FLOWS);
        SwitchId switchId = new SwitchId(switchIdKey);
        Control.ClearFlowsFilter clearFlowsFilter = Control.ClearFlowsFilter.newBuilder()
                .setDstMac(switchId.toMacAddress()).build();
        builder.addCommand(Any.pack(clearFlowsFilter));
        try {
            zeroMqClient.send(builder.build());
        } catch (InvalidProtocolBufferException e) {
            log.error("Marshalling error on {}", data);
        }
    }

    @KafkaHandler
    void listen(ListFlowsRequest data,
                @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String switchIdKey) {
        CommandPacket commandPacket = getFlowListCommandPacket(switchIdKey);
        try {
            CommandPacketResponse serverResponse = zeroMqClient.send(commandPacket);

            if (serverResponse == null) {
                log.error("No response from server on {}", data.getHeaders().getCorrelationId());
                return;
            }

            HashSet<String> flowList = new HashSet<>();
            for (Any any : serverResponse.getResponseList()) {
                flowList.add(any.unpack(Flow.class).getFlowId());
            }

            ListFlowsResponse response = ListFlowsResponse.builder()
                    .headers(data.getHeaders())
                    .flowIds(flowList).build();

            template.send(toStorm, response);
        } catch (InvalidProtocolBufferException e) {
            log.error("Marshalling error on {}", data);
        }
    }

    @KafkaHandler
    void listen(ListFlowsOnSwitch data,
                @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String switchIdKey) {

        CommandPacket commandPacket = getFlowListCommandPacket(switchIdKey);

        try {
            CommandPacketResponse serverResponse = zeroMqClient.send(commandPacket);
            if (serverResponse == null) {
                log.error("No response from server on {}", data.getHeaders().getCorrelationId());
                return;
            }

            for (Any any : serverResponse.getResponseList()) {
                String flowId = any.unpack(Flow.class).getFlowId();
                if (!data.getFlowIds().contains(flowId)) {
                    removeFlow(flowId, FlowDirection.FORWARD);
                    removeFlow(flowId, FlowDirection.REVERSE);
                }
            }

        } catch (InvalidProtocolBufferException e) {
            log.error("Marshalling error on {}", data);
        }
    }

    @KafkaHandler
    void listen(PushSettings data) {
        Builder builder = CommandPacket.newBuilder();
        Control.PushSettings pushSettings = Control.PushSettings.newBuilder()
                .setPacketGenerationIntervalInMs(data.getPacketGenerationIntervalInMs()).build();
        builder.setType(Type.PUSH_SETTINGS);
        builder.addCommand(Any.pack(pushSettings));
        CommandPacket packet = builder.build();
        try {
            zeroMqClient.send(packet);
        } catch (InvalidProtocolBufferException e) {
            log.error("Marshalling error on {}", data);
        }
    }

    @KafkaHandler
    void listen(RemoveFlow data) {
        try {
            removeFlow(data.getFlowId(), data.getDirection());
        } catch (InvalidProtocolBufferException e) {
            log.error("Marshalling error on {}", data);
        }
    }

    private void removeFlow(String flowId, FlowDirection direction) throws InvalidProtocolBufferException {
        Builder builder = CommandPacket.newBuilder();
        Flow flow = Flow.newBuilder()
                .setFlowId(flowId)
                .setDirection(FlowDirection.toBoolean(direction))
                .build();
        Control.RemoveFlow removeFlow = Control.RemoveFlow.newBuilder().setFlow(flow).build();
        builder.setType(Type.REMOVE_FLOW);
        builder.addCommand(Any.pack(removeFlow));
        CommandPacket packet = builder.build();
        zeroMqClient.send(packet);
    }

    private CommandPacket getFlowListCommandPacket(String switchIdKey) {
        SwitchId switchId = new SwitchId(switchIdKey);
        Builder builder = CommandPacket.newBuilder();
        builder.setType(Type.LIST_FLOWS);

        Control.ListFlowsFilter listFlowsFilter = Control.ListFlowsFilter.newBuilder()
                .setDstMac(switchId.toMacAddress()).build();
        builder.addCommand(Any.pack(listFlowsFilter));
        return builder.build();
    }
}
