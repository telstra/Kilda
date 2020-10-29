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

package org.openkilda.wfm.topology.versioning;

import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.kafka.MessageSerializer;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.versioning.bolts.LogBolt;
import org.openkilda.wfm.topology.versioning.bolts.SentBolt;
import org.openkilda.wfm.topology.versioning.bolts.TickBolt;
import org.openkilda.wfm.topology.versioning.config.BaseVersionTopologyConfig;

import com.google.common.collect.Lists;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.topology.TopologyBuilder;

public class VersioningTopology extends AbstractTopology<BaseVersionTopologyConfig> {
    public static final String MAIN_SPOUT_ID = "main-spout";

    public static final String LOG_BOLT_ID = "log-bolt";
    public static final String TICK_BOLT_ID = "tick-bolt";
    public static final String SENT_BOLT_ID = "sent-bolt";
    public static final String OUT_BOLT_ID = "OUT-bolt";
    private final String version;

    public VersioningTopology(LaunchEnvironment env, String version) {
        super(env, BaseVersionTopologyConfig.class);
        this.version = version;
    }

    /**
     * Isl latency topology factory.
     */
    public StormTopology createTopology() {
        logger.info("Creating IslLatencyTopology - {}", topologyName);

        TopologyBuilder builder = new TopologyBuilder();

        createSpouts(builder);

        createLogBolt(builder);
        createTickBolt(builder);
        createSentBolt(builder);
        createKafkaBolt(builder);


        return builder.createTopology();
    }


    private void createLogBolt(TopologyBuilder builder) {
        LogBolt logBolt = new LogBolt(version);
        builder.setBolt(LOG_BOLT_ID, logBolt, 1)
                .shuffleGrouping(MAIN_SPOUT_ID);
    }

    private void createTickBolt(TopologyBuilder builder) {
        TickBolt tickBolt = new TickBolt(1);
        builder.setBolt(TICK_BOLT_ID, tickBolt, 1);
    }

    private void createSentBolt(TopologyBuilder builder) {
        SentBolt sentBolt = new SentBolt(version);
        builder.setBolt(SENT_BOLT_ID, sentBolt, 1)
            .shuffleGrouping(TICK_BOLT_ID);
    }

    private void createKafkaBolt(TopologyBuilder builder) {
        builder.setBolt(OUT_BOLT_ID,
                makeKafkaBolt(MessageSerializer.class, version)
                        .withTopicSelector(topologyConfig.getOutTopic()), 1)
                .shuffleGrouping(SENT_BOLT_ID);
    }

    private void createSpouts(TopologyBuilder builder) {
        builder.setSpout(MAIN_SPOUT_ID, buildKafkaSpout(
                Lists.newArrayList(topologyConfig.getMainTopic()), MAIN_SPOUT_ID, version));
    }

    /**
     * Entry point for local run of the topology.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new VersioningTopology(env, "1")).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
