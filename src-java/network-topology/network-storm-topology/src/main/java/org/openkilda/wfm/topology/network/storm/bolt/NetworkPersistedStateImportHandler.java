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

package org.openkilda.wfm.topology.network.storm.bolt;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.topology.network.model.facts.HistoryFacts;
import org.openkilda.wfm.topology.network.service.ISwitchPrepopulateCarrier;
import org.openkilda.wfm.topology.network.service.NetworkHistoryService;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.sw.command.SwitchHistoryCommand;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

@Slf4j
public class NetworkPersistedStateImportHandler extends AbstractBolt implements ISwitchPrepopulateCarrier {
    public static final String BOLT_ID = ComponentId.NETWORK_HISTORY.toString();

    public static final String FIELD_ID_DATAPATH = SpeakerRouter.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PAYLOAD = "switch-init";
    public static final String FIELD_ID_CONTEXT = AbstractBolt.FIELD_ID_CONTEXT;

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public static final String STREAM_ZOOKEEPER_ID = ZkStreams.ZK.toString();
    public static final Fields STREAM_ZOOKEEPER_FIELDS = new Fields(ZooKeeperBolt.FIELD_ID_STATE,
            ZooKeeperBolt.FIELD_ID_CONTEXT);

    private final PersistenceManager persistenceManager;

    private transient NetworkHistoryService service;

    public NetworkPersistedStateImportHandler(
            PersistenceManager persistenceManager, String lifeCycleEventSourceComponent) {
        super(lifeCycleEventSourceComponent);
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void handleInput(Tuple input) {
        unhandledInput(input);
    }

    @Override
    protected void activate() {
        if (!active) {
            // Every new START signal will cause history reading
            log.info("Allying history events");
            service.applyHistory();
        } else {
            log.info("Skip history events");
        }
    }

    @Override
    protected void init() {
        super.init();
        service = new NetworkHistoryService(this, persistenceManager);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        streamManager.declareStream(STREAM_ZOOKEEPER_ID, STREAM_ZOOKEEPER_FIELDS);
    }

    /**
     * Emit new history fact about switch.
     * @param historyFacts entity
     */
    public void switchAddWithHistory(HistoryFacts historyFacts) {
        SwitchHistoryCommand command = new SwitchHistoryCommand(historyFacts);
        SwitchId switchId = command.getDatapath();

        CommandContext context = getCommandContext().fork(switchId.toOtsdFormat());
        getOutput().emit(new Values(switchId, command, context));
    }
}
