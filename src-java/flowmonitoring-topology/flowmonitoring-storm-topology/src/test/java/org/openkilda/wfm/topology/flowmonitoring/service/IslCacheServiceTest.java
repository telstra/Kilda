/* Copyright 2021 Telstra Open Source
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

package org.openkilda.wfm.topology.flowmonitoring.service;

import static org.junit.Assert.assertEquals;
import static org.openkilda.model.IslStatus.ACTIVE;

import org.openkilda.messaging.info.event.IslChangedInfoData;
import org.openkilda.messaging.info.event.IslOneWayLatency;
import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.model.Isl;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.topology.flowmonitoring.model.Link;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class IslCacheServiceTest extends InMemoryGraphBasedTest {
    private static final long ISL_RTT_LATENCY_EXPIRATION = 2;

    private static final SwitchId FIRST_SWITCH = new SwitchId(1);
    private static final SwitchId SECOND_SWITCH = new SwitchId(2);
    private static final SwitchId THIRD_SWITCH = new SwitchId(3);
    private static final int ISL_SRC_PORT = 10;
    private static final int ISL_DST_PORT = 20;
    private static final int ISL_SRC_PORT_2 = 11;
    private static final int ISL_DST_PORT_2 = 21;

    private IslRepository islRepository;

    private IslCacheService service;

    @Before
    public void setup() {
        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();

        Switch firstSwitch = createTestSwitch(FIRST_SWITCH.toLong());
        Switch secondSwitch = createTestSwitch(SECOND_SWITCH.toLong());
        Switch thirdSwitch = createTestSwitch(THIRD_SWITCH.toLong());

        createIsl(firstSwitch, ISL_SRC_PORT, secondSwitch, ISL_DST_PORT);
        createIsl(secondSwitch, ISL_DST_PORT, firstSwitch, ISL_SRC_PORT);
        createIsl(secondSwitch, ISL_SRC_PORT_2, thirdSwitch, ISL_DST_PORT_2);
        createIsl(thirdSwitch, ISL_DST_PORT_2, secondSwitch, ISL_SRC_PORT_2);

        service = new IslCacheService(persistenceManager, ISL_RTT_LATENCY_EXPIRATION);
    }

    @Test
    public void shouldCalculateLatencyForPathByOneWayLatency() {
        long latency = 100L;
        IslOneWayLatency islOneWayLatency = new IslOneWayLatency(FIRST_SWITCH, ISL_SRC_PORT,
                SECOND_SWITCH, ISL_DST_PORT, latency, 1L);
        service.handleOneWayLatency(islOneWayLatency);

        long actual = service.calculateLatencyForPath(getPath());

        assertEquals(latency, actual);
    }

    @Test
    public void shouldCalculateLatencyForPathByRttLatency() {
        long latency = 100L;
        IslRoundTripLatency islRoundTripLatency = new IslRoundTripLatency(FIRST_SWITCH, ISL_SRC_PORT, latency, 1L);
        service.handleRoundTripLatency(islRoundTripLatency);

        IslRoundTripLatency isl2RoundTripLatency = new IslRoundTripLatency(SECOND_SWITCH, ISL_SRC_PORT_2, latency, 1L);
        service.handleRoundTripLatency(isl2RoundTripLatency);

        long actual = service.calculateLatencyForPath(Arrays.asList(Link.builder()
                        .srcSwitchId(FIRST_SWITCH)
                        .srcPort(ISL_SRC_PORT)
                        .destSwitchId(SECOND_SWITCH)
                        .destPort(ISL_DST_PORT)
                        .build(),
                Link.builder()
                        .srcSwitchId(SECOND_SWITCH)
                        .srcPort(ISL_SRC_PORT_2)
                        .destSwitchId(THIRD_SWITCH)
                        .destPort(ISL_DST_PORT_2)
                        .build()));

        assertEquals(latency * 2, actual);
    }

    @Test
    public void shouldCalculateLatencyForPathWithExpiredRttValue() throws InterruptedException {
        long oneWayLatency = 100L;
        long rttLatency = 1000L;
        IslOneWayLatency islOneWayLatency = new IslOneWayLatency(FIRST_SWITCH, ISL_SRC_PORT,
                SECOND_SWITCH, ISL_DST_PORT, oneWayLatency, 1L);
        service.handleOneWayLatency(islOneWayLatency);
        IslRoundTripLatency islRoundTripLatency = new IslRoundTripLatency(FIRST_SWITCH, ISL_SRC_PORT, rttLatency, 1L);
        service.handleRoundTripLatency(islRoundTripLatency);

        Thread.sleep(ISL_RTT_LATENCY_EXPIRATION * 1200);

        long actual = service.calculateLatencyForPath(getPath());

        assertEquals(oneWayLatency, actual);
    }

    @Test
    public void shouldRemoveDeletedIslFromCache() {
        long rttLatency = 1000L;
        IslRoundTripLatency islRoundTripLatency = new IslRoundTripLatency(FIRST_SWITCH, ISL_SRC_PORT, rttLatency, 1L);
        service.handleRoundTripLatency(islRoundTripLatency);

        IslChangedInfoData islChangedInfoData = IslChangedInfoData.builder()
                .source(NetworkEndpoint.builder().datapath(FIRST_SWITCH).portNumber(ISL_SRC_PORT).build())
                .build();
        service.handleIslChangedData(islChangedInfoData);

        long actual = service.calculateLatencyForPath(getPath());

        assertEquals(0, actual);
    }

    @Test
    public void shouldHandleMovedIslFromCache() {
        IslRoundTripLatency islRoundTripLatency = new IslRoundTripLatency(FIRST_SWITCH, ISL_SRC_PORT, 7, 1L);
        service.handleRoundTripLatency(islRoundTripLatency);

        int newPort = 33;
        IslChangedInfoData islChangedInfoData = IslChangedInfoData.builder()
                .source(NetworkEndpoint.builder().datapath(FIRST_SWITCH).portNumber(ISL_SRC_PORT).build())
                .destination(NetworkEndpoint.builder().datapath(THIRD_SWITCH).portNumber(newPort).build())
                .build();
        service.handleIslChangedData(islChangedInfoData);

        long rttLatency = 1000L;
        IslRoundTripLatency isl2RoundTripLatency = new IslRoundTripLatency(FIRST_SWITCH, ISL_SRC_PORT, rttLatency, 1L);
        service.handleRoundTripLatency(isl2RoundTripLatency);

        long actual = service.calculateLatencyForPath(Collections.singletonList(Link.builder()
                .srcSwitchId(FIRST_SWITCH)
                .srcPort(ISL_SRC_PORT)
                .destSwitchId(THIRD_SWITCH)
                .destPort(newPort)
                .build()));

        assertEquals(rttLatency, actual);
    }

    private void createIsl(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort) {
        Isl isl = Isl.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .status(ACTIVE)
                .latency(100)
                .build();
        islRepository.add(isl);
    }

    private List<Link> getPath() {
        return Collections.singletonList(Link.builder()
                .srcSwitchId(FIRST_SWITCH)
                .srcPort(ISL_SRC_PORT)
                .destSwitchId(SECOND_SWITCH)
                .destPort(ISL_DST_PORT)
                .build());
    }
}
