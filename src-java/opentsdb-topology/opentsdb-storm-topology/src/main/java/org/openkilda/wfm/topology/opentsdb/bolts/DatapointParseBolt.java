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

import org.openkilda.messaging.info.Datapoint;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class DatapointParseBolt extends AbstractBolt {

    public DatapointParseBolt(String lifeCycleEventSourceComponent) {
        super(lifeCycleEventSourceComponent);
    }

    @Override
    public void handleInput(Tuple tuple) {
        if (active) {
            InfoData data = (InfoData) tuple.getValueByField(MessageKafkaTranslator.FIELD_ID_PAYLOAD);
            log.debug("Processing datapoint: {}", data);
            try {
                if (data instanceof Datapoint) {
                    Datapoint datapoint = (Datapoint) data;
                    List<Object> stream = Stream.of(datapoint.simpleHashCode(), datapoint)
                            .collect(Collectors.toList());
                    emit(stream);
                } else {
                    log.error("Unhandled input tuple from {} with data {}", getClass().getName(), data);
                }
            } catch (Exception e) {
                log.error("Failed process data: {}", data, e);
            }
        } else {
            log.debug("DatapointParseBolt is inactive");
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("hash", "datapoint"));
        declarer.declareStream(ZkStreams.ZK.toString(), new Fields(ZooKeeperBolt.FIELD_ID_STATE,
                ZooKeeperBolt.FIELD_ID_CONTEXT));
    }
}
