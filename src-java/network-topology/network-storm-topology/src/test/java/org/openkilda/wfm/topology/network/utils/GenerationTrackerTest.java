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

package org.openkilda.wfm.topology.network.utils;

import org.junit.Assert;
import org.junit.Test;

public class GenerationTrackerTest {
    private static final long LIMIT = 5;

    @Test
    public void testNewGenerationsAllocation() {
        GenerationTracker<String> tracker = makeTracker();

        long empty = tracker.getActual();
        Assert.assertEquals(-1, empty);
        Assert.assertEquals(empty, tracker.getActual());

        String firstId = "A";
        long first = tracker.identify(firstId);
        Assert.assertNotEquals(empty, first);
        Assert.assertEquals(first, tracker.getActual());
        Assert.assertEquals(first, tracker.identify(firstId));

        String secondId = "B";
        long second = tracker.identify(secondId);
        Assert.assertNotEquals(empty, second);
        Assert.assertNotEquals(first, second);
        Assert.assertEquals(second, tracker.getActual());
        Assert.assertEquals(second, tracker.identify(secondId));
    }

    @Test
    public void testWrapOverLimit() {
        GenerationTracker<String> tracker = makeTracker();
        String referenceId = "A";
        long referenceGeneration = tracker.identify(referenceId);

        for (int i = 0; i < LIMIT - 1; i++) {
            Assert.assertNotEquals(referenceGeneration, tracker.identify(String.format("id-%d", i)));
            Assert.assertEquals(referenceGeneration, tracker.identify(referenceId));
        }

        tracker.identify("B");
        Assert.assertNotEquals(referenceGeneration, tracker.identify(referenceId));
    }

    private GenerationTracker<String> makeTracker() {
        return new GenerationTracker<>(LIMIT);
    }
}
