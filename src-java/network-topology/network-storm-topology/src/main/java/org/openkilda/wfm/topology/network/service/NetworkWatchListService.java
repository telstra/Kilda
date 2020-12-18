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

import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

@Slf4j
public class NetworkWatchListService {
    private final IWatchListCarrier carrier;
    private final long genericTickPeriod;
    private final long exhaustedTickPeriod;
    private final long auxiliaryTickPeriod;

    private final Map<Endpoint, WatchListEntry> endpoints = new HashMap<>();
    private final SortedMap<Long, Set<Endpoint>> timeouts = new TreeMap<>();

    public NetworkWatchListService(IWatchListCarrier carrier, long genericTickPeriod,
                                   long exhaustedTickPeriod, long auxiliaryTickPeriod) {
        this.carrier = carrier;
        this.genericTickPeriod = genericTickPeriod;
        this.exhaustedTickPeriod = exhaustedTickPeriod;
        this.auxiliaryTickPeriod = auxiliaryTickPeriod;
    }

    @VisibleForTesting
    Set<Endpoint> getEndpoints() {
        return endpoints.keySet();
    }

    @VisibleForTesting
    SortedMap<Long, Set<Endpoint>> getTimeouts() {
        return timeouts;
    }

    @VisibleForTesting
    void addWatch(Endpoint endpoint, long currentTime) {
        if (endpoints.put(endpoint, new WatchListEntry()) == null) {
            carrier.discoveryRequest(endpoint, currentTime);
            addTimeout(endpoint, currentTime + genericTickPeriod);
        }
    }

    public void addWatch(Endpoint endpoint) {
        log.debug("Watch-list service receive ADD-WATCH request for {}", endpoint);
        addWatch(endpoint, now());
    }

    /**
     * Handle remove watch request.
     */
    public void removeWatch(Endpoint endpoint) {
        log.debug("Watch-list service receive REMOVE-WATCH request for {}", endpoint);
        carrier.watchRemoved(endpoint);
        endpoints.remove(endpoint);
    }

    @VisibleForTesting
    void updateExhaustedPollMode(Endpoint endpoint, boolean enable, long currentTime) {
        log.info("Discovery poll mode for endpoint {} update - exhausted mode requested (enable: '{}')",
                endpoint, enable);
        WatchListEntry watchListEntry = endpoints.computeIfPresent(endpoint,
                (e, listEntry) -> listEntry.toBuilder().exhaustedPollEnabled(enable).build());

        if (watchListEntry != null && !enable) { // when ISL belonging to this endpoint was found
            reloadEndpointTimeout(endpoint, currentTime);
        }
    }

    public void updateExhaustedPollMode(Endpoint endpoint, boolean enable) {
        updateExhaustedPollMode(endpoint, enable, now());
    }

    @VisibleForTesting
    void updateAuxiliaryPollMode(Endpoint endpoint, boolean enable, long currentTime) {
        log.info("Discovery poll mode for endpoint {} update - auxiliary mode requested (enable: '{}')",
                endpoint, enable);
        WatchListEntry watchListEntry = endpoints.computeIfPresent(endpoint,
                (e, listEntry) -> listEntry.toBuilder().auxiliaryPollEnabled(enable).build());

        if (watchListEntry != null && !enable) { // when another mechanism used to determine ISL status is disabled
            reloadEndpointTimeout(endpoint, currentTime);
        }
    }

    public void updateAuxiliaryPollMode(Endpoint endpoint, boolean enable) {
        updateAuxiliaryPollMode(endpoint, enable, now());
    }

    /**
     * Consume timer tick.
     */
    @VisibleForTesting
    void tick(long tickTime) {
        SortedMap<Long, Set<Endpoint>> range = timeouts.subMap(0L, tickTime + 1);
        if (!range.isEmpty()) {
            Map<Endpoint, WatchListEntry> renew = new HashMap<>();

            for (Set<Endpoint> endpointSet : range.values()) {
                for (Endpoint endpoint : endpointSet) {
                    WatchListEntry watchListEntry = endpoints.get(endpoint);
                    if (watchListEntry != null && renew.put(endpoint, watchListEntry) == null) {
                        carrier.discoveryRequest(endpoint, tickTime);
                    }
                }
            }
            range.clear();

            renew.forEach((endpoint, watchListEntry) -> addTimeout(endpoint, tickTime + calculateTimeout(endpoint)));
        }
    }

    /**
     * Process timer tick.
     */
    public void tick() {
        tick(now());
    }

    private long now() {
        return System.nanoTime();
    }

    private void addTimeout(Endpoint endpoint, long timeoutAt) {
        timeouts.computeIfAbsent(timeoutAt, mappingFunction -> new HashSet<>())
                .add(endpoint);
    }

    @VisibleForTesting
    long calculateTimeout(Endpoint endpoint) {
        return Optional.ofNullable(endpoints.get(endpoint)).map(watchListEntry -> {
            if (watchListEntry.isAuxiliaryPollEnabled()) {
                return auxiliaryTickPeriod;
            }
            if (watchListEntry.isExhaustedPollEnabled()) {
                return exhaustedTickPeriod;
            }
            return genericTickPeriod;
        }).orElse(genericTickPeriod);
    }

    private void reloadEndpointTimeout(Endpoint endpoint, long currentTime) {
        for (Set<Endpoint> e : timeouts.values()) {
            e.remove(endpoint);
        }

        carrier.discoveryRequest(endpoint, currentTime);
        addTimeout(endpoint, currentTime + calculateTimeout(endpoint));
    }

    /**
     * Handles topology deactivation event.
     */
    public void deactivate() {
        for (Endpoint entry : endpoints.keySet()) {
            carrier.watchRemoved(entry);
        }
        timeouts.clear();
    }

    /**
     * Handles topology activation event.
     */
    public void activate() {
        long currentTime = now();
        for (Endpoint entry : endpoints.keySet()) {
            carrier.discoveryRequest(entry, currentTime);
            addTimeout(entry, currentTime + genericTickPeriod);
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    private static class WatchListEntry {
        boolean exhaustedPollEnabled;
        boolean auxiliaryPollEnabled;
    }
}
