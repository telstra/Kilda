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

package org.openkilda.bluegreen;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.UUID;

public class ZkStateTrackerTest {
    @Test
    public void testProcessLifecycleStartEvent() {
        ZkWriter writer = Mockito.mock(ZkWriter.class);
        UUID startUid = UUID.randomUUID();
        ZkStateTracker stateTracker = new ZkStateTracker(writer);
        stateTracker.shutdownUuid = UUID.randomUUID();
        LifecycleEvent event = LifecycleEvent.builder().signal(Signal.START).uuid(startUid).build();
        stateTracker.processLifecycleEvent(event);
        verify(writer, Mockito.times(1)).setState(eq(1));
        assertTrue(stateTracker.shutdownUuid == null);
    }


    @Test
    public void testProcessLifecycleShutdownEvent() {
        ZkWriter writer = Mockito.mock(ZkWriter.class);
        UUID shutdownUuid = UUID.randomUUID();
        ZkStateTracker stateTracker = new ZkStateTracker(writer);
        stateTracker.startUuid = UUID.randomUUID();
        stateTracker.active = 1;
        LifecycleEvent event = LifecycleEvent.builder().signal(Signal.SHUTDOWN).uuid(shutdownUuid).build();
        stateTracker.processLifecycleEvent(event);
        verify(writer, Mockito.times(1)).setState(eq(0));
        assertTrue(stateTracker.startUuid == null);
    }
}
