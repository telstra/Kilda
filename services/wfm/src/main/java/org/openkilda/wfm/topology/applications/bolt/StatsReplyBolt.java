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

package org.openkilda.wfm.topology.applications.bolt;

import static java.lang.String.format;

import org.openkilda.applications.AppData;
import org.openkilda.applications.info.InfoAppData;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.stats.FlowStatsData;
import org.openkilda.messaging.info.stats.MeterStatsData;
import org.openkilda.messaging.info.stats.PortStatsData;
import org.openkilda.messaging.info.stats.SwitchTableStatsData;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.applications.AppsTopology.ComponentId;
import org.openkilda.wfm.topology.applications.mapper.StatsDataMapper;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class StatsReplyBolt extends AbstractBolt {
    public static final String BOLT_ID = ComponentId.STATS_REPLY.toString();

    public static final String FIELD_ID_KEY = MessageKafkaTranslator.FIELD_ID_KEY;
    public static final String FIELD_ID_PAYLOAD = MessageKafkaTranslator.FIELD_ID_PAYLOAD;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    @Override
    protected void handleInput(Tuple input) throws Exception {
        String source = input.getSourceComponent();
        if (ComponentId.STATS_SPOUT.toString().equals(source)) {
            processInput(input);
        } else {
            unhandledInput(input);
        }
    }

    private void processInput(Tuple input) throws PipelineException {
        Message message = pullValue(input, FIELD_ID_PAYLOAD, Message.class);
        if (message instanceof InfoMessage) {
            processInfoData(((InfoMessage) message).getData());
        } else {
            throw new UnsupportedOperationException(format("Unexpected message type \"%s\"", message.getClass()));
        }
    }

    private void processInfoData(InfoData data) {
        if (data instanceof PortStatsData) {
            emitStatsData(StatsDataMapper.INSTANCE.map((PortStatsData) data));
        } else if (data instanceof FlowStatsData) {
            emitStatsData(StatsDataMapper.INSTANCE.map((FlowStatsData) data));
        } else if (data instanceof SwitchTableStatsData) {
            emitStatsData(StatsDataMapper.INSTANCE.map((SwitchTableStatsData) data));
        } else if (data instanceof MeterStatsData) {
            emitStatsData(StatsDataMapper.INSTANCE.map((MeterStatsData) data));
        } else {
            log.debug("Unexpected message payload \"{}}\"", data.getClass());
        }
    }

    private void emitStatsData(InfoAppData payload) {
        String key = getCommandContext().getCorrelationId();
        emit(StatsEncoder.INPUT_STREAM_ID, getCurrentTuple(), makeTuple(key, payload));
    }

    private Values makeTuple(String key, AppData payload) {
        return new Values(key, payload, getCommandContext());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(StatsEncoder.INPUT_STREAM_ID, STREAM_FIELDS);
    }
}
