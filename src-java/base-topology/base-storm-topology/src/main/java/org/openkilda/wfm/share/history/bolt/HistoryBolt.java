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

package org.openkilda.wfm.share.history.bolt;

import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.history.service.HistoryService;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;

public class HistoryBolt extends AbstractBolt {
    private final PersistenceManager persistenceManager;
    private transient HistoryService historyService;

    public HistoryBolt(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
        this.hsBoltName = "history_bolt";
    }

    @Override
    protected void init() {
        historyService = new HistoryService(persistenceManager);
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        Object payload = input.getValueByField(FIELD_ID_PAYLOAD);
        if (payload instanceof FlowHistoryHolder) {
            if (input.getSourceComponent().equals("flow.reroute.hub.bolt")) {
                log.warn("HSTIME reroute spend in queue: Hub -> History "
                        + (System.currentTimeMillis() - ((FlowHistoryHolder) payload).getCreatingTime()));
            } else {
                log.warn("HSTIME spend in queue: Hub -> History "
                        + (System.currentTimeMillis() - ((FlowHistoryHolder) payload).getCreatingTime()));
            }
            historyService.store((FlowHistoryHolder) payload);
        } else {
            log.error("Skip undefined payload: {}", payload);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {

    }
}
