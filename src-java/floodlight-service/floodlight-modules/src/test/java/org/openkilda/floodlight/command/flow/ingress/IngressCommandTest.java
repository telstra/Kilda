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

import static org.easymock.EasyMock.getCurrentArguments;
import static org.openkilda.floodlight.switchmanager.SwitchManager.DEFAULT_FLOW_VLAN_PRIORITY_SHIFT;

import org.openkilda.floodlight.command.AbstractSpeakerCommandTest;
import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.command.flow.ingress.of.IngressFlowModFactory;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.model.Cookie;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MeterId;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import java.util.concurrent.CompletableFuture;

abstract class IngressCommandTest extends AbstractSpeakerCommandTest {
    protected static IngressFlowModFactory flowModFactoryMock = EasyMock.createStrictMock(IngressFlowModFactory.class);

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        EasyMock.reset(flowModFactoryMock);
    }

    @Override
    @After
    public void tearDown() {
        EasyMock.verify(flowModFactoryMock);
        super.tearDown();
    }

    public void expectMakeOuterVlanOnlyForwardMessage(IngressFlowSegmentBase command, MeterId effectiveMeterId) {
        EasyMock.expect(flowModFactoryMock.makeOuterVlanOnlyForwardMessage(effectiveMeterId))
                .andAnswer(() -> {
                    MeterId meterId = (MeterId) getCurrentArguments()[0];
                    return extractFlowModFactory(command).makeOuterVlanOnlyForwardMessage(meterId);
                });
    }

    public void expectMakeDefaultPortFlowMatchAndForwardMessage(
            IngressFlowSegmentBase command, MeterId effectiveMeterId) {
        EasyMock.expect(flowModFactoryMock.makeDefaultPortFlowMatchAndForwardMessage(effectiveMeterId,
                DEFAULT_FLOW_VLAN_PRIORITY_SHIFT))
                .andAnswer(() -> {
                    MeterId meterId = (MeterId) getCurrentArguments()[0];
                    return extractFlowModFactory(command).makeDefaultPortFlowMatchAndForwardMessage(
                            meterId, DEFAULT_FLOW_VLAN_PRIORITY_SHIFT);
                });
    }

    public void expectMakeCustomerPortSharedCatchInstallMessage(IngressFlowSegmentBase command) {
        EasyMock.expect(flowModFactoryMock.makeCustomerPortSharedCatchMessage())
                .andAnswer(() -> extractFlowModFactory(command).makeCustomerPortSharedCatchMessage());
    }

    public void expectNoMoreOfMessages() {
        EasyMock.replay(flowModFactoryMock);
    }

    private IngressFlowModFactory extractFlowModFactory(IngressFlowSegmentBase command) {
        if (! (command instanceof IFlowModFactoryOverride)) {
            Assert.fail();
        }
        return ((IFlowModFactoryOverride) command).getRealFlowModFactory();
    }

    protected void executeCommand(IngressFlowSegmentBase command, int writeCount) throws Exception {
        switchFeaturesSetup(sw, true);
        if (command.getMeterConfig() != null) {
            expectMeter();
        }
        replayAll();
        expectNoMoreOfMessages();

        final CompletableFuture<FlowSegmentReport> result = command.execute(commandProcessor);
        if (result.isDone()) {
            result.get().raiseError();
        }

        verifyWriteCount(writeCount);
        verifySuccessCompletion(result);
    }

    protected abstract void expectMeter();

    protected abstract IngressFlowSegmentBase makeCommand(
            FlowEndpoint endpoint, MeterConfig meterConfig, FlowSegmentMetadata metadata);

    protected FlowSegmentMetadata makeMetadata() {
        return makeMetadata(false);
    }

    protected FlowSegmentMetadata makeMetadata(boolean isMultiTable) {
        return new FlowSegmentMetadata(
                "speaker-unit-test", new Cookie(1), isMultiTable);
    }
}
