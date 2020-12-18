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

import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;

import com.google.common.annotations.VisibleForTesting;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

@Slf4j
public class NetworkWatcherService {
    private final IWatcherCarrier carrier;
    private final long awaitTime;
    private final Integer taskId;

    private long packetNo = 0;
    private Set<Packet> discoveryPackets = new HashSet<>();
    private Set<Packet> roundTripPackets = new HashSet<>();

    private Set<Packet> confirmedPackets = new HashSet<>();
    private SortedMap<Long, Set<Packet>> timeouts = new TreeMap<>();

    public NetworkWatcherService(IWatcherCarrier carrier, long awaitTime, Integer taskId) {
        this.carrier = carrier;
        this.awaitTime = awaitTime;
        this.taskId = taskId;
    }

    public void addWatch(Endpoint endpoint) {
        addWatch(endpoint, now());
    }

    void addWatch(Endpoint endpoint, long currentTime) {
        Packet packet = Packet.of(endpoint, packetNo);
        log.debug("Watcher service receive ADD-watch request for {} and produce packet id:{} task:{}",
                  endpoint, packet.packetNo, taskId);

        discoveryPackets.add(packet);
        roundTripPackets.add(packet);

        timeouts.computeIfAbsent(currentTime + awaitTime, key -> new HashSet<>())
                .add(packet);

        DiscoverIslCommandData discoveryRequest = new DiscoverIslCommandData(
                endpoint.getDatapath(), endpoint.getPortNumber(), packetNo);
        carrier.sendDiscovery(discoveryRequest);

        packetNo += 1;
    }

    /**
     * Remove endpoint from discovery process.
     */
    public void removeWatch(Endpoint endpoint) {
        log.debug("Watcher service receive REMOVE-watch request for {}", endpoint);
        carrier.clearDiscovery(endpoint);
        discoveryPackets.removeIf(packet -> packet.endpoint.equals(endpoint));
        roundTripPackets.removeIf(packet -> packet.endpoint.equals(endpoint));
        confirmedPackets.removeIf(packet -> packet.endpoint.equals(endpoint));
    }

    /**
     * Process timer tick.
     */
    public void tick() {
        tick(now());
    }

    void tick(long tickTime) {
        SortedMap<Long, Set<Packet>> range = timeouts.subMap(0L, tickTime + 1);
        if (!range.isEmpty()) {
            for (Set<Packet> e : range.values()) {
                for (Packet ee : e) {
                    timeoutAction(ee);
                }
            }
            range.clear();
        }
    }

    /**
     * .
     */
    public void confirmation(Endpoint endpoint, long packetNo) {
        log.debug("Watcher service receive SEND-confirmation for {} id:{} task:{}", endpoint, packetNo, taskId);
        Packet packet = Packet.of(endpoint, packetNo);
        if (discoveryPackets.remove(packet)) {
            confirmedPackets.add(packet);
        } else if (log.isDebugEnabled()) {
            log.debug("Can't find produced packet for {} id:{} task:{}", endpoint, packetNo, taskId);
        }
    }

    /**
     * Consume discovery event.
     */
    public void discovery(IslInfoData discoveryEvent) {
        Endpoint source = new Endpoint(discoveryEvent.getSource());
        Long packetId = discoveryEvent.getPacketId();
        if (packetId == null) {
            log.error("Got corrupted discovery packet into {} - packetId field is empty", source);
        } else {
            discovery(discoveryEvent, Packet.of(source, packetId));
        }
    }

    private void discovery(IslInfoData discoveryEvent, Packet packet) {
        if (log.isDebugEnabled()) {
            IslReference ref = IslReference.of(discoveryEvent);
            log.debug("Watcher service receive DISCOVERY event for {} id:{} task:{} - {}",
                      packet.endpoint, packet.packetNo, taskId, ref);
        }

        boolean wasProduced = discoveryPackets.remove(packet);
        boolean wasConfirmed = confirmedPackets.remove(packet);
        if (wasProduced || wasConfirmed) {
            carrier.oneWayDiscoveryReceived(packet.endpoint, packet.packetNo, discoveryEvent, now());
        } else {
            log.error("Receive invalid or removed discovery packet on {} id:{} task:{}",
                    packet.endpoint, packet.packetNo, taskId);
        }
    }

    /**
     * Process round trip discovery event.
     */
    public void roundTripDiscovery(Endpoint endpoint, long packetId) {
        log.debug("Watcher service receive ROUND TRIP DISCOVERY for {} id:{} task:{}",
                  endpoint, packetId, taskId);

        if (roundTripPackets.remove(Packet.of(endpoint, packetId))) {
            carrier.roundTripDiscoveryReceived(endpoint, packetId);
        } else {
            log.debug("Receive invalid/stale/duplicate round trip discovery packet for {} id:{} task:{}",
                    endpoint, packetId, taskId);
        }
    }

    private void timeoutAction(Packet packet) {
        discoveryPackets.remove(packet);
        roundTripPackets.remove(packet);

        if (confirmedPackets.remove(packet)) {
            log.debug("Detect discovery packet lost sent via {} id:{} task:{}",
                      packet.endpoint, packet.packetNo, taskId);
            carrier.discoveryFailed(packet.getEndpoint(), packet.packetNo, now());
        }
    }

    private long now() {
        return System.nanoTime();
    }

    @VisibleForTesting
    Set<Packet> getDiscoveryPackets() {
        return discoveryPackets;
    }

    @VisibleForTesting
    Set<Packet> getConfirmedPackets() {
        return confirmedPackets;
    }

    @VisibleForTesting
    SortedMap<Long, Set<Packet>> getTimeouts() {
        return timeouts;
    }

    @Value(staticConstructor = "of")
    public static class Packet {
        private final Endpoint endpoint;
        private final long packetNo;
    }
}
