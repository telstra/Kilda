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


package org.openkilda.server42.control.zeromq;

import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacket;
import org.openkilda.server42.control.messaging.flowrtt.Control.CommandPacketResponse;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;
import zmq.ZError;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

// Lazy Pirate pattern from http://zguide.zeromq.org/page:all#Client-Side-Reliability-Lazy-Pirate-Pattern
@Component
@Slf4j
public class ZeroMqClient {

    private ZContext ctx;
    private Socket client;
    private Poller poller;

    @Value("${openkilda.server42.control.zeromq.server.endpoint}")
    private String serverEndpoint;

    @Value("${openkilda.server42.control.zeromq.request.timeout}")
    private Long requestTimeout;

    @Value("${openkilda.server42.control.zeromq.request.retries}")
    private Long requestRetries;

    @PostConstruct
    private void init() {
        ctx = new ZContext();
    }


    @PreDestroy
    private void clear() {
        ctx.close();
    }

    /**
     * Send message to server and return result is success.
     * @return CommandPacketResponse or null in case of error
     */
    public CommandPacketResponse send(CommandPacket commandPacket) throws InvalidProtocolBufferException {

        byte[] response = sendRequest(commandPacket.toByteArray());

        if (response == null) {
            return null;
        }

        return CommandPacketResponse.parseFrom(response);
    }

    private byte[] sendRequest(byte[] request) {
        long retriesLeft = requestRetries;
        while (retriesLeft > 0 && !Thread.currentThread().isInterrupted()) {
            try {
                if (client == null) {
                    reconnect();
                }

                client.send(request);

                int rc = poller.poll(requestTimeout);
                if (rc == -1) {
                    break; //  Interrupted
                }
                if (poller.pollin(0)) {
                    return client.recv();
                } else if (--retriesLeft == 0) {
                    log.error("server seems to be offline, abandoning\n");
                    break;
                } else {
                    log.warn("no response from server, retrying");
                    //  Old socket is confused; close it and open a new one
                    reconnect();

                    //  Send request again, on new socket
                    client.send(request);
                }
            } catch (org.zeromq.ZMQException ex) {
                log.error(ex.toString());
                if (ex.getErrorCode() == ZError.EFSM) {
                    reconnect();
                }
            }
        }
        return null;
    }

    private void reconnect() {
        if (client != null) {
            if (poller != null) {
                poller.unregister(client);
            }
            ctx.destroySocket(client);
        }
        log.info("reconnecting to server {}", serverEndpoint);
        client = ctx.createSocket(ZMQ.REQ);
        client.connect(serverEndpoint);
        if (poller == null) {
            poller = ctx.createPoller(1);
        }
        poller.register(client, Poller.POLLIN);
    }
}
