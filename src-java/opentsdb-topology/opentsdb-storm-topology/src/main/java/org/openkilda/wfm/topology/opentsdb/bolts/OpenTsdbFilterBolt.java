/* Copyright 2017 Telstra Open Source
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

import org.openkilda.messaging.info.Datapoint;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.opentsdb.service.DatapointCarrier;
import org.openkilda.wfm.topology.opentsdb.service.FilterService;

import org.apache.storm.Config;
import org.apache.storm.Constants;
import org.apache.storm.opentsdb.bolt.TupleOpenTsdbDatapointMapper;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class OpenTsdbFilterBolt extends AbstractBolt implements DatapointCarrier {

    private static final long MUTE_IF_NO_UPDATES_SECS = TimeUnit.MINUTES.toSeconds(10);

    private static final Fields DECLARED_FIELDS =
            new Fields(TupleOpenTsdbDatapointMapper.DEFAULT_MAPPER.getMetricField(),
                    TupleOpenTsdbDatapointMapper.DEFAULT_MAPPER.getTimestampField(),
                    TupleOpenTsdbDatapointMapper.DEFAULT_MAPPER.getValueField(),
                    TupleOpenTsdbDatapointMapper.DEFAULT_MAPPER.getTagsField());

    private FilterService filterService;

    @Override
    public void init() {
        this.filterService = new FilterService(this);
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        Config conf = new Config();
        conf.put(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS, MUTE_IF_NO_UPDATES_SECS);
        return conf;
    }


    @Override
    public void handleInput(Tuple tuple) throws PipelineException {

        if (isTickTuple(tuple)) {
            filterService.handlePeriodic();

        } else {
            Datapoint datapoint = pullValue(tuple, "datapoint", Datapoint.class);
            filterService.handleData(datapoint);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(DECLARED_FIELDS);
    }

    private boolean isTickTuple(Tuple tuple) {
        String sourceComponent = tuple.getSourceComponent();
        String sourceStreamId = tuple.getSourceStreamId();

        return Constants.SYSTEM_COMPONENT_ID.equals(sourceComponent)
                && Constants.SYSTEM_TICK_STREAM_ID.equals(sourceStreamId);
    }

    @Override
    public void emitStream(List<Object> stream) {
        emit(stream);
    }
}
