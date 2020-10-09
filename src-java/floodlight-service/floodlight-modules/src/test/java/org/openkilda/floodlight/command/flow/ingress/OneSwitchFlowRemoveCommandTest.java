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

import org.openkilda.floodlight.command.flow.ingress.of.IngressFlowModFactory;
import org.openkilda.floodlight.command.flow.ingress.of.OneSwitchFlowRemoveMultiTableFlowModFactory;
import org.openkilda.floodlight.command.flow.ingress.of.OneSwitchFlowRemoveSingleTableFlowModFactory;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;

import lombok.Getter;
import org.junit.Assert;

import java.util.UUID;

public class OneSwitchFlowRemoveCommandTest extends IngressCommandRemoveTest {
    private static final FlowEndpoint endpointEgressDefaultPort = new FlowEndpoint(
            endpointIngressZeroVlan.getSwitchId(),
            IngressCommandRemoveTest.endpointEgressZeroVlan.getPortNumber(),
            IngressCommandRemoveTest.endpointEgressZeroVlan.getOuterVlanId());
    private static final FlowEndpoint endpointEgressSingleVlan = new FlowEndpoint(
            endpointIngressSingleVlan.getSwitchId(),
            IngressCommandRemoveTest.endpointEgressSingleVlan.getPortNumber(),
            IngressCommandRemoveTest.endpointEgressSingleVlan.getOuterVlanId());

    @Override
    protected OneSwitchFlowRemoveCommand makeCommand(
            FlowEndpoint endpoint, MeterConfig meterConfig, FlowSegmentMetadata metadata) {
        return makeCommand(endpoint, endpointEgressSingleVlan, meterConfig, metadata);
    }

    protected OneSwitchFlowRemoveCommand makeCommand(
            FlowEndpoint endpoint, FlowEndpoint egressEndpoint, MeterConfig meterConfig, FlowSegmentMetadata metadata) {
        UUID commandId = UUID.randomUUID();
        return new CommandStub(new MessageContext(commandId.toString()), commandId, metadata, endpoint, meterConfig,
                egressEndpoint, RulesContext.builder().build());
    }

    static class CommandStub extends OneSwitchFlowRemoveCommand implements IFlowModFactoryOverride {
        @Getter
        private IngressFlowModFactory realFlowModFactory;

        public CommandStub(
                MessageContext context, UUID commandId, FlowSegmentMetadata metadata, FlowEndpoint endpoint,
                MeterConfig meterConfig, FlowEndpoint egressEndpoint,
                RulesContext rulesContext) {
            super(context, commandId, metadata, endpoint, meterConfig, egressEndpoint, rulesContext, -1);
        }

        @Override
        protected void setupFlowModFactory() {
            super.setupFlowModFactory();

            realFlowModFactory = getFlowModFactory();
            if (metadata.isMultiTable()) {
                Assert.assertTrue(realFlowModFactory instanceof OneSwitchFlowRemoveMultiTableFlowModFactory);
            } else {
                Assert.assertTrue(realFlowModFactory instanceof OneSwitchFlowRemoveSingleTableFlowModFactory);
            }

            setFlowModFactory(flowModFactoryMock);
        }
    }
}
