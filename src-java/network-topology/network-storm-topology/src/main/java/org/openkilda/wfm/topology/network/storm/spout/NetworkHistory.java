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

package org.openkilda.wfm.topology.network.storm.spout;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.context.PersistenceContextRequired;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.network.model.facts.HistoryFacts;
import org.openkilda.wfm.topology.network.service.ISwitchPrepopulateCarrier;
import org.openkilda.wfm.topology.network.service.NetworkHistoryService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.sw.command.SwitchHistoryCommand;

import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

import java.util.Map;

public class NetworkHistory extends BaseRichSpout implements ISwitchPrepopulateCarrier {
    public static final String SPOUT_ID = ComponentId.NETWORK_HISTORY.toString();

    public static final String FIELD_ID_DATAPATH = SpeakerRouter.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PAYLOAD = "switch-init";
    public static final String FIELD_ID_CONTEXT = AbstractBolt.FIELD_ID_CONTEXT;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;

    private transient NetworkHistoryService service;
    private transient SpoutOutputCollector output;

    private final CommandContext rootContext = new CommandContext();

    private boolean workDone = false;

    public NetworkHistory(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    @Override
    @PersistenceContextRequired(requiresNew = true)
    public void nextTuple() {
        if (workDone) {
            org.apache.storm.utils.Utils.sleep(1L);
            return;
        }
        workDone = true;

        service.applyHistory();
    }

    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        output = collector;
        service = new NetworkHistoryService(this, persistenceManager);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
    }

    /**
     * Emit new history fact about switch.
     * @param historyFacts entity
     */
    public void switchAddWithHistory(HistoryFacts historyFacts) {
        SwitchHistoryCommand command = new SwitchHistoryCommand(historyFacts);
        SwitchId switchId = command.getDatapath();

        CommandContext context = rootContext.fork(switchId.toOtsdFormat());
        output.emit(new Values(switchId, command, context));
    }
}
