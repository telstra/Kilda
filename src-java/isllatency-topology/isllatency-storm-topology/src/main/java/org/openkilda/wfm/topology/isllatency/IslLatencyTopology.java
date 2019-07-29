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

package org.openkilda.wfm.topology.isllatency;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.isllatency.bolts.CacheBolt;
import org.openkilda.wfm.topology.isllatency.bolts.IslLatencyBolt;
import org.openkilda.wfm.topology.isllatency.bolts.IslStatsBolt;
import org.openkilda.wfm.topology.isllatency.bolts.IslStatusUpdateBolt;
import org.openkilda.wfm.topology.isllatency.bolts.OneWayLatencyManipulationBolt;
import org.openkilda.wfm.topology.isllatency.bolts.RouterBolt;
import org.openkilda.wfm.topology.isllatency.model.StreamType;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

public class IslLatencyTopology extends AbstractTopology<IslLatencyTopologyConfig> {
    public static final String ISL_STATUS_SPOUT_ID = "isl-status-spout";
    public static final String ISL_LATENCY_SPOUT_ID = "isl-latency-spout";

    public static final String ISL_LATENCY_OTSDB_BOLT_ID = "isl-latency-otsdb-bolt";
    public static final String ISL_LATENCY_BOLT_ID = "isl-latency-bolt";
    public static final String ISL_STATS_BOLT_ID = "isl-stats-bolt";
    public static final String ROUTER_BOLT_ID = "router-bolt";
    public static final String CACHE_BOLT_ID = "cache-bolt";
    public static final String ISL_STATUS_UPDATE_BOLT_ID = "isl-status-update-bolt";
    public static final String ONE_WAY_MANIPULATION_BOLT_ID = "one-way-manipulation-bolt";

    public static final String ISL_GROUPING_FIELD = "isl_group_field";
    public static final String SWITCH_KEY_FIELD = "switch_key";
    public static final String LATENCY_DATA_FIELD = "latency_data";
    public static final String ISL_STATUS_FIELD = "status_data";
    public static final String CACHE_DATA_FIELD = "cache_data";
    public static final Fields ISL_GROUPING_FIELDS = new Fields(ISL_GROUPING_FIELD);

    public IslLatencyTopology(LaunchEnvironment env) {
        super(env, IslLatencyTopologyConfig.class);
    }

    /**
     * Isl latency topology factory.
     */
    public StormTopology createTopology() {
        logger.info("Creating IslLatencyTopology - {}", topologyName);

        TopologyBuilder builder = new TopologyBuilder();

        createSpouts(builder);

        createIslStatusUpdateBolt(builder);

        PersistenceManager persistenceManager =
                PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);

        createCacheBolt(builder, persistenceManager);
        createLatencyBolt(builder, persistenceManager);

        createRouterBolt(builder);
        createStatsBolt(builder);
        createOneWayManipulationBolt(builder);

        createOpenTsdbBolt(builder);

        return builder.createTopology();
    }

    private void createOpenTsdbBolt(TopologyBuilder builder) {
        String openTsdbTopic = topologyConfig.getKafkaOtsdbTopic();
        KafkaBolt openTsdbBolt = createKafkaBolt(openTsdbTopic);
        builder.setBolt(ISL_LATENCY_OTSDB_BOLT_ID, openTsdbBolt, topologyConfig.getNewParallelism())
                .shuffleGrouping(ISL_STATS_BOLT_ID);
    }

    private void createLatencyBolt(TopologyBuilder builder, PersistenceManager persistenceManager) {
        IslLatencyBolt islLatencyBolt = new IslLatencyBolt(
                persistenceManager,
                topologyConfig.getLatencyUpdateInterval(),
                topologyConfig.getLatencyUpdateTimeRange());
        builder.setBolt(ISL_LATENCY_BOLT_ID, islLatencyBolt, topologyConfig.getNewParallelism())
                .fieldsGrouping(ONE_WAY_MANIPULATION_BOLT_ID, StreamType.LATENCY.toString(), ISL_GROUPING_FIELDS)
                .fieldsGrouping(CACHE_BOLT_ID, StreamType.LATENCY.toString(), ISL_GROUPING_FIELDS);
    }

    private void createStatsBolt(TopologyBuilder builder) {
        long latencyTimeout = (long) (topologyConfig.getDiscoveryIntervalMultiplier()
                * topologyConfig.getDiscoveryInterval());

        IslStatsBolt islStatsBolt = new IslStatsBolt(topologyConfig.getMetricPrefix(), latencyTimeout);
        builder.setBolt(ISL_STATS_BOLT_ID, islStatsBolt, topologyConfig.getNewParallelism())
                .fieldsGrouping(ONE_WAY_MANIPULATION_BOLT_ID, StreamType.LATENCY.toString(), ISL_GROUPING_FIELDS)
                .fieldsGrouping(CACHE_BOLT_ID, StreamType.LATENCY.toString(), ISL_GROUPING_FIELDS)
                .fieldsGrouping(ISL_STATUS_UPDATE_BOLT_ID, StreamType.ISL_STATUS.toString(), ISL_GROUPING_FIELDS);
    }

    private void createOneWayManipulationBolt(TopologyBuilder builder) {
        OneWayLatencyManipulationBolt oneWayLatencyManipulationBolt = new OneWayLatencyManipulationBolt();

        builder.setBolt(ONE_WAY_MANIPULATION_BOLT_ID, oneWayLatencyManipulationBolt, topologyConfig.getNewParallelism())
                .fieldsGrouping(ROUTER_BOLT_ID, StreamType.ONE_WAY_MANIPULATION.toString(), ISL_GROUPING_FIELDS);
    }


    private void createCacheBolt(TopologyBuilder builder, PersistenceManager persistenceManager) {
        CacheBolt cacheBolt = new CacheBolt(persistenceManager);
        builder.setBolt(CACHE_BOLT_ID, cacheBolt, topologyConfig.getNewParallelism())
                .allGrouping(ISL_STATUS_UPDATE_BOLT_ID, StreamType.ISL_STATUS.toString())
                .fieldsGrouping(ROUTER_BOLT_ID, StreamType.CACHE.toString(), new Fields(SWITCH_KEY_FIELD));
    }

    private void createIslStatusUpdateBolt(TopologyBuilder builder) {
        IslStatusUpdateBolt islStatusUpdateBolt = new IslStatusUpdateBolt();
        builder.setBolt(ISL_STATUS_UPDATE_BOLT_ID, islStatusUpdateBolt, topologyConfig.getNewParallelism())
                .shuffleGrouping(ISL_STATUS_SPOUT_ID);
    }

    private void createRouterBolt(TopologyBuilder builder) {
        RouterBolt routerBolt = new RouterBolt();
        builder.setBolt(ROUTER_BOLT_ID, routerBolt, topologyConfig.getNewParallelism())
                .shuffleGrouping(ISL_LATENCY_SPOUT_ID);
    }

    private void createSpouts(TopologyBuilder builder) {
        String topoIslLatencyTopic = topologyConfig.getKafkaTopoIslLatencyTopic();

        logger.debug("connecting to {} topic", topoIslLatencyTopic);
        builder.setSpout(ISL_LATENCY_SPOUT_ID, buildKafkaSpout(topoIslLatencyTopic, ISL_LATENCY_SPOUT_ID));
        builder.setSpout(ISL_STATUS_SPOUT_ID,
                buildKafkaSpout(topologyConfig.getKafkaNetworkIslStatusTopic(), ISL_STATUS_SPOUT_ID));
    }

    /**
     * Entry point for local run of the topology.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new IslLatencyTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
