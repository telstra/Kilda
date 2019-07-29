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

package org.openkilda.wfm.topology.reroute;

import static org.openkilda.wfm.topology.reroute.bolts.FlowRerouteQueueBolt.STREAM_NORTHBOUND_ID;
import static org.openkilda.wfm.topology.reroute.bolts.RerouteBolt.STREAM_MANUAL_REROUTE_REQUEST_ID;
import static org.openkilda.wfm.topology.reroute.bolts.RerouteBolt.STREAM_REROUTE_REQUEST_ID;
import static org.openkilda.wfm.topology.reroute.bolts.RerouteBolt.STREAM_REROUTE_RESULT_ID;
import static org.openkilda.wfm.topology.reroute.bolts.RerouteBolt.STREAM_SWAP_ID;
import static org.openkilda.wfm.topology.reroute.bolts.TimeWindowBolt.STREAM_TIME_WINDOW_EVENT_ID;

import org.openkilda.messaging.Message;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.share.hubandspoke.CoordinatorBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.reroute.bolts.FlowRerouteQueueBolt;
import org.openkilda.wfm.topology.reroute.bolts.RerouteBolt;
import org.openkilda.wfm.topology.reroute.bolts.TimeWindowBolt;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.concurrent.TimeUnit;

public class RerouteTopology extends AbstractTopology<RerouteTopologyConfig> {

    private static final String SPOUT_ID_REROUTE = "reroute-spout";

    private static final String BOLT_ID_KAFKA_FLOW = "kafka-flow-bolt";
    private static final String BOLT_ID_KAFKA_FLOWHS = "kafka-flowhs-bolt";
    private static final String BOLT_ID_KAFKA_NB = "kafka-northbound-bolt";

    public static final Fields KAFKA_FIELDS =
            new Fields(MessageKafkaTranslator.KEY_FIELD, MessageKafkaTranslator.FIELD_ID_PAYLOAD);

    public RerouteTopology(LaunchEnvironment env) {
        super(env, RerouteTopologyConfig.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StormTopology createTopology() {
        logger.info("Creating RerouteTopology - {}", topologyName);

        TopologyBuilder topologyBuilder = new TopologyBuilder();

        final Integer parallelism = topologyConfig.getNewParallelism();

        coordinator(topologyBuilder, parallelism);

        KafkaSpout<String, Message> kafkaSpout =
                buildKafkaSpout(topologyConfig.getKafkaTopoRerouteTopic(), SPOUT_ID_REROUTE);
        topologyBuilder.setSpout(SPOUT_ID_REROUTE, kafkaSpout, parallelism);

        PersistenceManager persistenceManager = PersistenceProvider.getInstance()
                .getPersistenceManager(configurationProvider);

        rerouteBolt(topologyBuilder, parallelism, persistenceManager);
        rerouteQueueBolt(topologyBuilder, parallelism, persistenceManager);
        timeWindowBolt(topologyBuilder);

        KafkaBolt<String, Message> kafkaFlowBolt = buildKafkaBolt(topologyConfig.getKafkaFlowTopic());
        topologyBuilder.setBolt(BOLT_ID_KAFKA_FLOW, kafkaFlowBolt, parallelism)
                .shuffleGrouping(RerouteBolt.BOLT_ID, STREAM_SWAP_ID);

        KafkaBolt<String, Message> kafkaFlowHsBolt = buildKafkaBolt(topologyConfig.getKafkaFlowHsTopic());
        topologyBuilder.setBolt(BOLT_ID_KAFKA_FLOWHS, kafkaFlowHsBolt, parallelism)
                .shuffleGrouping(FlowRerouteQueueBolt.BOLT_ID, FlowRerouteQueueBolt.STREAM_FLOWHS_ID);

        KafkaBolt<String, Message> kafkaNorthboundBolt = buildKafkaBolt(topologyConfig.getKafkaNorthboundTopic());
        topologyBuilder.setBolt(BOLT_ID_KAFKA_NB, kafkaNorthboundBolt, parallelism)
                .shuffleGrouping(FlowRerouteQueueBolt.BOLT_ID, STREAM_NORTHBOUND_ID);

        return topologyBuilder.createTopology();
    }

    private void rerouteBolt(TopologyBuilder topologyBuilder, int parallelism,
                             PersistenceManager persistenceManager) {
        RerouteBolt rerouteBolt = new RerouteBolt(persistenceManager);
        topologyBuilder.setBolt(RerouteBolt.BOLT_ID, rerouteBolt, parallelism)
                .shuffleGrouping(SPOUT_ID_REROUTE);
    }

    private void rerouteQueueBolt(TopologyBuilder topologyBuilder, int parallelism,
                                  PersistenceManager persistenceManager) {
        int rerouteTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getRerouteTimeoutSeconds());
        FlowRerouteQueueBolt flowRerouteQueueBolt = new FlowRerouteQueueBolt(persistenceManager,
                topologyConfig.getDefaultFlowPriority(),
                topologyConfig.getMaxRetry(), rerouteTimeout);
        topologyBuilder.setBolt(FlowRerouteQueueBolt.BOLT_ID, flowRerouteQueueBolt, parallelism)
                .fieldsGrouping(RerouteBolt.BOLT_ID, STREAM_REROUTE_REQUEST_ID, new Fields(RerouteBolt.FLOW_ID_FIELD))
                .fieldsGrouping(RerouteBolt.BOLT_ID, STREAM_MANUAL_REROUTE_REQUEST_ID,
                        new Fields(RerouteBolt.FLOW_ID_FIELD))
                .fieldsGrouping(RerouteBolt.BOLT_ID, STREAM_REROUTE_RESULT_ID, new Fields(RerouteBolt.FLOW_ID_FIELD))
                .allGrouping(TimeWindowBolt.BOLT_ID)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void timeWindowBolt(TopologyBuilder topologyBuilder) {
        TimeWindowBolt timeWindowBolt = new TimeWindowBolt(topologyConfig.getRerouteThrottlingMinDelay(),
                topologyConfig.getRerouteThrottlingMaxDelay());
        // Time window bolt should use parallelism 1 to provide synchronisation for all reroute queue bolts
        topologyBuilder.setBolt(TimeWindowBolt.BOLT_ID, timeWindowBolt, 1)
                .allGrouping(FlowRerouteQueueBolt.BOLT_ID, STREAM_TIME_WINDOW_EVENT_ID)
                .allGrouping(CoordinatorSpout.ID);
    }

    private void coordinator(TopologyBuilder topologyBuilder, int parallelism) {
        topologyBuilder.setSpout(CoordinatorSpout.ID, new CoordinatorSpout());
        topologyBuilder.setBolt(CoordinatorBolt.ID, new CoordinatorBolt(), parallelism)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(FlowRerouteQueueBolt.BOLT_ID, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY);
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
