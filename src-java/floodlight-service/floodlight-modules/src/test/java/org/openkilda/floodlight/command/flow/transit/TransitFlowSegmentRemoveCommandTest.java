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

package org.openkilda.floodlight.command.flow.transit;

import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.cookie.Cookie;

import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFlowDeleteStrict;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;

import java.util.UUID;

public class TransitFlowSegmentRemoveCommandTest extends TransitFlowSegmentCommandTest {
    @Test
    public void happyPathVlanEncapsulation() throws Exception {
        replayAll();

        TransitFlowSegmentRemoveCommand command = makeCommand(encapsulationVlan);
        verifySuccessCompletion(command.execute(commandProcessor));
        verifyWriteCount(1);

        OFFlowDeleteStrict expected = of.buildFlowDeleteStrict()
                .setPriority(TransitFlowSegmentRemoveCommand.FLOW_PRIORITY)
                .setCookie(U64.of(command.getCookie().getValue()))
                .setCookieMask(U64.NO_MASK)
                .setMatch(OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), command.getEncapsulation().getId())
                                  .setExact(MatchField.IN_PORT, OFPort.of(command.getIngressIslPort()))
                                  .build())
                .build();
        verifyOfMessageEquals(expected, getWriteRecord(0).getRequest());
    }

    @Override
    protected TransitFlowSegmentRemoveCommand makeCommand(FlowTransitEncapsulation encapsulation) {
        MessageContext messageContext = new MessageContext();
        UUID commandId = UUID.randomUUID();
        FlowSegmentMetadata metadata = new FlowSegmentMetadata(
                "transit-flow-segment-remove-flow-id", new Cookie(6), false);
        int ingressIslPort = 3;
        int egressIslPort = 5;
        return new TransitFlowSegmentRemoveCommand(
                messageContext, mapSwitchId(dpIdNext), commandId, metadata, ingressIslPort, encapsulation,
                egressIslPort, -1);
    }
}
