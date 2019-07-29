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

package org.openkilda.wfm.topology.isllatency.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.info.event.IslOneWayLatency;
import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.isllatency.model.IslKey;
import org.openkilda.wfm.topology.isllatency.model.LatencyRecord;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;

public class IslLatencyServiceTest extends InMemoryGraphBasedTest {
    private static final SwitchId SWITCH_ID_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_ID_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final int PORT_1 = 1;
    private static final int PORT_2 = 2;
    private static final int INITIAL_LATENCY = 123;
    private static final long PACKET_ID = 0;
    private static final SwitchId NON_EXISTENT_SWITCH_ID = new SwitchId(123);
    private static final int NON_EXISTENT_PORT = 555;
    private static final Endpoint FORWARD_DESTINATION = Endpoint.of(SWITCH_ID_2, PORT_2);
    private static final IslKey FORWARD_ISL_KEY = new IslKey(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2);
    public static final int LATENCY_UPDATE_INTERVAL = 100;
    public static final int LATENCY_UPDATE_TIME_RANGE = 10;


    private SwitchRepository switchRepository;
    private IslRepository islRepository;
    private IslLatencyService islLatencyService;

    @Before
    public void setup() {
        islLatencyService = new IslLatencyService(
                persistenceManager.getTransactionManager(), persistenceManager.getRepositoryFactory(),
                LATENCY_UPDATE_INTERVAL, LATENCY_UPDATE_TIME_RANGE);
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();

        Switch firstSwitch = createSwitch(SWITCH_ID_1);
        Switch secondSwitch = createSwitch(SWITCH_ID_2);

        createIsl(firstSwitch, PORT_1, secondSwitch, PORT_2, INITIAL_LATENCY);
    }

    @Ignore
    @Test(expected = SwitchNotFoundException.class)
    public void updateIslLatencyNonExistentSrcEndpointTest() throws IslNotFoundException, SwitchNotFoundException {
        islLatencyService.updateIslLatency(NON_EXISTENT_SWITCH_ID, PORT_1, SWITCH_ID_2, PORT_2, 0);
    }

    @Ignore
    @Test(expected = SwitchNotFoundException.class)
    public void updateIslLatencyNonExistentDstEndpointTest() throws IslNotFoundException, SwitchNotFoundException {
        islLatencyService.updateIslLatency(SWITCH_ID_1, PORT_1, NON_EXISTENT_SWITCH_ID, PORT_2, 0);
    }

    @Test(expected = IslNotFoundException.class)
    public void updateIslLatencyNonExistentIslTest() throws IslNotFoundException, SwitchNotFoundException {
        islLatencyService.updateIslLatency(SWITCH_ID_1, NON_EXISTENT_PORT, SWITCH_ID_2, NON_EXISTENT_PORT, 0);
    }

    @Test
    public void updateIslLatencyTest() throws IslNotFoundException, SwitchNotFoundException {
        islLatencyService.updateIslLatency(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, 1000);
        assertForwardLatency(1000);
    }

    @Test
    public void handleOneWayIslLatencyTest() {
        assertTrue(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleOneWayIslLatency(createForwardOneWayLatency(1), System.currentTimeMillis());
        assertForwardLatency(1);

        // second latency will be put in cache
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleOneWayIslLatency(createForwardOneWayLatency(10000), System.currentTimeMillis());
        assertForwardLatency(1);
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
    }

    @Test
    public void handleRoundTripIslLatencyTest() {
        assertTrue(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleRoundTripIslLatency(
                createForwardRoundTripLatency(5), FORWARD_DESTINATION, System.currentTimeMillis());
        assertForwardLatency(5);

        // second latency will be put in cache
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleRoundTripIslLatency(
                createForwardRoundTripLatency(50000), FORWARD_DESTINATION, System.currentTimeMillis());
        assertForwardLatency(5);
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
    }

    @Test
    public void handleRoundTripIslLatencyAfterOneWayIslLatencyTest() {
        assertTrue(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleOneWayIslLatency(createForwardOneWayLatency(7), System.currentTimeMillis());
        assertForwardLatency(7);

        // second latency will be put in cache
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleOneWayIslLatency(createForwardOneWayLatency(70000), System.currentTimeMillis());
        assertForwardLatency(7);

        // round trip latency will rewrite one way latency
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleRoundTripIslLatency(
                createForwardRoundTripLatency(8), FORWARD_DESTINATION, System.currentTimeMillis());
        assertForwardLatency(8);

        // second latency will be put in cache
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
        islLatencyService.handleRoundTripIslLatency(
                createForwardRoundTripLatency(80000), FORWARD_DESTINATION, System.currentTimeMillis());
        assertForwardLatency(8);
        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
    }

    @Test
    public void isUpdateRequiredTest() {
        assertTrue(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));

        IslRoundTripLatency data = new IslRoundTripLatency(SWITCH_ID_1, PORT_1, 1L, 0L);
        Endpoint destination = Endpoint.of(SWITCH_ID_2, PORT_2);
        islLatencyService.handleRoundTripIslLatency(data, destination, System.currentTimeMillis());

        assertFalse(islLatencyService.isUpdateRequired(FORWARD_ISL_KEY));
    }

    @Test
    public void handleOneWayIslLatencyNonExistentIslTest() {
        int fakePort = 999;
        IslKey islKey = new IslKey(SWITCH_ID_1, fakePort, SWITCH_ID_2, fakePort);

        assertTrue(islLatencyService.isUpdateRequired(islKey));

        IslOneWayLatency nonExistent = new IslOneWayLatency(SWITCH_ID_1, fakePort, SWITCH_ID_2, fakePort, 3, PACKET_ID);
        islLatencyService.handleOneWayIslLatency(nonExistent, System.currentTimeMillis());

        assertTrue(islLatencyService.isUpdateRequired(islKey));
    }

    @Test
    public void handleRoundTripIslLatencyNonExistentIslTest() {
        int fakePort = 998;
        IslKey islKey = new IslKey(SWITCH_ID_1, fakePort, SWITCH_ID_2, fakePort);

        assertTrue(islLatencyService.isUpdateRequired(islKey));

        IslRoundTripLatency nonExistent = new IslRoundTripLatency(SWITCH_ID_1, fakePort, 4, PACKET_ID);
        islLatencyService.handleRoundTripIslLatency(
                nonExistent, Endpoint.of(SWITCH_ID_2, fakePort), System.currentTimeMillis());

        assertTrue(islLatencyService.isUpdateRequired(islKey));
    }

    @Test
    public void getNextUpdateTimeTest() {
        Instant actualTime = islLatencyService.getNextUpdateTime();
        long expectedTime = System.currentTimeMillis() + LATENCY_UPDATE_INTERVAL * 1000;
        assertEquals(expectedTime, actualTime.toEpochMilli(), 50);
    }

    @Test
    public void calculateAverageLatencyTest() {
        Queue<LatencyRecord> latencyRecords = new LinkedList<>();

        for (int i = 1; i <= 5; i++) {
            latencyRecords.add(new LatencyRecord(i, 1));
        }
        assertEquals(3, islLatencyService.calculateAverageLatency(latencyRecords));
    }

    @Test
    public void calculateAverageLatencyEmptyTest() {
        assertEquals(-1, islLatencyService.calculateAverageLatency(new LinkedList<>()));
    }

    @Test
    public void pollExpiredRecordsTest() {
        Instant time = Instant.now().minusSeconds(LATENCY_UPDATE_TIME_RANGE * 2);
        Queue<LatencyRecord> latencyRecords = new LinkedList<>();

        for (int i = 0; i < 5; i++) {
            latencyRecords.add(new LatencyRecord(i, time.toEpochMilli()));
            time = time.plusSeconds(1);
        }

        time = Instant.now().minusSeconds(LATENCY_UPDATE_TIME_RANGE - 7);
        for (int i = 5; i < 10; i++) {
            latencyRecords.add(new LatencyRecord(i, time.toEpochMilli()));
            time = time.plusSeconds(1);
        }

        assertEquals(10, latencyRecords.size());
        islLatencyService.pollExpiredRecords(latencyRecords);
        assertEquals(5, latencyRecords.size());

        for (int i = 5; i < 10; i++) {
            assertEquals(i, latencyRecords.poll().getLatency());
        }
    }

    private Switch createSwitch(SwitchId switchId) {
        Switch sw = Switch.builder().switchId(switchId).build();
        switchRepository.add(sw);
        return sw;
    }

    private void createIsl(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort, int latency) {
        Isl isl = Isl.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .actualStatus(IslStatus.ACTIVE)
                .latency(latency).build();
        islRepository.add(isl);
    }

    private IslOneWayLatency createForwardOneWayLatency(long latency) {
        return new IslOneWayLatency(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, latency, PACKET_ID);
    }

    private IslRoundTripLatency createForwardRoundTripLatency(long latency) {
        return new IslRoundTripLatency(SWITCH_ID_1, PORT_1, latency, PACKET_ID);
    }

    private void assertForwardLatency(long expectedLatency) {
        long actualLatency = islRepository.findByEndpoints(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2).get().getLatency();
        assertEquals(expectedLatency, actualLatency);
    }
}
