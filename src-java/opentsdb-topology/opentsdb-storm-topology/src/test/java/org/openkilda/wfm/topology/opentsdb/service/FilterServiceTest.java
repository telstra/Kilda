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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.openkilda.wfm.topology.opentsdb.service.FilterService.MUTE_IF_NO_UPDATES_MILLIS;

import org.openkilda.messaging.info.Datapoint;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

public class FilterServiceTest {

    @Test
    public void testHandlePeriodic() {
        Map<String, String> tags = new HashMap<>();
        tags.put("key", "value");
        DatapointCarrier mockCarrier = Mockito.mock(DatapointCarrier.class);
        FilterService filterService = new FilterService(mockCarrier);
        Datapoint datapoint = new Datapoint("test_metric",
                System.currentTimeMillis() - (1 + MUTE_IF_NO_UPDATES_MILLIS), tags, 10);
        filterService.addDatapoint(datapoint);
        assertEquals(1, filterService.storage.size());
        filterService.handlePeriodic();
        assertEquals(0, filterService.storage.size());
    }


    @Test
    public void testHandleDatapointNoUpdatesHappenedTooShortDelay() {
        Map<String, String> tags = new HashMap<>();
        tags.put("key", "value");
        DatapointCarrier mockCarrier = Mockito.mock(DatapointCarrier.class);
        FilterService filterService = new FilterService(mockCarrier);
        long ts = System.currentTimeMillis();
        Datapoint datapoint = new Datapoint("test_metric", ts, tags, 10);
        filterService.addDatapoint(datapoint);
        assertEquals(1, filterService.storage.size());
        datapoint = new Datapoint("test_metric", ts + 5, tags, 10);
        filterService.handleData(datapoint);
        Mockito.verify(mockCarrier, Mockito.times(0)).emitStream(any());
    }

    @Test
    public void testHandleDatapointUpdatesHappenedNotSameData() {
        Map<String, String> tags = new HashMap<>();
        tags.put("key", "value");
        DatapointCarrier mockCarrier = Mockito.mock(DatapointCarrier.class);
        FilterService filterService = new FilterService(mockCarrier);
        long ts = System.currentTimeMillis();
        Datapoint datapoint = new Datapoint("test_metric", ts, tags, 10);
        filterService.addDatapoint(datapoint);
        assertEquals(1, filterService.storage.size());
        datapoint = new Datapoint("test_metric", ts + 5, tags, 11);
        filterService.handleData(datapoint);
        Mockito.verify(mockCarrier, Mockito.times(1)).emitStream(any());
    }

    @Test
    public void testHandleDatapointUpdatesHappenedThresholdDelay() {
        Map<String, String> tags = new HashMap<>();
        tags.put("key", "value");
        DatapointCarrier mockCarrier = Mockito.mock(DatapointCarrier.class);
        FilterService filterService = new FilterService(mockCarrier);
        long ts = System.currentTimeMillis();
        Datapoint datapoint = new Datapoint("test_metric", ts, tags, 10);
        filterService.addDatapoint(datapoint);
        assertEquals(1, filterService.storage.size());
        datapoint = new Datapoint("test_metric", ts + MUTE_IF_NO_UPDATES_MILLIS + 10, tags, 10);
        filterService.handleData(datapoint);
        Mockito.verify(mockCarrier, Mockito.times(1)).emitStream(any());
    }
}
