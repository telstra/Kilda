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

package org.openkilda.server42.control.serverstub;

import org.openkilda.server42.control.messaging.flowrtt.Control.AddFlow;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacket;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacketResponse;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacketResponse.Builder;
import org.openkilda.server42.control.messaging.flowrtt.Control.Flow;
import org.openkilda.server42.control.messaging.flowrtt.Control.PushSettings;
import org.openkilda.server42.control.messaging.flowrtt.Control.RemoveFlow;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import java.util.HashMap;
import javax.annotation.PostConstruct;


/**
 * The Server class implements a ZeroMQ server that simply pretends to be the full server42 C++ implementation with
 * DPDK support. Also, they must emulate the network part of flow RTT.
 */
@Service
@Slf4j
public class ControlServer extends Thread {

    private HashMap<String, Flow> flows = new HashMap<>();

    @Value("${openkilda.server42.control.zeromq.control.server.endpoint}")
    private String bindEndpoint;

    private StatsServer statsServer;

    @Autowired
    public ControlServer(StatsServer statsServer) {
        this.statsServer = statsServer;
    }


    /**
     * We get commands from zmq socket and execute them one by one.
     */
    @Override
    public void run() {
        log.info("started");
        try (ZContext context = new ZContext()) {
            Socket server = context.createSocket(ZMQ.REP);
            server.bind(bindEndpoint);
            while (!isInterrupted()) {
                byte[] request = server.recv();
                try {
                    CommandPacket commandPacket = CommandPacket.parseFrom(request);

                    Builder builder = CommandPacketResponse.newBuilder();
                    builder.setCommunicationId(commandPacket.getCommunicationId());
                    log.info("command type {}", commandPacket.getType().toString());
                    log.info("flow list before {}", flows.keySet().toString());
                    switch (commandPacket.getType()) {
                        case ADD_FLOW:
                            for (Any any : commandPacket.getCommandList()) {
                                AddFlow addFlow = any.unpack(AddFlow.class);
                                flows.put(addFlow.getFlow().getFlowId(), addFlow.getFlow());
                                statsServer.addFlow(addFlow.getFlow());
                            }
                            break;
                        case REMOVE_FLOW:
                            for (Any any : commandPacket.getCommandList()) {
                                RemoveFlow removeFlow = any.unpack(RemoveFlow.class);
                                flows.remove(removeFlow.getFlow().getFlowId());
                                statsServer.removeFlow(removeFlow.getFlow().getFlowId());
                            }
                            break;
                        case CLEAR_FLOWS:
                            flows.clear();
                            statsServer.clearFlows();
                            break;
                        case LIST_FLOWS:
                            for (Flow flow : flows.values()) {
                                builder.addResponse(Any.pack(flow));
                            }
                            break;
                        case PUSH_SETTINGS:
                            log.warn("will be shipped with stats application");
                            Any command = commandPacket.getCommand(0);
                            PushSettings settings = command.unpack(PushSettings.class);
                            log.info(settings.toString());
                            break;
                        case UNRECOGNIZED:
                        default:
                            log.error("Unknown command type");
                            break;
                    }
                    log.info("flow list after {}", flows.keySet().toString());
                    server.send(builder.build().toByteArray());
                } catch (InvalidProtocolBufferException e) {
                    log.error("marshalling error");
                }
            }
        }
    }

    @PostConstruct
    void init() {
        this.start();
    }
}
