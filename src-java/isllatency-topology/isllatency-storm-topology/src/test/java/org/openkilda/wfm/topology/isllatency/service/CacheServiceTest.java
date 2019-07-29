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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.openkilda.model.IslStatus.ACTIVE;
import static org.openkilda.model.IslStatus.INACTIVE;
import static org.openkilda.model.IslStatus.MOVED;

import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.messaging.info.event.IslStatusUpdateNotification;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.isllatency.carriers.CacheCarrier;

import org.junit.Before;
import org.junit.Test;

public class CacheServiceTest extends InMemoryGraphBasedTest {
    private static final SwitchId SWITCH_ID_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_ID_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final int PORT_1 = 1;
    private static final int PORT_2 = 2;
    private static final int FORWARD_LATENCY = 123;
    private static final int REVERSE_LATENCY = 456;

    private CacheCarrier carrier;
    private SwitchRepository switchRepository;
    private IslRepository islRepository;
    private CacheService cacheService;

    @Before
    public void setup() {
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();

        Switch firstSwitch = createSwitch(SWITCH_ID_1);
        Switch secondSwitch = createSwitch(SWITCH_ID_2);

        createActiveIsl(firstSwitch, PORT_1, secondSwitch, PORT_2, FORWARD_LATENCY);
        createActiveIsl(secondSwitch, PORT_2, firstSwitch, PORT_1, REVERSE_LATENCY);

        carrier = mock(CacheCarrier.class);
        cacheService = new CacheService(carrier, persistenceManager.getRepositoryFactory());
    }

    @Test()
    public void handleGetDataFromCacheTest() {
        IslRoundTripLatency forward = new IslRoundTripLatency(SWITCH_ID_1, PORT_1, 1, 0L);
        IslRoundTripLatency reverse = new IslRoundTripLatency(SWITCH_ID_2, PORT_2, 1, 0L);

        checkHandleGetDataFromCache(forward, SWITCH_ID_2, PORT_2);
        checkHandleGetDataFromCache(reverse, SWITCH_ID_1, PORT_1);
    }

    @Test()
    public void handleGetDataFromCacheNotFromInitCacheActiveTest() {
        testGetDataFromCacheNotFromInitCache(ACTIVE);
    }

    @Test()
    public void handleGetDataFromCacheNotFromInitCacheInactiveTest() {
        testGetDataFromCacheNotFromInitCache(INACTIVE);
    }

    private void testGetDataFromCacheNotFromInitCache(IslStatus islStatus) {
        int srcPort = 5;
        int dstPort = 6;
        // isl is not in init cache yet
        createIsl(switchRepository.findById(SWITCH_ID_1).get(), srcPort,
                switchRepository.findById(SWITCH_ID_2).get(), dstPort, 10, islStatus);

        IslRoundTripLatency forward = new IslRoundTripLatency(SWITCH_ID_1, srcPort, 1, 0L);
        IslRoundTripLatency reverse = new IslRoundTripLatency(SWITCH_ID_2, dstPort, 1, 0L);

        checkHandleGetDataFromCache(forward, SWITCH_ID_2, dstPort);
        checkHandleGetDataFromCache(reverse, SWITCH_ID_1, srcPort);
    }

    @Test()
    public void handleGetDataFromCacheNonExistentSwitchTest() {
        IslRoundTripLatency nonExistent = new IslRoundTripLatency(new SwitchId(999), PORT_1, 1, 0L);
        checkHandleGetDataFromCacheDidNotCallEmitCacheData(nonExistent);
    }

    @Test()
    public void handleGetDataFromCacheNonExistentIslTest() {
        IslRoundTripLatency nonExistent = new IslRoundTripLatency(SWITCH_ID_1, 999, 1, 0L);
        checkHandleGetDataFromCacheDidNotCallEmitCacheData(nonExistent);
    }

    @Test()
    public void handleUpdateCacheInactiveTest() {
        IslStatusUpdateNotification notification =
                new IslStatusUpdateNotification(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, INACTIVE);

        updateIslStatus(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, INACTIVE);
        updateIslStatus(SWITCH_ID_2, PORT_2, SWITCH_ID_1, PORT_1, INACTIVE);

        cacheService.handleUpdateCache(notification);

        IslRoundTripLatency forward = new IslRoundTripLatency(SWITCH_ID_1, PORT_1, 1, 0L);
        checkHandleGetDataFromCache(forward, SWITCH_ID_2, PORT_2);

        IslRoundTripLatency reverse = new IslRoundTripLatency(SWITCH_ID_2, PORT_2, 1, 0L);
        checkHandleGetDataFromCache(reverse, SWITCH_ID_1, PORT_1);
    }

    @Test()
    public void handleUpdateCacheMovedTest() {
        testHandleUpdateCache(MOVED);
    }

    @Test()
    public void handleGetDataFromCacheMovedTest() {
        testGetDataFromCache(MOVED);
    }

    private void testGetDataFromCache(IslStatus islStatus) {
        int srcPort = 7;
        int dstPort = 8;

        createIsl(switchRepository.findById(SWITCH_ID_1).get(), srcPort,
                switchRepository.findById(SWITCH_ID_2).get(), dstPort, 1, islStatus);

        IslRoundTripLatency forward = new IslRoundTripLatency(SWITCH_ID_1, srcPort, 1, 0L);
        checkHandleGetDataFromCacheDidNotCallEmitCacheData(forward);

        IslRoundTripLatency reverse = new IslRoundTripLatency(SWITCH_ID_2, dstPort, 1, 0L);
        checkHandleGetDataFromCacheDidNotCallEmitCacheData(reverse);
    }

    private void testHandleUpdateCache(IslStatus islStatus) {
        IslStatusUpdateNotification notification =
                new IslStatusUpdateNotification(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, islStatus);

        updateIslStatus(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, islStatus);
        updateIslStatus(SWITCH_ID_2, PORT_2, SWITCH_ID_1, PORT_1, islStatus);

        cacheService.handleUpdateCache(notification);

        IslRoundTripLatency forward = new IslRoundTripLatency(SWITCH_ID_1, PORT_1, 1, 0L);
        checkHandleGetDataFromCacheDidNotCallEmitCacheData(forward);

        IslRoundTripLatency reverse = new IslRoundTripLatency(SWITCH_ID_2, PORT_2, 1, 0L);
        checkHandleGetDataFromCacheDidNotCallEmitCacheData(reverse);
    }

    private void updateIslStatus(
            SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort, IslStatus status) {
        Isl isl = islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort).get();
        isl.setStatus(status);
    }

    private void checkHandleGetDataFromCacheDidNotCallEmitCacheData(IslRoundTripLatency islRoundTripLatency) {
        cacheService.handleGetDataFromCache(islRoundTripLatency);
        verify(carrier, never()).emitCachedData(any(), any());
    }

    private void checkHandleGetDataFromCache(
            IslRoundTripLatency islRoundTripLatency, SwitchId expectedDstSwitchId, int expectedDstPort) {
        cacheService.handleGetDataFromCache(islRoundTripLatency);
        verify(carrier).emitCachedData(eq(islRoundTripLatency), eq(Endpoint.of(expectedDstSwitchId, expectedDstPort)));
    }

    private Switch createSwitch(SwitchId switchId) {
        Switch sw = Switch.builder().switchId(switchId).build();
        switchRepository.add(sw);
        return sw;
    }

    private void createActiveIsl(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort, long latency) {
        createIsl(srcSwitch, srcPort, dstSwitch, dstPort, latency, ACTIVE);
    }

    private void createIsl(
            Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort, long latency, IslStatus status) {
        Isl isl = Isl.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .status(status)
                .latency(latency).build();
        islRepository.add(isl);
    }
}
