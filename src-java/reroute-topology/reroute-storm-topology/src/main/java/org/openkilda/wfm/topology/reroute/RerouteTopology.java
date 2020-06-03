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

package org.openkilda.wfm.topology.reroute;

import static org.openkilda.wfm.topology.reroute.bolts.FlowRerouteQueueBolt.STREAM_NORTHBOUND_ID;
import static org.openkilda.wfm.topology.reroute.bolts.OperationQueueBolt.REROUTE_QUEUE_STREAM;
import static org.openkilda.wfm.topology.reroute.bolts.RerouteBolt.STREAM_MANUAL_REROUTE_REQUEST_ID;
import static org.openkilda.wfm.topology.reroute.bolts.RerouteBolt.STREAM_REROUTE_REQUEST_ID;
import static org.openkilda.wfm.topology.reroute.bolts.TimeWindowBolt.STREAM_TIME_WINDOW_EVENT_ID;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_KEY;
import static org.openkilda.wfm.topology.utils.KafkaRecordTranslator.FIELD_ID_PAYLOAD;

import org.openkilda.messaging.Message;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.share.hubandspoke.CoordinatorBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.reroute.bolts.FlowRerouteQueueBolt;
import org.openkilda.wfm.topology.reroute.bolts.OperationQueueBolt;
import org.openkilda.wfm.topology.reroute.bolts.RerouteBolt;
import org.openkilda.wfm.topology.reroute.bolts.TimeWindowBolt;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.concurrent.TimeUnit;

public class RerouteTopology extends AbstractTopology<RerouteTopologyConfig> {

    private static final String SPOUT_ID_REROUTE = "reroute-spout";

    private static final String BOLT_ID_KAFKA_FLOWHS = "kafka-flowhs-bolt";
    private static final String BOLT_ID_KAFKA_NB = "kafka-northbound-bolt";

    private static final String METRICS_BOLT_ID = "metrics-bolt";

    public static final Fields KAFKA_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD);

    public RerouteTopology(LaunchEnvironment env) {
        super(env, "reroute-topology", RerouteTopologyConfig.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StormTopology createTopology() {
        logger.info("Creating RerouteTopology - {}", topologyName);

        TopologyBuilder topologyBuilder = new TopologyBuilder();

        coordinator(topologyBuilder);

        declareKafkaSpout(topologyBuilder, topologyConfig.getKafkaTopoRerouteTopic(), SPOUT_ID_REROUTE);

        PersistenceManager persistenceManager = PersistenceProvider.getInstance()
                .getPersistenceManager(configurationProvider);

        rerouteBolt(topologyBuilder, persistenceManager);
        rerouteQueueBolt(topologyBuilder, persistenceManager);
        timeWindowBolt(topologyBuilder);

        operationQueue(topologyBuilder);

        KafkaBolt<String, Message> kafkaFlowHsBolt = buildKafkaBolt(topologyConfig.getKafkaFlowHsTopic());
        declareBolt(topologyBuilder, kafkaFlowHsBolt, BOLT_ID_KAFKA_FLOWHS)
                .shuffleGrouping(OperationQueueBolt.BOLT_ID);

        KafkaBolt<String, Message> kafkaNorthboundBolt = buildKafkaBolt(topologyConfig.getKafkaNorthboundTopic());
        declareBolt(topologyBuilder, kafkaNorthboundBolt, BOLT_ID_KAFKA_NB)
                .shuffleGrouping(FlowRerouteQueueBolt.BOLT_ID, STREAM_NORTHBOUND_ID);
        zkBolt(topologyBuilder);
        zkSpout(topologyBuilder);

        metrics(topologyBuilder);

        return topologyBuilder.createTopology();
    }

    private void zkSpout(TopologyBuilder topologyBuilder) {
        String zkString = getZookeeperConfig().getConnectString();
        ZooKeeperSpout zooKeeperSpout = new ZooKeeperSpout(getConfig().getBlueGreenMode(), getZkTopoName(), zkString);
        declareSpout(topologyBuilder, zooKeeperSpout, ZooKeeperSpout.SPOUT_ID);
    }

    private void zkBolt(TopologyBuilder topologyBuilder) {
        String zkString = getZookeeperConfig().getConnectString();
        ZooKeeperBolt zooKeeperBolt = new ZooKeeperBolt(getConfig().getBlueGreenMode(), getZkTopoName(), zkString);
        declareBolt(topologyBuilder, zooKeeperBolt, ZooKeeperBolt.BOLT_ID)
                .allGrouping(RerouteBolt.BOLT_ID, ZkStreams.ZK.toString())
                .allGrouping(OperationQueueBolt.BOLT_ID, ZkStreams.ZK.toString());
    }

    private void rerouteBolt(TopologyBuilder topologyBuilder,
                             PersistenceManager persistenceManager) {
        RerouteBolt rerouteBolt = new RerouteBolt(persistenceManager, ZooKeeperSpout.SPOUT_ID);
        declareBolt(topologyBuilder, rerouteBolt, RerouteBolt.BOLT_ID)
                .shuffleGrouping(SPOUT_ID_REROUTE)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);
    }

    private void rerouteQueueBolt(TopologyBuilder topologyBuilder,
                                  PersistenceManager persistenceManager) {
        int rerouteTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getRerouteTimeoutSeconds());
        FlowRerouteQueueBolt flowRerouteQueueBolt = new FlowRerouteQueueBolt(persistenceManager,
                topologyConfig.getDefaultFlowPriority(),
                topologyConfig.getMaxRetry(), rerouteTimeout);
        declareBolt(topologyBuilder, flowRerouteQueueBolt, FlowRerouteQueueBolt.BOLT_ID)
                .fieldsGrouping(RerouteBolt.BOLT_ID, STREAM_REROUTE_REQUEST_ID, new Fields(RerouteBolt.FLOW_ID_FIELD))
                .fieldsGrouping(RerouteBolt.BOLT_ID, STREAM_MANUAL_REROUTE_REQUEST_ID,
                        new Fields(RerouteBolt.FLOW_ID_FIELD))
                .fieldsGrouping(OperationQueueBolt.BOLT_ID, REROUTE_QUEUE_STREAM,
                        new Fields(OperationQueueBolt.FLOW_ID_FIELD))
                .allGrouping(TimeWindowBolt.BOLT_ID)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void timeWindowBolt(TopologyBuilder topologyBuilder) {
        TimeWindowBolt timeWindowBolt = new TimeWindowBolt(topologyConfig.getRerouteThrottlingMinDelay(),
                topologyConfig.getRerouteThrottlingMaxDelay());
        // Time window bolt should use parallelism 1 to provide synchronisation for all reroute queue bolts
        declareBolt(topologyBuilder, timeWindowBolt, TimeWindowBolt.BOLT_ID)
                .allGrouping(FlowRerouteQueueBolt.BOLT_ID, STREAM_TIME_WINDOW_EVENT_ID)
                .allGrouping(CoordinatorSpout.ID);
    }

    private void coordinator(TopologyBuilder topologyBuilder) {
        declareSpout(topologyBuilder, new CoordinatorSpout(), CoordinatorSpout.ID);
        declareBolt(topologyBuilder, new CoordinatorBolt(), CoordinatorBolt.ID)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(FlowRerouteQueueBolt.BOLT_ID, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(OperationQueueBolt.BOLT_ID, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY);
    }

    private void operationQueue(TopologyBuilder topologyBuilder) {
        OperationQueueBolt operationQueueBolt =
                new OperationQueueBolt((int) TimeUnit.SECONDS.toMillis(topologyConfig.getRerouteTimeoutSeconds()),
                        ZooKeeperSpout.SPOUT_ID);
        declareBolt(topologyBuilder, operationQueueBolt, OperationQueueBolt.BOLT_ID)
                .fieldsGrouping(RerouteBolt.BOLT_ID, RerouteBolt.STREAM_OPERATION_QUEUE_ID,
                        new Fields(RerouteBolt.FLOW_ID_FIELD))
                .fieldsGrouping(FlowRerouteQueueBolt.BOLT_ID, FlowRerouteQueueBolt.STREAM_OPERATION_QUEUE_ID,
                        new Fields(FlowRerouteQueueBolt.FLOW_ID_FIELD))
                .directGrouping(CoordinatorBolt.ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);
    }

    @Override
    protected String getZkTopoName() {
        return "reroute";
    }

    private void metrics(TopologyBuilder topologyBuilder) {
        String openTsdbTopic = topologyConfig.getKafkaTopics().getOtsdbTopic();
        KafkaBolt kafkaBolt = createKafkaBolt(openTsdbTopic);
        declareBolt(topologyBuilder, kafkaBolt, METRICS_BOLT_ID)
                .shuffleGrouping(RerouteBolt.BOLT_ID, RerouteBolt.STREAM_TO_METRICS_BOLT);
    }

    /**
     * Launches and sets up the workflow manager environment.
     *
     * @param args the command-line arguments.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            new RerouteTopology(env).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
