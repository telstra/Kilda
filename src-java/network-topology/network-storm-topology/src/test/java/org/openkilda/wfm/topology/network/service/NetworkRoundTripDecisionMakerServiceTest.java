/* Copyright 2020 Telstra Open Source
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.openkilda.model.SwitchId;
import org.openkilda.stubs.ManualClock;
import org.openkilda.wfm.share.model.Endpoint;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;

@RunWith(MockitoJUnitRunner.class)
public class NetworkRoundTripDecisionMakerServiceTest {
    private static final Duration EXPIRE_DELAY = Duration.ofSeconds(4);

    @Mock
    IDecisionMakerCarrier carrier;

    private ManualClock clock;

    @Before
    public void setup() {
        reset(carrier);

        clock = new ManualClock();
    }

    @Test
    public void registerExpandExpire() {
        final Endpoint endpoint = Endpoint.of(new SwitchId(1), 1);
        final NetworkRoundTripDecisionMakerService service = makeService();

        service.discovered(endpoint, 1L);
        verify(carrier).linkRoundTripActive(eq(endpoint));
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(3));  // 3 seconds
        service.discovered(endpoint, 2L);
        verify(carrier, times(2)).linkRoundTripActive(eq(endpoint));
        service.tick();
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(2));  // 5 seconds
        service.tick();
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(4));  // 9 seconds
        service.tick();
        verify(carrier).linkRoundTripInactive(endpoint);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void shouldIgnoreOutdatedPacket() {
        final Endpoint endpoint = Endpoint.of(new SwitchId(1), 1);
        final NetworkRoundTripDecisionMakerService service = makeService();

        service.discovered(endpoint, 2L);
        verify(carrier).linkRoundTripActive(endpoint);
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(3));  // 3 seconds
        service.discovered(endpoint, 1L);
        service.tick();
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(2));  // 5 seconds
        service.tick();
        // packetId == 1 must be ignored, so this endpoint must expire on seconds number 4 not on seconds number 7
        verify(carrier).linkRoundTripInactive(endpoint);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void shouldNotEmitAnyNotificationsAfterRemoval() {
        final Endpoint endpoint = Endpoint.of(new SwitchId(1), 1);
        final NetworkRoundTripDecisionMakerService service = makeService();

        service.discovered(endpoint, 1L);
        verify(carrier).linkRoundTripActive(endpoint);
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(3));  // 3 seconds
        service.tick();
        service.clear(endpoint);
        verifyNoMoreInteractions(carrier);

        clock.adjust(Duration.ofSeconds(2));  // 5 seconds
        service.tick();
        verifyNoMoreInteractions(carrier);
    }

    private NetworkRoundTripDecisionMakerService makeService() {
        return new NetworkRoundTripDecisionMakerService(clock, carrier, EXPIRE_DELAY);
    }
}
