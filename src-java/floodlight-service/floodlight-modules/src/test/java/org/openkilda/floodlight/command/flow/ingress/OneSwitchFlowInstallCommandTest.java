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
import org.openkilda.floodlight.command.flow.ingress.of.OneSwitchFlowInstallMultiTableFlowModFactory;
import org.openkilda.floodlight.command.flow.ingress.of.OneSwitchFlowInstallSingleTableFlowModFactory;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;

import lombok.Getter;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;

public class OneSwitchFlowInstallCommandTest extends IngressCommandInstallTest {
    private static final FlowEndpoint endpointEgressZeroVlan = new FlowEndpoint(
            endpointIngressZeroVlan.getSwitchId(),
            IngressCommandInstallTest.endpointEgressZeroVlan.getPortNumber(),
            IngressCommandInstallTest.endpointEgressZeroVlan.getOuterVlanId());
    private static final FlowEndpoint endpointEgressOneVlan = new FlowEndpoint(
            endpointIngressSingleVlan.getSwitchId(),
            IngressCommandInstallTest.endpointEgressSingleVlan.getPortNumber(),
            IngressCommandInstallTest.endpointEgressSingleVlan.getOuterVlanId());

    @Test
    public void zeroVlanSingleTable() throws Exception {
        processZeroVlanSingleTable(makeCommand(
                endpointIngressZeroVlan, endpointEgressOneVlan, meterConfig, makeMetadata(false)));
    }

    @Test
    public void oneVlanSingleTable() throws Exception {
        processOneVlanSingleTable(makeCommand(
                endpointIngressSingleVlan, endpointEgressZeroVlan, meterConfig, makeMetadata(false)));
    }

    @Test
    public void zeroVlanMultiTable() throws Exception {
        processZeroVlanMultiTable(makeCommand(
                endpointIngressZeroVlan, endpointEgressOneVlan, meterConfig, makeMetadata(true)));
    }

    @Test
    public void oneVlanMultiTable() throws Exception {
        processOneVlanMultiTable(makeCommand(
                endpointIngressSingleVlan, endpointEgressZeroVlan, meterConfig, makeMetadata(true)));
    }

    @Override
    protected void expectMeter() {
        expectMeterInstall();
    }

    @Override
    protected OneSwitchFlowInstallCommand makeCommand(
            FlowEndpoint endpoint, MeterConfig meterConfig, FlowSegmentMetadata metadata) {
        return makeCommand(endpoint, endpointEgressOneVlan, meterConfig, metadata);
    }

    protected OneSwitchFlowInstallCommand makeCommand(
            FlowEndpoint endpoint, FlowEndpoint egressEndpoint, MeterConfig meterConfig, FlowSegmentMetadata metadata) {
        UUID commandId = UUID.randomUUID();
        return new CommandStub(new MessageContext(commandId.toString()), commandId, metadata, endpoint, meterConfig,
                egressEndpoint, RulesContext.builder().build());
    }

    static class CommandStub extends OneSwitchFlowInstallCommand implements IFlowModFactoryOverride {
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
                Assert.assertTrue(realFlowModFactory instanceof OneSwitchFlowInstallMultiTableFlowModFactory);
            } else {
                Assert.assertTrue(realFlowModFactory instanceof OneSwitchFlowInstallSingleTableFlowModFactory);
            }

            setFlowModFactory(flowModFactoryMock);
        }
    }
}
