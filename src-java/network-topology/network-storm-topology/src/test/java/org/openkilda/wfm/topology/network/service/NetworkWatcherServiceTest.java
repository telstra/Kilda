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

package org.openkilda.wfm.topology.network.service;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.ManualClock;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.time.Instant;

@RunWith(MockitoJUnitRunner.class)
public class NetworkWatcherServiceTest {
    private final Integer taskId = 0;
    private final ManualClock testClock = new ManualClock();

    @Mock
    IWatcherCarrier carrier;

    @Before
    public void setup() {
        reset(carrier);
    }

    @Test
    public void addWatch() {
        NetworkWatcherService w = makeService();
        w.addWatch(Endpoint.of(new SwitchId(1), 1), 1);
        w.addWatch(Endpoint.of(new SwitchId(1), 2), 1);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 2), 3);

        assertThat(w.getConfirmedPackets().size(), is(0));
        assertThat(w.getTimeouts().size(), is(3));

        verify(carrier, times(5)).sendDiscovery(any(DiscoverIslCommandData.class));
    }

    @Test
    public void removeWatch() {
        NetworkWatcherService w = makeService();
        w.addWatch(Endpoint.of(new SwitchId(1), 1), 1);
        w.addWatch(Endpoint.of(new SwitchId(1), 2), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 3);
        w.addWatch(Endpoint.of(new SwitchId(2), 2), 4);
        w.addWatch(Endpoint.of(new SwitchId(3), 1), 5);

        verify(carrier, times(5)).sendDiscovery(any(DiscoverIslCommandData.class));

        w.confirmation(Endpoint.of(new SwitchId(1), 2), 1);
        w.confirmation(Endpoint.of(new SwitchId(2), 1), 2);

        assertThat(w.getConfirmedPackets().size(), is(2));
        assertThat(w.getTimeouts().size(), is(5));
        assertThat(w.getDiscoveryPackets().size(), is(3));

        w.removeWatch(Endpoint.of(new SwitchId(1), 2));
        w.removeWatch(Endpoint.of(new SwitchId(2), 2));

        verify(carrier).clearDiscovery(Endpoint.of(new SwitchId(1), 2));
        verify(carrier).clearDiscovery(Endpoint.of(new SwitchId(2), 2));

        assertThat(w.getConfirmedPackets().size(), is(1));
        assertThat(w.getDiscoveryPackets().size(), is(2));

        w.tick(100);
        assertThat(w.getTimeouts().size(), is(0));
    }

    @Test
    public void tick() {
        NetworkWatcherService w = makeService();
        w.addWatch(Endpoint.of(new SwitchId(1), 1), 1);
        w.addWatch(Endpoint.of(new SwitchId(1), 2), 1);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 2), 3);

        assertThat(w.getConfirmedPackets().size(), is(0));
        assertThat(w.getTimeouts().size(), is(3));
        verify(carrier, times(5)).sendDiscovery(any(DiscoverIslCommandData.class));

        w.confirmation(Endpoint.of(new SwitchId(1), 1), 0);
        w.confirmation(Endpoint.of(new SwitchId(2), 1), 2);

        assertThat(w.getConfirmedPackets().size(), is(2));

        w.tick(100);

        assertThat(w.getConfirmedPackets().size(), is(0));

        verify(carrier).discoveryFailed(eq(Endpoint.of(new SwitchId(1), 1)), eq(0L), anyLong());
        verify(carrier).discoveryFailed(eq(Endpoint.of(new SwitchId(2), 1)), eq(2L), anyLong());
        verify(carrier, times(2)).discoveryFailed(any(Endpoint.class), anyLong(), anyLong());

        assertThat(w.getTimeouts().size(), is(0));
    }

    @Test
    public void discovery() {
        NetworkWatcherService w = makeService();
        w.addWatch(Endpoint.of(new SwitchId(1), 1), 1);
        w.addWatch(Endpoint.of(new SwitchId(1), 2), 1);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 1), 2);
        w.addWatch(Endpoint.of(new SwitchId(2), 2), 3);

        assertThat(w.getConfirmedPackets().size(), is(0));
        assertThat(w.getTimeouts().size(), is(3));
        verify(carrier, times(5)).sendDiscovery(any(DiscoverIslCommandData.class));

        w.confirmation(Endpoint.of(new SwitchId(1), 1), 0);
        w.confirmation(Endpoint.of(new SwitchId(2), 1), 2);
        assertThat(w.getConfirmedPackets().size(), is(2));

        PathNode source = new PathNode(new SwitchId(1), 1, 0);
        PathNode destination = new PathNode(new SwitchId(2), 1, 0);

        IslInfoData islAlphaBeta = IslInfoData.builder().source(source).destination(destination).packetId(0L).build();
        IslInfoData islBetaAlpha = IslInfoData.builder().source(destination).destination(source).packetId(2L).build();
        w.discovery(islAlphaBeta);
        w.discovery(islBetaAlpha);

        w.tick(100);

        assertThat(w.getConfirmedPackets().size(), is(0));

        verify(carrier).discoveryReceived(eq(new Endpoint(islAlphaBeta.getSource())), eq(0L), eq(islAlphaBeta),
                                          anyLong());
        verify(carrier).discoveryReceived(eq(new Endpoint(islBetaAlpha.getSource())), eq(2L), eq(islBetaAlpha),
                                          anyLong());
        verify(carrier, times(2)).discoveryReceived(any(Endpoint.class), anyLong(), any(IslInfoData.class), anyLong());

        assertThat(w.getTimeouts().size(), is(0));
    }

    @Test
    public void discoveryBeforeConfirmation() {
        final int awaitTime = 10;
        PathNode source = new PathNode(new SwitchId(1), 1, 0);
        PathNode destination = new PathNode(new SwitchId(2), 1, 0);

        NetworkWatcherService w = makeService(awaitTime);
        w.addWatch(Endpoint.of(source.getSwitchId(), source.getPortNo()), 1);

        verify(carrier, times(1)).sendDiscovery(any(DiscoverIslCommandData.class));

        IslInfoData islAlphaBeta = IslInfoData.builder().source(source).destination(destination).packetId(0L).build();

        w.discovery(islAlphaBeta);
        w.confirmation(new Endpoint(source), 0);
        w.tick(awaitTime + 1);

        verify(carrier).discoveryReceived(eq(new Endpoint(source)), eq(0L), eq(islAlphaBeta), anyLong());
        verify(carrier, never()).discoveryFailed(eq(new Endpoint(source)), anyLong(), anyLong());

        assertThat(w.getConfirmedPackets().size(), is(0));
    }

    @Test
    public void periodicallyProduceRoundTripStatusNotifications() {
        Endpoint alpha = Endpoint.of(new SwitchId(1), 1);
        Endpoint beta = Endpoint.of(new SwitchId(2), 2);

        final PathNode source = new PathNode(alpha.getDatapath(), alpha.getPortNumber(), 0);
        final PathNode destination = new PathNode(beta.getDatapath(), beta.getPortNumber(), 0);

        NetworkWatcherService w = makeService();

        testClock.adjust(Duration.ofMillis(1500));  // one and half round-trip-status notify interval
        w.tick(1500);
        verifyNoMoreInteractions(carrier);

        IslInfoData discovery;

        // discovery without round-trip response
        w.addWatch(alpha, 1501);
        discovery = IslInfoData.builder().source(source).destination(destination).packetId(0L).build();
        w.discovery(discovery);

        testClock.adjust(Duration.ofSeconds(1));
        w.tick(2501);
        verify(carrier, times(0)).sendRoundTripStatus(any());

        // invalid packet id
        Assert.assertTrue(w.getRoundTripPackets().isEmpty());
        w.roundTripDiscovery(alpha, 0);
        testClock.adjust(Duration.ofSeconds(1));
        w.tick(3501);
        verify(carrier, times(0)).sendRoundTripStatus(any());

        // valid round trip response
        w.addWatch(alpha, 3502);
        Instant lastRoundTripTime = testClock.instant();
        w.roundTripDiscovery(alpha, 1L);

        verify(carrier, times(0)).sendRoundTripStatus(any());

        InOrder inOrder = inOrder(carrier);
        // expect periodically sent round trip status
        for (int i = 0; i < 5; i++) {
            testClock.adjust(Duration.ofSeconds(1));
            w.tick(4502 + i * 1000);
            inOrder.verify(carrier)
                    .sendRoundTripStatus(eq(new RoundTripStatus(alpha, lastRoundTripTime, testClock.instant())));
        }

        reset(carrier);

        // remove watch must remove round trip status too
        w.removeWatch(alpha);
        testClock.adjust(Duration.ofSeconds(1));
        w.tick(10000);
        verify(carrier, times(0)).sendRoundTripStatus(any());
    }

    private NetworkWatcherService makeService() {
        return makeService(10);
    }

    private NetworkWatcherService makeService(int awaitTime) {
        Duration roundTripInterval = Duration.ofSeconds(1);
        return new NetworkWatcherService(testClock, carrier, roundTripInterval, awaitTime, taskId);
    }
}
