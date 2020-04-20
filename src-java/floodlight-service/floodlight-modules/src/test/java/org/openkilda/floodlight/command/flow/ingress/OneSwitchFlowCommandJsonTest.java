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

package org.openkilda.floodlight.command.flow.ingress;

import org.openkilda.floodlight.api.request.OneSwitchFlowRequest;
import org.openkilda.floodlight.api.request.factory.OneSwitchFlowRequestFactory;
import org.openkilda.floodlight.command.AbstractSpeakerCommandJsonTest;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.Cookie;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchId;

import org.junit.Assert;

abstract class OneSwitchFlowCommandJsonTest
        extends AbstractSpeakerCommandJsonTest<OneSwitchFlowRequest> {
    protected void verifyPayload(OneSwitchFlowRequest request, OneSwitchFlowCommand command) {
        Assert.assertEquals(request.getMessageContext(), command.getMessageContext());
        Assert.assertEquals(request.getSwitchId(), command.getSwitchId());
        Assert.assertEquals(request.getCommandId(), command.getCommandId());
        Assert.assertEquals(request.getEndpoint(), command.getEndpoint());
        Assert.assertEquals(request.getEndpoint(), command.getEndpoint());
        Assert.assertEquals(request.getMeterConfig(), command.getMeterConfig());
        Assert.assertEquals(request.getEgressEndpoint(), command.getEgressEndpoint());
        Assert.assertEquals(request.getRulesContext(), command.getRulesContext());
    }

    @Override
    protected OneSwitchFlowRequest makeRequest() {
        SwitchId swId = new SwitchId(1);
        OneSwitchFlowRequestFactory factory = new OneSwitchFlowRequestFactory(
                new MessageContext(),
                new FlowSegmentMetadata("single-switch-flow-install-request", new Cookie(2), false),
                new FlowEndpoint(swId, 3, 4),
                new MeterConfig(new MeterId(6), 7000),
                new FlowEndpoint(swId, 8, 9),
                RulesContext.builder().build());
        return makeRequest(factory);
    }

    protected abstract OneSwitchFlowRequest makeRequest(OneSwitchFlowRequestFactory factory);
}
