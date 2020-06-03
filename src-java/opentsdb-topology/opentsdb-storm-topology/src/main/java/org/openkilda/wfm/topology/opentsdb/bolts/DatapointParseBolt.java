/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.topology.opentsdb.bolts;

import static java.util.Arrays.asList;

import org.openkilda.messaging.info.Datapoint;
import org.openkilda.messaging.info.DatapointEntries;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DatapointParseBolt extends BaseRichBolt {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatapointParseBolt.class);
    private transient OutputCollector collector;

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            List<Datapoint> datapoints;

            Object payload = tuple.getValueByField(MessageKafkaTranslator.FIELD_ID_PAYLOAD);
            if (payload instanceof Datapoint) {
                LOGGER.debug("Processing datapoint: {}", payload);
                datapoints = Collections.singletonList((Datapoint) payload);
            } else if (payload instanceof DatapointEntries) {
                LOGGER.warn("Processing datapoints: {}", payload);
                datapoints = ((DatapointEntries) payload).getDatapointEntries();
            } else {
                LOGGER.error("Unhandled input tuple from {} with data {}", getClass().getName(), payload);
                return;
            }

            for (Datapoint datapoint : datapoints) {
                collector.emit(asList(datapoint.simpleHashCode(), datapoint));
            }
        } catch (Exception e) {
            LOGGER.error("Failed process tuple: {}", tuple, e);
        } finally {
            collector.ack(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("hash", "datapoint"));
    }
}
