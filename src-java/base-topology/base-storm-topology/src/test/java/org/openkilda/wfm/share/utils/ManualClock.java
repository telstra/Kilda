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

package org.openkilda.wfm.share.utils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAmount;

/**
 * Main goal of {@link ManualClock} is to be used in test code.Unlike {@link java.time.Clock.FixedClock} value
 * produced by this "clock" can be adjusted. So test code can track how test subject handle time flow.
 */
public class ManualClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public ManualClock() {
        this(Instant.EPOCH, ZoneOffset.UTC);
    }

    public ManualClock(Instant timeNow, ZoneId zone) {
        this.instant = timeNow;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone.equals(this.zone)) {
            return this;
        }
        return new ManualClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void set(Instant timeNow) {
        instant = timeNow;
    }

    public Instant adjust(TemporalAmount offset) {
        instant = instant.plus(offset);
        return instant;
    }
}
