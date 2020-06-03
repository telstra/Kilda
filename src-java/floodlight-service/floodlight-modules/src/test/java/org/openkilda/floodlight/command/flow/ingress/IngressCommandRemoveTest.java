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

import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.error.SwitchErrorResponseException;
import org.openkilda.floodlight.error.SwitchOperationException;
import org.openkilda.floodlight.error.UnsupportedSwitchOperationException;

import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFBadRequestCode;

import java.util.concurrent.CompletableFuture;

abstract class IngressCommandRemoveTest extends IngressCommandTest {
    @Test
    public void noMeterRequested() throws Exception {
        IngressFlowSegmentBase command = makeCommand(endpointIngressSingleVlan, null, makeMetadata());

        switchFeaturesSetup(sw, false);
        expectMakeOuterOnlyVlanForwardMessage(command, null);
        expectNoMoreOfMessages();
        replayAll();

        verifySuccessCompletion(command.execute(commandProcessor));
    }

    @Test
    public void errorNoMeterSupport() throws Exception {
        IngressFlowSegmentBase command = makeCommand(endpointIngressSingleVlan, meterConfig, makeMetadata());

        switchFeaturesSetup(sw, false);
        expectMeterDryRun(
                new UnsupportedSwitchOperationException(dpIdNext, "Switch doesn't support meters (unit-test)"));
        expectMakeOuterOnlyVlanForwardMessage(command, null);
        expectNoMoreOfMessages();
        replayAll();

        verifySuccessCompletion(command.execute(commandProcessor));
    }

    @Test
    public void errorOnMeterManipulation() {
        IngressFlowSegmentBase command = makeCommand(endpointIngressSingleVlan, meterConfig, makeMetadata());

        switchFeaturesSetup(sw, true);
        expectMeterDryRun();
        expectMeterRemove(new SwitchErrorResponseException(dpIdNext, "fake fail to remove meter error"));
        expectMakeOuterOnlyVlanForwardMessage(command, meterConfig.getId());
        expectNoMoreOfMessages();
        replayAll();

        CompletableFuture<FlowSegmentReport> result = command.execute(commandProcessor);
        verifyErrorCompletion(result, SwitchErrorResponseException.class);
    }

    @Test
    public void errorOnFlowMod() {
        IngressFlowSegmentBase command = makeCommand(endpointIngressSingleVlan, meterConfig, makeMetadata());

        switchFeaturesSetup(sw, true);
        expectMeterDryRun();
        expectMakeOuterOnlyVlanForwardMessage(command, meterConfig.getId());
        expectNoMoreOfMessages();
        replayAll();

        CompletableFuture<FlowSegmentReport> result = command.execute(commandProcessor);

        getWriteRecord(0).getFuture()
                .completeExceptionally(new SwitchErrorResponseException(
                        dpIdNext, of.errorMsgs().buildBadRequestErrorMsg().setCode(OFBadRequestCode.BAD_LEN).build()));
        verifyErrorCompletion(result, SwitchOperationException.class);
    }

    protected void processZeroVlanSingleTable(IngressFlowSegmentBase command) throws Exception {
        expectMakeDefaultPortForwardMessage(command, meterConfig.getId());
        executeCommand(command, 1);
    }

    protected void processOneVlanSingleTable(IngressFlowSegmentBase command) throws Exception {
        expectMakeOuterOnlyVlanForwardMessage(command, meterConfig.getId());
        executeCommand(command, 1);
    }

    protected void processZeroVlanMultiTable(IngressFlowSegmentBase command) throws Exception {
        expectMakeDefaultPortForwardMessage(command, meterConfig.getId());
        executeCommand(command, 1);
    }

    protected void processOneVlanMultiTable(IngressFlowSegmentBase command) throws Exception {
        expectMakeSingleVlanForwardMessage(command, meterConfig.getId());
        executeCommand(command, 1);
    }

    @Override
    protected void expectMeter() {
        expectMeterDryRun();
        expectMeterRemove();
    }
}
