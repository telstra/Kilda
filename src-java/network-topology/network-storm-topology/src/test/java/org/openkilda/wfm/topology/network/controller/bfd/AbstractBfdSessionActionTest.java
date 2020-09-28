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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.SwitchReference;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.model.Endpoint;

import org.junit.Assert;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.function.Supplier;

public abstract class AbstractBfdSessionActionTest {
    protected static final int BFD_LOGICAL_PORT_OFFSET = 200;

    protected Endpoint alphaEndpoint = Endpoint.of(new SwitchId(1), 1);
    protected Endpoint alphaLogicalEndpoint = Endpoint.of(
            alphaEndpoint.getDatapath(), BFD_LOGICAL_PORT_OFFSET + alphaEndpoint.getPortNumber());
    protected SwitchReference alphaSwitchRef = new SwitchReference(
            alphaEndpoint.getDatapath(), Inet4Address.getByName("192.168.1.1"));
    protected SwitchReference betaSwitchRef = new SwitchReference(
            new SwitchId(2), Inet4Address.getByName("192.168.1.2"));
    protected NoviBfdSession payload = NoviBfdSession.builder()
            .target(alphaSwitchRef)
            .remote(betaSwitchRef)
            .physicalPortNumber(alphaEndpoint.getPortNumber())
            .logicalPortNumber(alphaLogicalEndpoint.getPortNumber())
            .udpPortNumber(BfdSessionFsm.BFD_UDP_PORT)
            .discriminator(1001)
            .intervalMs(350)
            .multiplier((short) 3)
            .keepOverDisconnect(true)
            .build();

    protected Supplier<AssertionError> expectResultError = () -> new AssertionError(
            "Result must be defined at this moment");

    public AbstractBfdSessionActionTest() throws UnknownHostException {
    }

    @Test
    public void completeOnSpeakerError() {
        String requestKey = "BFD-session-setup-key";
        BfdSessionAction action = makeAction(requestKey);

        BfdSessionResponse response = new BfdSessionResponse(payload, NoviBfdSession.Errors.SWITCH_RESPONSE_ERROR);
        BfdSessionAction.ActionResult result = action.consumeSpeakerResponse(requestKey, response)
                .orElseThrow(expectResultError);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(NoviBfdSession.Errors.SWITCH_RESPONSE_ERROR, result.getErrorCode());
    }

    protected abstract BfdSessionAction makeAction(String requestKey);
}
