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

package org.openkilda.wfm.topology.versioning.bolts;

import org.openkilda.messaging.payload.SimpleMessage;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.AbstractTopology;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;

@Slf4j
public class SentBolt extends AbstractBolt {
    private final String version;
    int count;

    public SentBolt(String version) {
        this.version = version;
        this.count = Integer.parseInt(version);
    }

    @Override
    protected void init() {
        log.info("SENT BOLT started with version {}", version);
    }

    @Override
    protected void handleInput(Tuple input) throws PipelineException {
        log.info("Sent bolt message with version {}, counter {}", version, count);
        SimpleMessage message = new SimpleMessage(
                String.format("Message from sent bolt %s Version %d", version, count += 2));
        emit(input, Lists.newArrayList(message));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(AbstractTopology.fieldMessage);
    }
}
