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

package org.openkilda.wfm.topology.stats.metrics;

import static org.junit.Assert.assertEquals;
import static org.openkilda.wfm.topology.stats.metrics.FlowRttMetricGenBolt.noviflowTimestamp;

import org.junit.Test;

public class FlowRttMetricGenBoltTest {

    @Test
    public void testNoviflowTimstampToLong() {

        long seconds = 123456789;
        long nanoseconds = 987654321;

        long timestampNovi = (seconds << 32) + nanoseconds;

        assertEquals(123456789_987654321L, noviflowTimestamp(timestampNovi));
    }
}
