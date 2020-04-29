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

import static org.openkilda.wfm.topology.AbstractTopology.MESSAGE_FIELD;

import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.stats.FlowRttStatsData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import org.apache.storm.tuple.Tuple;

import java.util.Map;

public class FlowRttMetricGenBolt extends MetricGenBolt {

    public static final long TEN_TO_NINE = 1_000_000_000;

    public FlowRttMetricGenBolt(String metricPrefix) {
        super(metricPrefix);
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        InfoMessage message = (InfoMessage) input.getValueByField(MESSAGE_FIELD);
        FlowRttStatsData data = (FlowRttStatsData) message.getData();
        long timestamp = getCommandContext().getCreateTime();
        Map<String, String> tags = ImmutableMap.of(
                "direction", data.getDirection(),
                "flowid", data.getFlowId()
        );

        long t0 = noviflowTimestamp(data.getT0());
        long t1 = noviflowTimestamp(data.getT1());

        emitMetric("flow.rtt", timestamp, t1 - t0, tags);
    }
    
    @VisibleForTesting
    static long noviflowTimestamp(Long v) {
        long seconds = ((v & 0xFFFFFFFF00000000L) >> 32);
        long nanoseconds = (v & 0xFFFFFFFFL);
        return seconds * TEN_TO_NINE + nanoseconds;
    }
}
