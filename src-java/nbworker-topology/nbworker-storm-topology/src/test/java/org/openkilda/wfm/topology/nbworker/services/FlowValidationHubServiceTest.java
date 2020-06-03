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

package org.openkilda.wfm.topology.nbworker.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.switches.DumpMetersForNbworkerRequest;
import org.openkilda.messaging.command.switches.DumpRulesForNbworkerRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.nbtopology.request.FlowValidationRequest;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.topology.nbworker.bolts.FlowValidationHubCarrier;

import com.google.common.collect.Lists;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

public class FlowValidationHubServiceTest extends FlowValidationTestBase {
    private static final String TEST_KEY = "test_key";

    private static FlowValidationHubService flowValidationHubService;
    private static FlowValidationService flowValidationService;

    @BeforeClass
    public static void setUpOnce() {
        FlowValidationTestBase.setUpOnce();
        flowValidationHubService = new FlowValidationHubService(persistenceManager, flowResourcesConfig,
                new SimpleMeterRegistry());
        flowValidationService = new FlowValidationService(persistenceManager, flowResourcesConfig,
                MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT);
    }

    @Test
    public void testMainPath() {
        class FlowValidationHubCarrierImpl implements FlowValidationHubCarrier {

            @Override
            public void sendCommandToSpeakerWorker(String key, CommandData commandData) {
                assertEquals(TEST_KEY, key);
                assertTrue(commandData instanceof DumpRulesForNbworkerRequest
                        || commandData instanceof DumpMetersForNbworkerRequest);

                List<SwitchId> switchIds =
                        Lists.newArrayList(TEST_SWITCH_ID_A, TEST_SWITCH_ID_B, TEST_SWITCH_ID_C, TEST_SWITCH_ID_E);
                if (commandData instanceof DumpRulesForNbworkerRequest) {
                    assertTrue(switchIds.contains(((DumpRulesForNbworkerRequest) commandData).getSwitchId()));
                } else {
                    assertTrue(switchIds.contains(((DumpMetersForNbworkerRequest) commandData).getSwitchId()));
                }
            }

            @Override
            public void sendToResponseSplitterBolt(String key, List<? extends InfoData> message) {
                assertEquals(TEST_KEY, key);
                assertEquals(4, message.size());
                try {
                    assertEquals(flowValidationService
                            .validateFlow(TEST_FLOW_ID_A, getSwitchFlowEntriesWithTransitVlan(),
                            getSwitchMeterEntries()), message);
                } catch (FlowNotFoundException | SwitchNotFoundException e) {
                    //tested in the FlowValidationServiceTest
                }
            }

            @Override
            public void sendToMessageEncoder(String key, ErrorData errorData) {
                fail();
            }

            @Override
            public void endProcessing(String key) {
                assertEquals(TEST_KEY, key);
            }

            @Override
            public long getFlowMeterMinBurstSizeInKbits() {
                return MIN_BURST_SIZE_IN_KBITS;
            }

            @Override
            public double getFlowMeterBurstCoefficient() {
                return BURST_COEFFICIENT;
            }
        }

        buildTransitVlanFlow("");
        flowValidationHubService.handleFlowValidationRequest(TEST_KEY, new FlowValidationRequest(TEST_FLOW_ID_A),
                new FlowValidationHubCarrierImpl());
        getSwitchFlowEntriesWithTransitVlan().forEach(switchFlowEntries ->
                flowValidationHubService.handleAsyncResponse(TEST_KEY, new InfoMessage(switchFlowEntries,
                        System.currentTimeMillis(), TEST_KEY)));
        getSwitchMeterEntries().forEach(switchMeterEntries ->
                flowValidationHubService.handleAsyncResponse(TEST_KEY, new InfoMessage(switchMeterEntries,
                        System.currentTimeMillis(), TEST_KEY)));
    }

    @Test
    public void testTimeout() {
        class FlowValidationHubCarrierImpl implements FlowValidationHubCarrier {

            @Override
            public void sendCommandToSpeakerWorker(String key, CommandData commandData) {
                assertEquals(TEST_KEY, key);
                assertTrue(commandData instanceof DumpRulesForNbworkerRequest
                        || commandData instanceof DumpMetersForNbworkerRequest);

                List<SwitchId> switchIds =
                        Lists.newArrayList(TEST_SWITCH_ID_A, TEST_SWITCH_ID_B, TEST_SWITCH_ID_C, TEST_SWITCH_ID_E);
                if (commandData instanceof DumpRulesForNbworkerRequest) {
                    assertTrue(switchIds.contains(((DumpRulesForNbworkerRequest) commandData).getSwitchId()));
                } else {
                    assertTrue(switchIds.contains(((DumpMetersForNbworkerRequest) commandData).getSwitchId()));
                }
            }

            @Override
            public void sendToResponseSplitterBolt(String key, List<? extends InfoData> message) {
                fail();
            }

            @Override
            public void sendToMessageEncoder(String key, ErrorData errorData) {
                assertEquals(TEST_KEY, key);
                assertEquals(ErrorType.OPERATION_TIMED_OUT, errorData.getErrorType());
            }

            @Override
            public void endProcessing(String key) {
                assertEquals(TEST_KEY, key);
            }

            @Override
            public long getFlowMeterMinBurstSizeInKbits() {
                return MIN_BURST_SIZE_IN_KBITS;
            }

            @Override
            public double getFlowMeterBurstCoefficient() {
                return BURST_COEFFICIENT;
            }
        }

        buildTransitVlanFlow("");
        flowValidationHubService.handleFlowValidationRequest(TEST_KEY, new FlowValidationRequest(TEST_FLOW_ID_A),
                new FlowValidationHubCarrierImpl());
        flowValidationHubService.handleTaskTimeout(TEST_KEY);
    }

    @Test
    public void testFlowNotFoundError() {
        class FlowValidationHubCarrierImpl implements FlowValidationHubCarrier {

            @Override
            public void sendCommandToSpeakerWorker(String key, CommandData commandData) {
                assertEquals(TEST_KEY, key);
                assertTrue(commandData instanceof DumpRulesForNbworkerRequest
                        || commandData instanceof DumpMetersForNbworkerRequest);

                List<SwitchId> switchIds =
                        Lists.newArrayList(TEST_SWITCH_ID_A, TEST_SWITCH_ID_B, TEST_SWITCH_ID_C, TEST_SWITCH_ID_E);
                if (commandData instanceof DumpRulesForNbworkerRequest) {
                    assertTrue(switchIds.contains(((DumpRulesForNbworkerRequest) commandData).getSwitchId()));
                } else {
                    assertTrue(switchIds.contains(((DumpMetersForNbworkerRequest) commandData).getSwitchId()));
                }
            }

            @Override
            public void sendToResponseSplitterBolt(String key, List<? extends InfoData> message) {
                fail();
            }

            @Override
            public void sendToMessageEncoder(String key, ErrorData errorData) {
                assertEquals(TEST_KEY, key);
                assertEquals(ErrorType.NOT_FOUND, errorData.getErrorType());
            }

            @Override
            public void endProcessing(String key) {
                assertEquals(TEST_KEY, key);
            }

            @Override
            public long getFlowMeterMinBurstSizeInKbits() {
                return MIN_BURST_SIZE_IN_KBITS;
            }

            @Override
            public double getFlowMeterBurstCoefficient() {
                return BURST_COEFFICIENT;
            }
        }

        buildTransitVlanFlow("");
        flowValidationHubService.handleFlowValidationRequest(TEST_KEY, new FlowValidationRequest("test"),
                new FlowValidationHubCarrierImpl());

        flowValidationHubService.handleFlowValidationRequest(TEST_KEY, new FlowValidationRequest(TEST_FLOW_ID_A),
                new FlowValidationHubCarrierImpl());
        transactionManager.doInTransaction(() ->
                flowRepository.remove(flowRepository.findById(TEST_FLOW_ID_A).get()));
        getSwitchFlowEntriesWithTransitVlan().forEach(switchFlowEntries ->
                flowValidationHubService.handleAsyncResponse(TEST_KEY, new InfoMessage(switchFlowEntries,
                        System.currentTimeMillis(), TEST_KEY)));
        getSwitchMeterEntries().forEach(switchMeterEntries ->
                flowValidationHubService.handleAsyncResponse(TEST_KEY, new InfoMessage(switchMeterEntries,
                        System.currentTimeMillis(), TEST_KEY)));

    }
}
