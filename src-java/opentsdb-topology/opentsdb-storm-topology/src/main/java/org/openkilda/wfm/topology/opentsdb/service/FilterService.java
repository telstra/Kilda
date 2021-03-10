/* Copyright 2021 Telstra Open Source
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

package org.openkilda.wfm.topology.opentsdb.service;

import org.openkilda.messaging.info.Datapoint;

import com.google.common.annotations.VisibleForTesting;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class FilterService {
    static final long MUTE_IF_NO_UPDATES_SECS = TimeUnit.MINUTES.toSeconds(10);
    static final long MUTE_IF_NO_UPDATES_MILLIS = TimeUnit.SECONDS.toMillis(MUTE_IF_NO_UPDATES_SECS);
    @VisibleForTesting
    Map<DatapointKey, Datapoint> storage = new HashMap<>();
    private DatapointCarrier carrier;


    public FilterService(DatapointCarrier carrier) {
        this.carrier = carrier;
    }

    /**
     * Handle periodic events.
     */
    public void handlePeriodic() {
        long now = System.currentTimeMillis();
        storage.entrySet().removeIf(entry -> now - entry.getValue().getTime() > MUTE_IF_NO_UPDATES_MILLIS);

        if (log.isTraceEnabled()) {
            log.trace("storage after clean tuple: {}", storage.toString());
        }
    }

    /**
     * Handle new datapoint.
     */
    public void handleData(Datapoint datapoint) {
        if (isUpdateRequired(datapoint)) {
            addDatapoint(datapoint);

            List<Object> stream = Stream.of(datapoint.getMetric(), datapoint.getTime(), datapoint.getValue(),
                    datapoint.getTags()).collect(Collectors.toList());

            log.debug("emit datapoint: {}", stream);
            carrier.emitStream(stream);
        } else {
            log.debug("skip datapoint: {}", datapoint);
        }
    }

    @VisibleForTesting
    void addDatapoint(Datapoint datapoint) {
        log.debug("adding datapoint: {}", datapoint);
        log.debug("storage.size: {}", storage.size());
        storage.put(new DatapointKey(datapoint.getMetric(), datapoint.getTags()), datapoint);
        if (log.isTraceEnabled()) {
            log.trace("addDatapoint storage: {}", storage.toString());
        }
    }

    private boolean isUpdateRequired(Datapoint datapoint) {
        boolean update = true;
        Datapoint prevDatapoint = storage.get(new DatapointKey(datapoint.getMetric(), datapoint.getTags()));

        if (prevDatapoint != null) {
            if (log.isTraceEnabled()) {
                log.trace("prev: {} cur: {} equals: {} time_delta: {}",
                        prevDatapoint,
                        datapoint,
                        prevDatapoint.getValue().equals(datapoint.getValue()),
                        datapoint.getTime() - prevDatapoint.getTime()
                );
            }
            update = !prevDatapoint.getValue().equals(datapoint.getValue())
                    || datapoint.getTime() - prevDatapoint.getTime() >= MUTE_IF_NO_UPDATES_MILLIS;
        }
        return update;
    }

    @Value
    private static class DatapointKey {

        private String metric;

        private Map<String, String> tags;
    }
}
