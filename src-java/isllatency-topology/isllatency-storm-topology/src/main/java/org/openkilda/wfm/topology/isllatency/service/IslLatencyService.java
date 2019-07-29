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

package org.openkilda.wfm.topology.isllatency.service;

import org.openkilda.messaging.info.event.IslOneWayLatency;
import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.model.Isl;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.isllatency.model.IslKey;
import org.openkilda.wfm.topology.isllatency.model.LatencyRecord;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Slf4j
public class IslLatencyService {
    public static final String ONE_WAY_LATENCY = "one way";
    public static final String ROUND_TRIP_LATENCY = "round trip";
    private TransactionManager transactionManager;
    private IslRepository islRepository;
    private SwitchRepository switchRepository;
    private final long latencyUpdateInterval; // emit data in DB interval
    private final long latencyUpdateTimeRange; // average latency will be calculated in this time range

    private Map<IslKey, Queue<LatencyRecord>> roundTripLatencyStorage;
    private Map<IslKey, Queue<LatencyRecord>> oneWayLatencyStorage;
    private Map<IslKey, Instant> nextUpdateTimeMap;
    private Set<IslKey> roundTripLatencyIsSet; // Contains ISLs for which round trip latency were stored in DB

    public IslLatencyService(TransactionManager transactionManager,
                             RepositoryFactory repositoryFactory, long latencyUpdateInterval,
                             long latencyUpdateTimeRange) {
        this.transactionManager = transactionManager;
        this.latencyUpdateInterval = latencyUpdateInterval;
        this.latencyUpdateTimeRange = latencyUpdateTimeRange;
        islRepository = repositoryFactory.createIslRepository();
        switchRepository = repositoryFactory.createSwitchRepository();
        oneWayLatencyStorage = new HashMap<>();
        roundTripLatencyStorage = new HashMap<>();
        roundTripLatencyIsSet = new HashSet<>();
        nextUpdateTimeMap = new HashMap<>();
    }

    /**
     * Handle round trip latency.
     *
     * @param data round trip latency info data
     * @param destination isl destination endpoint
     * @param timestamp latency timestamp
     */
    public void handleRoundTripIslLatency(IslRoundTripLatency data, Endpoint destination, long timestamp) {
        if (data.getLatency() < 0) {
            log.warn("Received invalid round trip latency {} for ISL {}_{} ===> {}_{}. Packet Id: {}",
                    data.getLatency(), data.getSrcSwitchId(), data.getSrcPortNo(),
                    destination.getDatapath(), destination.getPortNumber(), data.getPacketId());
            return;
        }

        log.debug("Received round trip latency {} for ISL {}_{} ===> {}_{}. Packet Id: {}",
                data.getLatency(), data.getSrcSwitchId(), data.getSrcPortNo(),
                destination.getDatapath(), destination.getPortNumber(), data.getPacketId());

        IslKey islKey = new IslKey(data, destination);

        roundTripLatencyStorage.putIfAbsent(islKey, new LinkedList<>());
        roundTripLatencyStorage.get(islKey).add(new LatencyRecord(data.getLatency(), timestamp));

        if (isUpdateRequired(islKey) || !roundTripLatencyIsSet.contains(islKey)) {
            updateRoundTripLatency(data, destination, islKey);
        }
    }

    /**
     * Handle one way latency metric.
     *
     * @param data isl one way latency info data
     * @param timestamp latency timestamp
     */
    public void handleOneWayIslLatency(IslOneWayLatency data, long timestamp) {
        log.debug("Received one way latency {} for ISL {}_{} ===> {}_{}, Packet Id: {}",
                data.getLatency(), data.getSrcSwitchId(), data.getSrcPortNo(),
                data.getDstSwitchId(), data.getDstPortNo(), data.getPacketId());

        IslKey islKey = new IslKey(data);

        oneWayLatencyStorage.putIfAbsent(islKey, new LinkedList<>());
        oneWayLatencyStorage.get(islKey).add(new LatencyRecord(data.getLatency(), timestamp));

        if (isUpdateRequired(islKey)) {
            updateOneWayLatencyIfNeeded(data, islKey);
        }
    }

    private void updateRoundTripLatency(IslRoundTripLatency data, Endpoint destination, IslKey islKey) {
        Queue<LatencyRecord> roundTripRecords = roundTripLatencyStorage.get(islKey);

        pollExpiredRecords(roundTripRecords);
        pollExpiredRecords(oneWayLatencyStorage.get(islKey));

        if (roundTripRecords.isEmpty()) {
            log.warn("Couldn't update round trip latency {} for ISL {}_{} === {}_{}. "
                    + "There is no valid latency records. Packet Id: {}",
                    data.getLatency(), data.getSrcSwitchId(), data.getSrcPortNo(),
                    destination.getDatapath(), destination.getPortNumber(), data.getPacketId());
        }

        long averageLatency = calculateAverageLatency(roundTripRecords);

        boolean updated = updateLatencyInDataBase(data, destination, averageLatency);

        if (updated) {
            nextUpdateTimeMap.put(islKey, getNextUpdateTime());
            roundTripLatencyIsSet.add(islKey);
        }
    }

    private void updateOneWayLatencyIfNeeded(IslOneWayLatency data, IslKey islKey) {
        Queue<LatencyRecord> oneWayRecords = oneWayLatencyStorage.get(islKey);
        pollExpiredRecords(oneWayRecords);

        Queue<LatencyRecord> roundTripRecords = roundTripLatencyStorage.get(islKey);
        pollExpiredRecords(roundTripRecords);
        if (roundTripRecords != null && !roundTripRecords.isEmpty()) {
            // next round trip latency packet will update ISL latency
            return;
        }

        IslKey reverseIslKey = islKey.getReverse();
        Queue<LatencyRecord> reverseRoundTripRecords = roundTripLatencyStorage.get(reverseIslKey);
        pollExpiredRecords(reverseRoundTripRecords);

        boolean updated;
        if (reverseRoundTripRecords != null && !reverseRoundTripRecords.isEmpty()) {
            // reverse ISL has round trip latency records. We can use them for forward ISL
            long averageReverseLatency = calculateAverageLatency(reverseRoundTripRecords);
            updated = updateLatencyInDataBase(data, averageReverseLatency);
        } else {
            // There are no round trip latency records for both ISL direction. We have to use one way latency records
            if (oneWayRecords.isEmpty()) {
                log.warn("Couldn't update round trip latency {} for ISL {}_{} === {}_{}. "
                                + "There is no valid latency records. Packet Id: {}",
                        data.getLatency(), data.getSrcSwitchId(), data.getSrcPortNo(),
                        data.getDstSwitchId(), data.getDstPortNo(), data.getPacketId());
                return;
            }

            long averageOneWayLatency = calculateAverageLatency(oneWayRecords);
            updated = updateLatencyInDataBase(data, averageOneWayLatency);
        }

        if (updated) {
            nextUpdateTimeMap.put(islKey, getNextUpdateTime());
            roundTripLatencyIsSet.remove(islKey);
        }
    }

    private boolean updateLatencyInDataBase(IslOneWayLatency data, long latency) {
        return updateLatencyInDataBase(data.getSrcSwitchId(), data.getSrcPortNo(),
                data.getDstSwitchId(), data.getDstPortNo(), latency, data.getPacketId(), ONE_WAY_LATENCY);
    }

    private boolean updateLatencyInDataBase(IslRoundTripLatency data, Endpoint destination, long latency) {
        return updateLatencyInDataBase(data.getSrcSwitchId(), data.getSrcPortNo(),
                destination.getDatapath(), destination.getPortNumber(), latency, data.getPacketId(),
                ROUND_TRIP_LATENCY);
    }

    private boolean updateLatencyInDataBase(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort,
                                         long latency, long packetId, String latencyType) {
        if (latency < 0) {
            log.warn("Couldn't update {} latency for ISL {}_{} ===> {}_{}. Packet id:{}. Latency must be positive.",
                    latencyType, srcSwitch, srcPort, dstSwitch, dstPort, packetId);
            return false;
        }

        try {
            updateIslLatency(srcSwitch, srcPort, dstSwitch, dstPort, latency);
            log.debug("Updated {} latency for ISL {}_{} ===( {} ns )===> {}_{}. Packet id:{}",
                    latencyType, srcSwitch, srcPort, latency, dstSwitch, dstPort, packetId);
        } catch (SwitchNotFoundException | IslNotFoundException e) {
            log.warn("Couldn't update {} latency for ISL {}_{} ===> {}_{}. Packet id:{}. {}",
                    latencyType, srcSwitch, srcPort, dstSwitch, dstPort, packetId, e.getMessage());
            return false;
        }
        return true;
    }

    @VisibleForTesting
    void pollExpiredRecords(Queue<LatencyRecord> recordsQueue) {
        if (recordsQueue == null) {
            return;
        }
        Instant oldestTimeInRange = Instant.now().minusSeconds(latencyUpdateTimeRange);
        while (!recordsQueue.isEmpty()
                && Instant.ofEpochMilli(recordsQueue.peek().getTimestamp()).isBefore(oldestTimeInRange)) {
            recordsQueue.poll();
        }
    }

    @VisibleForTesting
    long calculateAverageLatency(Queue<LatencyRecord> recordsQueue) {
        if (recordsQueue.isEmpty()) {
            log.error("Couldn't calculate average latency. Records queue is empty");
            return -1;
        }

        long sum = 0;
        for (LatencyRecord record : recordsQueue) {
            sum += record.getLatency();
        }
        return sum / recordsQueue.size();
    }

    @VisibleForTesting
    boolean isUpdateRequired(IslKey islKey) {
        return Instant.now().isAfter(nextUpdateTimeMap.getOrDefault(islKey, Instant.MIN));
    }

    @VisibleForTesting
    Instant getNextUpdateTime() {
        return Instant.now().plusSeconds(latencyUpdateInterval);
    }

    /**
     * Update latency of isl by source and destination endpoint.
     *
     * @param srcSwitchId ID of source switch.
     * @param srcPort source port.
     * @param dstSwitchId ID of destination switch.
     * @param dstPort destination port.
     * @param latency latency to update
     *
     * @throws SwitchNotFoundException if src or dst switch is not found
     * @throws IslNotFoundException if isl is not found
     */
    @VisibleForTesting
    void updateIslLatency(
            SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort, long latency)
            throws SwitchNotFoundException, IslNotFoundException {
        transactionManager.doInTransaction(() -> {
            Isl isl = islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort)
                    .orElseThrow(() -> new IslNotFoundException(srcSwitchId, srcPort, dstSwitchId, dstPort));
            isl.setLatency(latency);
        });
    }
}
