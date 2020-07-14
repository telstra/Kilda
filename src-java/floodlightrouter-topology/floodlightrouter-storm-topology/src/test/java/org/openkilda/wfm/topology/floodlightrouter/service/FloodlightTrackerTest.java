/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.topology.floodlightrouter.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.utils.ManualClock;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mapstruct.ap.internal.util.Collections;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class FloodlightTrackerTest {
    private static long DEFAULT_ALIVE_TIMEOUT = 5L;
    private static long DEFAULT_RESPONSE_TIMEOUT = 1L;
    private static String REGION_ONE = "1";
    private static String REGION_TWO = "2";

    private static SwitchId SWITCH_R1_ONE = new SwitchId(1001);
    private static SwitchId SWITCH_R1_TWO = new SwitchId(1002);

    private static SwitchId SWITCH_R2_ONE = new SwitchId(2001);
    private static SwitchId SWITCH_R2_TWO = new SwitchId(2002);

    private final ManualClock testClock = new ManualClock(Instant.EPOCH, ZoneOffset.UTC);
    private final AliveSetup aliveSetup = new AliveSetup(testClock, DEFAULT_ALIVE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT);
    private Set<String> regions = Collections.asSet(REGION_ONE, REGION_TWO);
    private final FloodlightTracker service = new FloodlightTracker(
            regions, aliveSetup);


    @Mock
    MessageSender carrier;

    @Before
    public void setUp() {
        testClock.set(Instant.EPOCH);
    }

    @Test
    public void testUpdateSwitchRegion() {
        FloodlightTracker floodlightTracker = new FloodlightTracker(regions, DEFAULT_ALIVE_TIMEOUT,
                                                                    DEFAULT_RESPONSE_TIMEOUT);
        floodlightTracker.updateSwitchRegion(SWITCH_R1_ONE, REGION_ONE);
        assertTrue(floodlightTracker.switchRegionMap.containsKey(SWITCH_R1_ONE));

    }

    @Test
    public void testLookupRegion() {
        FloodlightTracker floodlightTracker = new FloodlightTracker(regions, DEFAULT_ALIVE_TIMEOUT,
                                                                    DEFAULT_RESPONSE_TIMEOUT);
        floodlightTracker.switchRegionMap.putIfAbsent(SWITCH_R1_ONE, REGION_ONE);
        String actualRegion = floodlightTracker.lookupRegion(SWITCH_R1_ONE);
        assertEquals(REGION_ONE, actualRegion);
    }

    @Test
    public void testAliveExpiration() {
        FloodlightTracker floodlightTracker = new FloodlightTracker(regions, aliveSetup);

        floodlightTracker.updateSwitchRegion(SWITCH_R1_ONE, REGION_ONE);
        floodlightTracker.updateSwitchRegion(SWITCH_R2_ONE, REGION_TWO);

        // all regions are alive on start
        floodlightTracker.handleAliveExpiration(carrier);

        verifyNoMoreInteractions(carrier);

        // region 1 received an alive response, while region 2 does not
        testClock.adjust(Duration.ofSeconds(DEFAULT_ALIVE_TIMEOUT - 1));
        floodlightTracker.handleAliveResponse(REGION_ONE, testClock.instant().toEpochMilli());

        testClock.set(Instant.EPOCH.plusSeconds(DEFAULT_ALIVE_TIMEOUT + 1));
        floodlightTracker.handleAliveExpiration(carrier);
        verify(carrier).emitSwitchUnmanagedNotification(SWITCH_R2_ONE);
        verifyNoMoreInteractions(carrier);
        reset(carrier);

        // region 1 become unavailable too
        testClock.set(Instant.EPOCH.plusSeconds(DEFAULT_ALIVE_TIMEOUT * 2));
        floodlightTracker.handleAliveExpiration(carrier);
        verify(carrier).emitSwitchUnmanagedNotification(SWITCH_R1_ONE);
        verifyNoMoreInteractions(carrier);
    }

    @Test
    public void testAliveResponseHandling() {
        // force all regions to become "expired/unmanaged"
        testClock.adjust(Duration.ofSeconds(DEFAULT_ALIVE_TIMEOUT + 1));
        service.handleAliveExpiration(carrier);
        verifyNoMoreInteractions(carrier); // there is no switches, so there is no unmanaged notifications

        // 1 second later
        testClock.adjust(Duration.ofSeconds(1));
        Assert.assertTrue(service.handleAliveResponse(REGION_ONE, testClock.instant().toEpochMilli()));
        Assert.assertTrue(service.handleAliveResponse(REGION_TWO, testClock.instant().toEpochMilli()));

        // 1 second later (do not require sync on consecutive responses)
        testClock.adjust(Duration.ofSeconds(1));
        Assert.assertFalse(service.handleAliveResponse(REGION_ONE, testClock.instant().toEpochMilli()));
        Assert.assertFalse(service.handleAliveResponse(REGION_TWO, testClock.instant().toEpochMilli()));
    }

    @Test
    public void testRegionsRequireAliveRequest() {
        // need to move clock on any not zero value, because all interval overcome check use strict comparison '<'
        testClock.adjust(Duration.ofMillis(1));

        Assert.assertEquals(service.getRegionsForAliveRequest(), regions);
        // this call is clock independent, so it will be same in consecutive call
        Assert.assertEquals(service.getRegionsForAliveRequest(), regions);

        // register response from region 1
        service.handleAliveResponse(REGION_ONE, testClock.instant().toEpochMilli());
        Assert.assertEquals(service.getRegionsForAliveRequest(), Collections.asSet(REGION_TWO));

        // DEFAULT_RESPONSE_TIMEOUT s later
        testClock.adjust(Duration.ofSeconds(DEFAULT_RESPONSE_TIMEOUT));
        Assert.assertEquals(service.getRegionsForAliveRequest(), Collections.asSet(REGION_TWO));

        // 1 ms later
        testClock.adjust(Duration.ofMillis(1));
        Assert.assertEquals(service.getRegionsForAliveRequest(), regions);
    }
}
