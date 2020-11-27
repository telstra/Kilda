/* Copyright 2017 Telstra Open Source
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

package org.openkilda.wfm.topology.stats;

import static org.openkilda.messaging.Utils.COMMON_COMPONENT_NAME;
import static org.openkilda.messaging.Utils.COMMON_COMPONENT_RUN_ID;
import static org.openkilda.wfm.AbstractBolt.FIELD_ID_CONTEXT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.FLOW_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.METER_CFG_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.METER_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.PACKET_IN_OUT_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.PORT_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.SERVER42_STATS_FLOW_RTT_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.SERVER42_STATS_FLOW_RTT_SPOUT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.STATS_CACHE_BOLT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.STATS_CACHE_FILTER_BOLT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.STATS_GRPC_SPEAKER_BOLT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.STATS_KILDA_SPEAKER_BOLT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.STATS_KILDA_SPEAKER_SPOUT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.STATS_REQUESTER_BOLT;
import static org.openkilda.wfm.topology.stats.StatsComponentType.SYSTEM_RULE_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.TABLE_STATS_METRIC_GEN;
import static org.openkilda.wfm.topology.stats.StatsComponentType.TICK_BOLT;
import static org.openkilda.wfm.topology.stats.StatsStreamType.CACHE_UPDATE;
import static org.openkilda.wfm.topology.stats.StatsStreamType.GRPC_REQUEST;
import static org.openkilda.wfm.topology.stats.StatsStreamType.STATS_REQUEST;
import static org.openkilda.wfm.topology.stats.bolts.CacheBolt.statsWithCacheFields;

import org.openkilda.config.KafkaTopicsConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.stats.bolts.CacheBolt;
import org.openkilda.wfm.topology.stats.bolts.CacheFilterBolt;
import org.openkilda.wfm.topology.stats.bolts.SpeakerBolt;
import org.openkilda.wfm.topology.stats.bolts.SpeakerRequestDecoderBolt;
import org.openkilda.wfm.topology.stats.bolts.StatsRequesterBolt;
import org.openkilda.wfm.topology.stats.bolts.TickBolt;
import org.openkilda.wfm.topology.stats.metrics.FlowMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.FlowRttMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.MeterConfigMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.MeterStatsMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.PacketInOutMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.PortMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.SystemRuleMetricGenBolt;
import org.openkilda.wfm.topology.stats.metrics.TableStatsMetricGenBolt;
import org.openkilda.wfm.topology.utils.JsonKafkaTranslator;

import com.google.common.collect.ImmutableList;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

public class StatsTopology extends AbstractTopology<StatsTopologyConfig> {
    public static final String STATS_FIELD = "stats";
    public static final Fields statsFields = new Fields(STATS_FIELD, FIELD_ID_CONTEXT);

    public StatsTopology(LaunchEnvironment env) {
        super(env, StatsTopologyConfig.class);
    }

    /**
     * main.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new StatsTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }

    @Override
    public StormTopology createTopology() {
        logger.info("Creating StatsTopology - {}", topologyName);

        final Integer parallelism = topologyConfig.getParallelism();
        TopologyBuilder builder = new TopologyBuilder();


        final String kafkaSpoutId = StatsComponentType.STATS_OFS_KAFKA_SPOUT.toString();
        KafkaSpout kafkaSpout = buildKafkaSpout(topologyConfig.getKafkaStatsTopic(), kafkaSpoutId);
        builder.setSpout(kafkaSpoutId, kafkaSpout, parallelism);

        SpeakerBolt speakerBolt = new SpeakerBolt();
        final String statsOfsBolt = StatsComponentType.STATS_OFS_BOLT.toString();
        builder.setBolt(statsOfsBolt, speakerBolt, parallelism)
                .shuffleGrouping(kafkaSpoutId);

        inputSpeakerRequests(builder, parallelism);
        cacheSyncFilter(builder, parallelism);

        // Cache bolt get data from the database on start
        PersistenceManager persistenceManager =
                PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);
        builder.setBolt(STATS_CACHE_BOLT.name(), new CacheBolt(persistenceManager), parallelism)
                .allGrouping(STATS_CACHE_FILTER_BOLT.name(), CACHE_UPDATE.name())
                .fieldsGrouping(statsOfsBolt, StatsStreamType.CACHE_DATA.toString(), statsFields);

        builder.setBolt(PORT_STATS_METRIC_GEN.name(),
                new PortMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(statsOfsBolt, StatsStreamType.PORT_STATS.toString(), fieldMessage);
        builder.setBolt(METER_CFG_STATS_METRIC_GEN.name(),
                new MeterConfigMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(statsOfsBolt, StatsStreamType.METER_CONFIG_STATS.toString(), fieldMessage);
        builder.setBolt(SYSTEM_RULE_STATS_METRIC_GEN.name(),
                new SystemRuleMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(statsOfsBolt, StatsStreamType.SYSTEM_RULE_STATS.toString(), statsFields);
        builder.setBolt(TABLE_STATS_METRIC_GEN.name(),
                new TableStatsMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(statsOfsBolt, StatsStreamType.TABLE_STATS.toString(), statsFields);
        builder.setBolt(PACKET_IN_OUT_STATS_METRIC_GEN.name(),
                new PacketInOutMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(statsOfsBolt, StatsStreamType.PACKET_IN_OUT_STATS.toString(), statsFields);

        logger.debug("starting flow_stats_metric_gen");
        builder.setBolt(FLOW_STATS_METRIC_GEN.name(),
                new FlowMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(STATS_CACHE_BOLT.name(), StatsStreamType.FLOW_STATS.toString(), statsWithCacheFields);
        builder.setBolt(METER_STATS_METRIC_GEN.name(),
                new MeterStatsMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .fieldsGrouping(STATS_CACHE_BOLT.name(), StatsStreamType.METER_STATS.toString(), statsWithCacheFields);

        builder.setBolt(TICK_BOLT.name(), new TickBolt(topologyConfig.getStatisticsRequestInterval()));

        builder.setBolt(STATS_REQUESTER_BOLT.name(), new StatsRequesterBolt(persistenceManager), parallelism)
                .shuffleGrouping(TICK_BOLT.name());

        builder.setBolt(STATS_KILDA_SPEAKER_BOLT.name(), buildKafkaBolt(topologyConfig.getSpeakerTopic()))
                .shuffleGrouping(STATS_REQUESTER_BOLT.name(), STATS_REQUEST.name());
        builder.setBolt(STATS_GRPC_SPEAKER_BOLT.name(), buildKafkaBolt(topologyConfig.getGrpcSpeakerTopic()))
                .shuffleGrouping(STATS_REQUESTER_BOLT.name(), GRPC_REQUEST.name());

        KafkaSpout server42StatsFlowRttSpout = buildKafkaSpout(topologyConfig.getServer42StatsFlowRttTopic(),
                SERVER42_STATS_FLOW_RTT_SPOUT.name());
        builder.setSpout(SERVER42_STATS_FLOW_RTT_SPOUT.name(), server42StatsFlowRttSpout, parallelism);

        builder.setBolt(SERVER42_STATS_FLOW_RTT_METRIC_GEN.name(),
                new FlowRttMetricGenBolt(topologyConfig.getMetricPrefix()), parallelism)
                .shuffleGrouping(SERVER42_STATS_FLOW_RTT_SPOUT.name());

        String openTsdbTopic = topologyConfig.getKafkaOtsdbTopic();
        builder.setBolt("stats-opentsdb", createKafkaBolt(openTsdbTopic))
                .shuffleGrouping(PORT_STATS_METRIC_GEN.name())
                .shuffleGrouping(METER_STATS_METRIC_GEN.name())
                .shuffleGrouping(METER_CFG_STATS_METRIC_GEN.name())
                .shuffleGrouping(FLOW_STATS_METRIC_GEN.name())
                .shuffleGrouping(TABLE_STATS_METRIC_GEN.name())
                .shuffleGrouping(SYSTEM_RULE_STATS_METRIC_GEN.name())
                .shuffleGrouping(PACKET_IN_OUT_STATS_METRIC_GEN.name())
                .shuffleGrouping(SERVER42_STATS_FLOW_RTT_METRIC_GEN.name());

        return builder.createTopology();
    }

    /**
     * Capture and decode speaker requests (kilda.speaker.flow).
     */
    private void inputSpeakerRequests(TopologyBuilder topology, int scaleFactor) {
        String id = STATS_KILDA_SPEAKER_SPOUT.name();
        KafkaTopicsConfig topics = topologyConfig.getKafkaTopics();
        KafkaSpoutConfig<String, String> config = makeKafkaSpoutConfig(
                ImmutableList.of(topics.getSpeakerFlowHsTopic()), id, StringDeserializer.class, COMMON_COMPONENT_NAME,
                COMMON_COMPONENT_RUN_ID)
                .setRecordTranslator(new JsonKafkaTranslator())
                .build();
        topology.setSpout(id, new KafkaSpout<>(config), scaleFactor);

        SpeakerRequestDecoderBolt decoder = new SpeakerRequestDecoderBolt();
        topology.setBolt(SpeakerRequestDecoderBolt.BOLT_ID, decoder)
                .shuffleGrouping(id);
    }

    /**
     * CacheFilterBolt catch data from kilda.speaker spout and tried to find InstallEgressFlow
     * or InstallOneSwitchFlow and throw tuple to CacheBolt.
     */
    private void cacheSyncFilter(TopologyBuilder topology, int scaleFactor) {
        topology.setBolt(STATS_CACHE_FILTER_BOLT.name(), new CacheFilterBolt(), scaleFactor)
                .shuffleGrouping(SpeakerRequestDecoderBolt.BOLT_ID, SpeakerRequestDecoderBolt.STREAM_GENERIC_ID)
                .shuffleGrouping(SpeakerRequestDecoderBolt.BOLT_ID, SpeakerRequestDecoderBolt.STREAM_HUB_AND_SPOKE_ID);
    }
}
