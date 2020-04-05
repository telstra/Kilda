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

import com.google.common.collect.ImmutableMap;
import org.apache.storm.tuple.Tuple;

import java.util.Map;

public class FlowRttMetricGenBolt extends MetricGenBolt {

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
        emitMetric("flow.rtt", timestamp, data.getT1() - data.getT0(), tags);
    }
}
