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

import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.messaging.info.event.IslStatusUpdateNotification;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.error.IllegalIslStateException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.isllatency.carriers.CacheCarrier;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class CacheService {
    private CacheCarrier carrier;
    private IslRepository islRepository;
    private Map<Endpoint, Endpoint> cache;

    public CacheService(CacheCarrier carrier, RepositoryFactory repositoryFactory) {
        this.carrier = carrier;
        islRepository = repositoryFactory.createIslRepository();
        cache = new HashMap<>();
        initCache();
    }

    private void initCache() {
        cache.clear();
        try {
            islRepository.findAllActive()
                    .forEach(isl -> {
                        Endpoint source = Endpoint.of(isl.getSrcSwitchId(), isl.getSrcPort());
                        Endpoint destination = Endpoint.of(isl.getDestSwitchId(), isl.getDestPort());

                        cache.put(source, destination);
                        cache.put(destination, source);
                    });
            log.debug("Isl latency cache: {}", cache);
            log.info("Isl latency cache: Initialized");
        } catch (Exception ex) {
            log.error("Error during isl latency cache initialization", ex);
        }
    }

    /**
     * Remove outdated data from cache.
     *
     * @param notification notification about ISL status update
     */
    public void handleUpdateCache(IslStatusUpdateNotification notification) {
        if (notification.getStatus() == IslStatus.MOVED || notification.getStatus() == IslStatus.INACTIVE) {
            Endpoint source = Endpoint.of(notification.getSrcSwitchId(), notification.getSrcPortNo());
            Endpoint destination = Endpoint.of(notification.getDstSwitchId(), notification.getDstPortNo());

            cache.remove(source);
            cache.remove(destination);

            log.info("Remove ISL {}_{} ===> {}_{} from isl latency cache",
                    notification.getSrcSwitchId(), notification.getSrcPortNo(),
                    notification.getDstSwitchId(), notification.getDstPortNo());
        }
    }

    /**
     * Get data about specified ISL from cache.
     *
     * @param roundTripLatency round trip latency info data with source endpoint
     */
    public void handleGetDataFromCache(IslRoundTripLatency roundTripLatency) {
        Endpoint source = Endpoint.of(roundTripLatency.getSrcSwitchId(), roundTripLatency.getSrcPortNo());

        Endpoint destination = cache.get(source);

        try {
            if (destination == null) {
                destination = updateCache(source);
            }

            carrier.emitCachedData(roundTripLatency, destination);
        } catch (IslNotFoundException e) {
            log.debug(String.format("Could not update ISL cache: %s", e.getMessage()), e);
        } catch (IllegalIslStateException e) {
            log.error(String.format("Could not update ISL cache: %s", e.getMessage()), e);
        }
    }

    private Endpoint updateCache(Endpoint source) throws IslNotFoundException, IllegalIslStateException {
        Isl isl = getNotMovedIsl(source);

        Endpoint destination = Endpoint.of(isl.getDestSwitchId(), isl.getDestPort());

        cache.put(source, destination);
        cache.put(destination, source);

        return destination;
    }

    private Isl getNotMovedIsl(Endpoint source) throws IslNotFoundException, IllegalIslStateException {
        List<Isl> notMovedIsls = islRepository.findBySrcEndpoint(source.getDatapath(), source.getPortNumber())
                .stream()
                .filter(isl -> isl.getStatus() != IslStatus.MOVED)
                .collect(Collectors.toList());

        if (notMovedIsls.isEmpty()) {
            String message = String.format("There is no not moved ISLs with src endpoint %s_%d.",
                    source.getDatapath(), source.getPortNumber());
            throw new IslNotFoundException(message);
        }
        if (notMovedIsls.size() > 1) {
            List<String> destinationEndpoints = notMovedIsls.stream()
                    .map(isl -> String.format("%s_%d", isl.getDestSwitch(), isl.getDestPort()))
                    .collect(Collectors.toList());

            String message = String.format(
                    "There is more than one not moved ISLs with source endpoint %s_%d. Destination endpoints: %s",
                    source.getDatapath(), source.getPortNumber(), destinationEndpoints);
            throw new IllegalIslStateException(message);
        }

        return notMovedIsls.get(0);
    }
}
