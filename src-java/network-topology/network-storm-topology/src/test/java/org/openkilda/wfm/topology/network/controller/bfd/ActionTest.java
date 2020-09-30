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
import org.openkilda.messaging.model.NoviBfdSession.Errors;
import org.openkilda.messaging.model.SwitchReference;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.topology.network.controller.bfd.BfdAction.ActionResult;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.Inet4Address;
import java.net.UnknownHostException;

@RunWith(MockitoJUnitRunner.class)
public class ActionTest {
    private NoviBfdSession payload = NoviBfdSession.builder()
            .target(new SwitchReference(new SwitchId(1), Inet4Address.getByName("192.168.1.1")))
            .remote(new SwitchReference(new SwitchId(2), Inet4Address.getByName("192.168.1.2")))
            .physicalPortNumber(1)
            .logicalPortNumber(201)
            .discriminator(1001)
            .udpPortNumber(BfdPortFsm.BFD_UDP_PORT)
            .intervalMs(350)
            .multiplier((short) 3)
            .keepOverDisconnect(true)
            .build();

    public ActionTest() throws UnknownHostException {
    }

    @Test
    public void mustFilterResponsesByRequestKey() {
        String requestKey = "request-key";
        BfdAction action = new BfdActionImpl(requestKey, false);

        // invalid
        BfdSessionResponse response = new BfdSessionResponse(payload, null);
        Assert.assertFalse(action.consumeSpeakerResponse(requestKey + "+invalid", response).isPresent());

        // valid
        ActionResult result = action.consumeSpeakerResponse(requestKey, new BfdSessionResponse(payload, null))
                .orElseThrow(() -> new AssertionError("Action must produce result"));
        Assert.assertTrue(result.isSuccess());

        // extra result
        ActionResult result2 = action
                .consumeSpeakerResponse(requestKey, new BfdSessionResponse(payload, Errors.NOVI_BFD_UNKNOWN_ERROR))
                .orElseThrow(() -> new AssertionError("Action must produce result"));
        Assert.assertTrue(result2.isSuccess()); // because extra result was ignored
    }

    @Test
    public void errorResponse() {
        ActionResult result = makeWithResponse(new BfdSessionResponse(payload, Errors.SWITCH_RESPONSE_ERROR));

        Assert.assertEquals(Errors.SWITCH_RESPONSE_ERROR, result.getErrorCode());
        Assert.assertFalse(result.isSuccess());
    }

    @Test
    public void timeoutResponse() {
        ActionResult result = makeWithResponse(null);

        Assert.assertNull(result.getErrorCode());
        Assert.assertFalse(result.isSuccess());
    }

    @Test
    public void missingSessionErrorResponse() {
        ActionResult result;

        result = makeWithResponse(
                new BfdSessionResponse(payload, Errors.NOVI_BFD_DISCRIMINATOR_NOT_FOUND_ERROR), false);
        Assert.assertFalse(result.isSuccess());

        result = makeWithResponse(
                new BfdSessionResponse(payload, Errors.NOVI_BFD_DISCRIMINATOR_NOT_FOUND_ERROR), true);
        Assert.assertTrue(result.isSuccess());
    }

    private ActionResult makeWithResponse(BfdSessionResponse response) {
        return makeWithResponse(response, false);
    }

    private ActionResult makeWithResponse(BfdSessionResponse response, boolean allowMissing) {
        String requestKey = "request-key";
        BfdAction action = new BfdActionImpl(requestKey, allowMissing);

        return action.consumeSpeakerResponse(requestKey, response)
                .orElseThrow(() -> new AssertionError("Action must produce result"));
    }

    private static class BfdActionImpl extends BfdAction {
        private final boolean allowMissing;

        BfdActionImpl(String requestKey, boolean allowMissing) {
            super(requestKey);
            this.allowMissing = allowMissing;
        }

        @Override
        public String getLogIdentifier() {
            return "test dummy";
        }

        @Override
        protected ActionResult makeResult(BfdSessionResponse response) {
            return ActionResult.of(response, allowMissing);
        }
    }
}
