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

import org.openkilda.wfm.share.model.Endpoint;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

@Slf4j
public class NetworkRoundTripDecisionMakerService {
    private final Clock clock;

    private final IDecisionMakerCarrier carrier;
    private final Duration expireDelay;

    private final Map<Endpoint, Entry> entries = new HashMap<>();
    private final SortedMap<Instant, Set<Endpoint>> timeouts = new TreeMap<>();

    public NetworkRoundTripDecisionMakerService(IDecisionMakerCarrier carrier, Duration expireDelay) {
        this(Clock.systemUTC(), carrier, expireDelay);
    }

    public NetworkRoundTripDecisionMakerService(Clock clock, IDecisionMakerCarrier carrier, Duration expireDelay) {
        this.clock = clock;

        this.carrier = carrier;
        this.expireDelay = expireDelay;
    }

    /**
     * Register/update round trip tracking data for specific endpoint.
     */
    public void discovered(Endpoint endpoint, long packetId) {
        Entry entry = entries.get(endpoint);
        if (entry != null && packetId < entry.getLastSeenPacketId()) {
            log.debug("Ignore staled round trip discovery on {} (packet_id: {})", endpoint, packetId);
            return;
        }

        Instant expireAt = clock.instant().plus(expireDelay);
        entries.put(endpoint, new Entry(packetId, expireAt));
        timeouts.computeIfAbsent(expireAt, key -> new HashSet<>()).add(endpoint);

        if (entry == null) {
            log.info("Register round trip status entry for {}", endpoint);
        }

        // Emits each registered round trip event. Some ISLs monitor can ignore some round trip ACTIVATE events as part
        // of race conditions prevention fight.
        carrier.linkRoundTripActive(endpoint);
    }

    /**
     * Remove tracking for specific endpoint.
     */
    public void clear(Endpoint endpoint) {
        if (entries.remove(endpoint) != null) {
            log.info("Remove round trip status entry for {}", endpoint);
        }
    }

    /**
     * Process time tick - locate expired entries and emit round trip INACTIVE events for them.
     */
    public void tick() {
        Instant now = clock.instant();
        SortedMap<Instant, Set<Endpoint>> range = timeouts.headMap(now);

        Set<Endpoint> processed = new HashSet<>();
        for (Set<Endpoint> batch : range.values()) {
            for (Endpoint endpoint : batch) {
                if (processed.contains(endpoint)) {
                    continue;
                }
                processed.add(endpoint);
                checkExpiration(now, endpoint);
            }
        }
        range.clear();
    }

    private void checkExpiration(Instant now, Endpoint endpoint) {
        Entry entry = entries.get(endpoint);
        if (entry != null && entry.getExpireAt().isBefore(now)) {
            carrier.linkRoundTripInactive(endpoint);
        }
    }

    @Value
    private static class Entry {
        long lastSeenPacketId;
        Instant expireAt;
    }
}
