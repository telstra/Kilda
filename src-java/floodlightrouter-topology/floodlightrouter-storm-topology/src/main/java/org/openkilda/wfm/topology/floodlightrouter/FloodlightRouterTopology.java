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

package org.openkilda.wfm.topology.floodlightrouter;

import org.openkilda.config.KafkaTopicsConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.floodlightrouter.bolts.BroadcastRequestBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.DiscoReplyBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.DiscoveryBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.ReplyBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.RequestBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.SpeakerRequestBolt;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.TopologyBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Floodlight topology.
 */
public class FloodlightRouterTopology extends AbstractTopology<FloodlightRouterTopologyConfig> {
    private final PersistenceManager persistenceManager;

    public FloodlightRouterTopology(LaunchEnvironment env) {
        super(env, FloodlightRouterTopologyConfig.class);

        persistenceManager = PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);
    }

    private void createKildaFlowSpout(TopologyBuilder builder, int parallelism, List<String> kildaFlowTopics) {
        KafkaSpout kildaFlowSpout = buildKafkaSpout(kildaFlowTopics,
                ComponentType.KILDA_FLOW_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_FLOW_KAFKA_SPOUT, kildaFlowSpout, parallelism);
    }

    private void createKildaFlowKafkaBolt(TopologyBuilder builder, int parallelism, KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaFlowKafkaBolt = buildKafkaBolt(topicsConfig.getFlowTopic());
        builder.setBolt(ComponentType.KILDA_FLOW_KAFKA_BOLT, kildaFlowKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_FLOW_REPLY_BOLT, Stream.KILDA_FLOW);
    }

    private void createKildaFlowHsSpout(TopologyBuilder builder, int parallelism, List<String> kildaFlowTopics) {
        KafkaSpout kildaFlowHsSpout = buildKafkaSpoutForAbstractMessage(kildaFlowTopics,
                ComponentType.KILDA_FLOW_HS_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_FLOW_HS_KAFKA_SPOUT, kildaFlowHsSpout, parallelism);
    }

    private void createKildaFlowHsKafkaBolt(TopologyBuilder builder, int parallelism, KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaFlowHsKafkaBolt = buildKafkaBoltWithAbstractMessageSupport(topicsConfig.getFlowHsSpeakerTopic());
        builder.setBolt(ComponentType.KILDA_FLOW_HS_KAFKA_BOLT, kildaFlowHsKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_FLOW_HS_REPLY_BOLT, Stream.KILDA_HS_FLOW);
    }

    private void createKildaFlowReplyStream(TopologyBuilder builder, int spoutParallelism,
                                            int parallelism, KafkaTopicsConfig topicsConfig,
                                            List<String> kildaFlowTopics) {
        createKildaFlowSpout(builder, spoutParallelism, kildaFlowTopics);
        createKildaFlowKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_FLOW);
        builder.setBolt(ComponentType.KILDA_FLOW_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_FLOW_KAFKA_SPOUT);

    }

    private void createKildaFlowHsReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                              KafkaTopicsConfig topicsConfig, List<String> kildaFlowHsTopics) {
        createKildaFlowHsSpout(builder, spoutParallelism, kildaFlowHsTopics);
        createKildaFlowHsKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_HS_FLOW);
        builder.setBolt(ComponentType.KILDA_FLOW_HS_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_FLOW_HS_KAFKA_SPOUT);

    }

    private void createKildaPingSpout(TopologyBuilder builder, int parallelism, List<String> kildaPingTopics) {
        KafkaSpout kildaPingSpout = buildKafkaSpout(kildaPingTopics,
                ComponentType.KILDA_PING_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_PING_KAFKA_SPOUT, kildaPingSpout, parallelism);
    }

    private void createKildaPingKafkaBolt(TopologyBuilder builder, int parallelism, KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaPingKafkaBolt = buildKafkaBolt(topicsConfig.getPingTopic());
        builder.setBolt(ComponentType.KILDA_PING_KAFKA_BOLT, kildaPingKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_PING_REPLY_BOLT, Stream.KILDA_PING);
    }


    private void createKildaPingReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                            KafkaTopicsConfig topicsConfig,
                                            List<String> kildaPingTopics) {
        createKildaPingSpout(builder, spoutParallelism, kildaPingTopics);
        createKildaPingKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_PING);
        builder.setBolt(ComponentType.KILDA_PING_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_PING_KAFKA_SPOUT);

    }

    private void createKildaStatsSpout(TopologyBuilder builder, int parallelism, List<String> kildaStatsTopics) {
        KafkaSpout kildaStatsSpout = buildKafkaSpout(kildaStatsTopics,
                ComponentType.KILDA_STATS_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_STATS_KAFKA_SPOUT, kildaStatsSpout, parallelism);
    }

    private void createKildaStatsKafkaBolt(TopologyBuilder builder, int parallelism, KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaStatsKafkaBolt = buildKafkaBolt(topicsConfig.getStatsTopic());
        builder.setBolt(ComponentType.KILDA_STATS_KAFKA_BOLT, kildaStatsKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_STATS_REPLY_BOLT, Stream.KILDA_STATS);
    }


    private void createKildaStatsReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                             KafkaTopicsConfig topicsConfig,
                                            List<String> kildaStatsTopics) {
        createKildaStatsSpout(builder, spoutParallelism, kildaStatsTopics);
        createKildaStatsKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_STATS);
        builder.setBolt(ComponentType.KILDA_STATS_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_STATS_KAFKA_SPOUT);

    }

    private void createKildaIslLatencySpout(TopologyBuilder builder, int parallelism, List<String> kildaStatsTopics) {
        KafkaSpout kildaStatsSpout = buildKafkaSpout(kildaStatsTopics,
                ComponentType.KILDA_ISL_LATENCY_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_ISL_LATENCY_KAFKA_SPOUT, kildaStatsSpout, parallelism);
    }

    private void createKildaIslLatencyKafkaBolt(TopologyBuilder builder, int parallelism,
                                                KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaStatsKafkaBolt = buildKafkaBolt(topicsConfig.getTopoIslLatencyTopic());
        builder.setBolt(ComponentType.KILDA_ISL_LATENCY_KAFKA_BOLT, kildaStatsKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_ISL_LATENCY_REPLY_BOLT, Stream.KILDA_ISL_LATENCY);
    }

    private void createKildaIslLatencyReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                                  KafkaTopicsConfig topicsConfig, List<String> kildaIslLatencyTopics) {
        createKildaIslLatencySpout(builder, spoutParallelism, kildaIslLatencyTopics);
        createKildaIslLatencyKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_ISL_LATENCY);
        builder.setBolt(ComponentType.KILDA_ISL_LATENCY_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_ISL_LATENCY_KAFKA_SPOUT);
    }

    private void createKildaConnectedDevicesSpout(TopologyBuilder builder, int parallelism, List<String> topics) {
        KafkaSpout spout = buildKafkaSpout(topics, ComponentType.KILDA_CONNECTED_DEVICES_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_CONNECTED_DEVICES_KAFKA_SPOUT, spout, parallelism);
    }

    private void createKildaConnectedDevicesKafkaBolt(TopologyBuilder builder, int parallelism,
                                                      KafkaTopicsConfig topicsConfig) {
        KafkaBolt kafkaBolt = buildKafkaBolt(topicsConfig.getTopoConnectedDevicesTopic());
        builder.setBolt(ComponentType.KILDA_CONNECTED_DEVICES_KAFKA_BOLT, kafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_CONNECTED_DEVICES_REPLY_BOLT, Stream.KILDA_CONNECTED_DEVICES);
    }

    private void createKildaConnectedDevicesReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                                        KafkaTopicsConfig topicsConfig, List<String> topics) {
        createKildaConnectedDevicesSpout(builder, spoutParallelism, topics);
        createKildaConnectedDevicesKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_CONNECTED_DEVICES);
        builder.setBolt(ComponentType.KILDA_CONNECTED_DEVICES_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_CONNECTED_DEVICES_KAFKA_SPOUT);
    }

    private void createKildaSwitchManagerSpout(TopologyBuilder builder, int parallelism,
                                               List<String> kildaSwitchManagerTopics) {
        KafkaSpout kildaSwitchManagerSpout = buildKafkaSpout(kildaSwitchManagerTopics,
                ComponentType.KILDA_SWITCH_MANAGER_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_SWITCH_MANAGER_KAFKA_SPOUT, kildaSwitchManagerSpout, parallelism);
    }

    private void createKildaSwitchManagerKafkaBolt(TopologyBuilder builder, int parallelism,
                                                   KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaSwitchManagerKafkaBolt = buildKafkaBolt(topicsConfig.getTopoSwitchManagerTopic());
        builder.setBolt(ComponentType.KILDA_SWITCH_MANAGER_KAFKA_BOLT, kildaSwitchManagerKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_REQUEST_BOLT, Stream.KILDA_SWITCH_MANAGER)
                .shuffleGrouping(ComponentType.KILDA_SWITCH_MANAGER_REPLY_BOLT, Stream.KILDA_SWITCH_MANAGER);
    }


    private void createKildaSwitchManagerReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                                     KafkaTopicsConfig topicsConfig,
                                                     List<String> kildaSwitchManagerTopics) {
        createKildaSwitchManagerSpout(builder, spoutParallelism, kildaSwitchManagerTopics);
        createKildaSwitchManagerKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.KILDA_SWITCH_MANAGER);
        builder.setBolt(ComponentType.KILDA_SWITCH_MANAGER_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_SWITCH_MANAGER_KAFKA_SPOUT);
    }

    private void createKildaNorthboundSpout(TopologyBuilder builder, int parallelism,
                                               List<String> kildaNorthboundTopics) {
        KafkaSpout kildaNorthboundSpout = buildKafkaSpout(kildaNorthboundTopics,
                ComponentType.NORTHBOUND_REPLY_KAFKA_SPOUT);
        builder.setSpout(ComponentType.NORTHBOUND_REPLY_KAFKA_SPOUT, kildaNorthboundSpout, parallelism);
    }

    private void createKildaNorthboundKafkaBolt(TopologyBuilder builder, int parallelism,
                                                   KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaNorthboundKafkaBolt = buildKafkaBolt(topicsConfig.getNorthboundTopic());
        builder.setBolt(ComponentType.NORTHBOUND_REPLY_KAFKA_BOLT, kildaNorthboundKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_REQUEST_BOLT, Stream.NORTHBOUND_REPLY)
                .shuffleGrouping(ComponentType.NORTHBOUND_REPLY_BOLT, Stream.NORTHBOUND_REPLY);
    }


    private void createKildaNorthboundReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                                     KafkaTopicsConfig topicsConfig,
                                                     List<String> kildaNorthboundTopics) {
        createKildaNorthboundSpout(builder, spoutParallelism, kildaNorthboundTopics);
        createKildaNorthboundKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.NORTHBOUND_REPLY);
        builder.setBolt(ComponentType.NORTHBOUND_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.NORTHBOUND_REPLY_KAFKA_SPOUT);
    }

    private void createKildaNbWorkerSpout(TopologyBuilder builder, int parallelism,
                                            List<String> kildaNbWorkerTopics) {
        KafkaSpout kildaNbWorkerSpout = buildKafkaSpout(kildaNbWorkerTopics,
                ComponentType.KILDA_NB_WORKER_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_NB_WORKER_KAFKA_SPOUT, kildaNbWorkerSpout, parallelism);
    }

    private void createKildaNbWorkerKafkaBolt(TopologyBuilder builder, int parallelism,
                                                KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaNbWorkerKafkaBolt = buildKafkaBolt(topicsConfig.getTopoNbTopic());
        builder.setBolt(ComponentType.KILDA_NB_WORKER_KAFKA_BOLT, kildaNbWorkerKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_NB_WORKER_REPLY_BOLT, Stream.NB_WORKER);
    }


    private void createKildaNbWorkerReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                                  KafkaTopicsConfig topicsConfig,
                                                  List<String> kildaNbWorkerTopics) {
        createKildaNbWorkerSpout(builder, spoutParallelism, kildaNbWorkerTopics);
        createKildaNbWorkerKafkaBolt(builder, parallelism, topicsConfig);

        ReplyBolt replyBolt = new ReplyBolt(Stream.NB_WORKER);
        builder.setBolt(ComponentType.KILDA_NB_WORKER_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_NB_WORKER_KAFKA_SPOUT);
    }



    private void createSpeakerFlowRequestStream(TopologyBuilder builder, int parallelism,
                                                KafkaTopicsConfig topicsConfig) {
        createSpeakerFlowRequestSpoutAndKafkaBolt(builder, parallelism, topicsConfig);
        createSpeakerFlowHsRequestSpoutAndKafkaBolt(builder, parallelism, topicsConfig);

        RequestBolt speakerFlowRequestBolt = new RequestBolt(Stream.SPEAKER_FLOW, Stream.SPEAKER_FLOW_HS,
                topologyConfig.getFloodlightRegions());
        builder.setBolt(ComponentType.SPEAKER_FLOW_REQUEST_BOLT, speakerFlowRequestBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_FLOW_KAFKA_SPOUT)
                .shuffleGrouping(ComponentType.SPEAKER_FLOW_HS_KAFKA_SPOUT)
                .allGrouping(ComponentType.KILDA_TOPO_DISCO_BOLT, Stream.REGION_NOTIFICATION);
    }

    private void createSpeakerFlowRequestSpoutAndKafkaBolt(TopologyBuilder builder, int parallelism,
                                                           KafkaTopicsConfig topicsConfig) {
        KafkaSpout speakerFlowKafkaSpout = buildKafkaSpout(topicsConfig.getSpeakerFlowTopic(),
                ComponentType.SPEAKER_FLOW_KAFKA_SPOUT);
        builder.setSpout(ComponentType.SPEAKER_FLOW_KAFKA_SPOUT, speakerFlowKafkaSpout);

        for (String region: topologyConfig.getFloodlightRegions()) {
            KafkaBolt speakerFlowKafkaBolt = buildKafkaBolt(
                    Stream.formatWithRegion(topicsConfig.getSpeakerFlowRegionTopic(), region));
            builder.setBolt(Stream.formatWithRegion(ComponentType.SPEAKER_FLOW_KAFKA_BOLT, region),
                    speakerFlowKafkaBolt, parallelism)
                    .shuffleGrouping(ComponentType.SPEAKER_FLOW_REQUEST_BOLT,
                            Stream.formatWithRegion(Stream.SPEAKER_FLOW, region));
        }
    }

    private void createSpeakerFlowHsRequestSpoutAndKafkaBolt(TopologyBuilder builder, int parallelism,
                                                             KafkaTopicsConfig topicsConfig) {
        KafkaSpout speakerFlowKafkaSpout = buildKafkaSpoutForAbstractMessage(topicsConfig.getSpeakerFlowHsTopic(),
                ComponentType.SPEAKER_FLOW_HS_KAFKA_SPOUT);
        builder.setSpout(ComponentType.SPEAKER_FLOW_HS_KAFKA_SPOUT, speakerFlowKafkaSpout);

        for (String region: topologyConfig.getFloodlightRegions()) {
            KafkaBolt speakerFlowKafkaBolt = buildKafkaBoltWithAbstractMessageSupport(
                    Stream.formatWithRegion(topicsConfig.getSpeakerFlowRegionTopic(), region));
            builder.setBolt(Stream.formatWithRegion(ComponentType.SPEAKER_FLOW_HS_KAFKA_BOLT, region),
                    speakerFlowKafkaBolt, parallelism)
                    .shuffleGrouping(ComponentType.SPEAKER_FLOW_REQUEST_BOLT,
                            Stream.formatWithRegion(Stream.SPEAKER_FLOW_HS, region));
        }
    }

    private void createSpeakerFlowPingRequestStream(TopologyBuilder builder, int parallelism,
                                                    KafkaTopicsConfig topicsConfig) {
        KafkaSpout speakerPingKafkaSpout = buildKafkaSpout(topicsConfig.getSpeakerFlowPingTopic(),
                ComponentType.SPEAKER_PING_KAFKA_SPOUT);
        builder.setSpout(ComponentType.SPEAKER_PING_KAFKA_SPOUT, speakerPingKafkaSpout);

        for (String region: topologyConfig.getFloodlightRegions()) {
            KafkaBolt speakerPingKafkaBolt = buildKafkaBolt(
                    Stream.formatWithRegion(topicsConfig.getSpeakerFlowPingRegionTopic(), region));
            builder.setBolt(Stream.formatWithRegion(ComponentType.SPEAKER_PING_KAFKA_BOLT, region),
                    speakerPingKafkaBolt, parallelism)
                    .shuffleGrouping(ComponentType.SPEAKER_PING_REQUEST_BOLT,
                            Stream.formatWithRegion(Stream.SPEAKER_PING, region));
        }

        RequestBolt speakerPingRequestBolt = new RequestBolt(Stream.SPEAKER_PING,
                topologyConfig.getFloodlightRegions());
        builder.setBolt(ComponentType.SPEAKER_PING_REQUEST_BOLT, speakerPingRequestBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_PING_KAFKA_SPOUT)
                .allGrouping(ComponentType.KILDA_TOPO_DISCO_BOLT, Stream.REGION_NOTIFICATION);
    }

    private void createSpeakerRequestStream(TopologyBuilder builder, int parallelism,
                                            KafkaTopicsConfig topicsConfig) {
        KafkaSpout speakerKafkaSpout = buildKafkaSpout(topicsConfig.getSpeakerTopic(),
                ComponentType.SPEAKER_KAFKA_SPOUT);
        builder.setSpout(ComponentType.SPEAKER_KAFKA_SPOUT, speakerKafkaSpout);

        for (String region: topologyConfig.getFloodlightRegions()) {
            KafkaBolt speakerKafkaBolt = buildKafkaBolt(
                    Stream.formatWithRegion(topicsConfig.getSpeakerRegionTopic(), region));
            builder.setBolt(Stream.formatWithRegion(ComponentType.SPEAKER_KAFKA_BOLT, region),
                    speakerKafkaBolt, parallelism)
                    .shuffleGrouping(ComponentType.SPEAKER_REQUEST_BOLT,
                            Stream.formatWithRegion(Stream.SPEAKER, region));
        }

        SpeakerRequestBolt speakerRequestBolt = new SpeakerRequestBolt(Stream.SPEAKER,
                topologyConfig.getFloodlightRegions());
        builder.setBolt(ComponentType.SPEAKER_REQUEST_BOLT, speakerRequestBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_KAFKA_SPOUT)
                .allGrouping(ComponentType.KILDA_TOPO_DISCO_BOLT, Stream.REGION_NOTIFICATION);
    }


    private void createKildaTopoDiscoSpout(TopologyBuilder builder, int parallelism,
                                           List<String> kildaTopoDiscoTopics) {
        KafkaSpout kildaTopoDiscoSpout = buildKafkaSpout(kildaTopoDiscoTopics,
                ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT);
        builder.setSpout(ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT, kildaTopoDiscoSpout, parallelism);
    }

    private void createKildaTopoDiscoKafkaBolt(TopologyBuilder builder, int parallelism,
                                               KafkaTopicsConfig topicsConfig) {
        KafkaBolt kildaTopoDiscoKafkaBolt = buildKafkaBolt(topicsConfig.getTopoDiscoTopic());
        builder.setBolt(ComponentType.KILDA_TOPO_DISCO_KAFKA_BOLT, kildaTopoDiscoKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_TOPO_DISCO_REPLY_BOLT, Stream.KILDA_TOPO_DISCO)
                .shuffleGrouping(ComponentType.KILDA_TOPO_DISCO_BOLT, Stream.KILDA_TOPO_DISCO);
    }


    private void createKildaTopoDiscoReplyStream(TopologyBuilder builder, int spoutParallelism, int parallelism,
                                                 KafkaTopicsConfig topicsConfig,
                                                 List<String> kildaTopoDiscoTopics) {
        createKildaTopoDiscoSpout(builder, spoutParallelism, kildaTopoDiscoTopics);
        createKildaTopoDiscoKafkaBolt(builder, parallelism, topicsConfig);

        DiscoReplyBolt replyBolt = new DiscoReplyBolt(Stream.KILDA_TOPO_DISCO);
        builder.setBolt(ComponentType.KILDA_TOPO_DISCO_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT);
    }

    private void createSpeakerDiscoSpout(TopologyBuilder builder, int parallelism,
                                         String kildaTopoDiscoTopic) {
        KafkaSpout speakerDiscoSpout = buildKafkaSpout(kildaTopoDiscoTopic,
                ComponentType.SPEAKER_DISCO_KAFKA_SPOUT);
        builder.setSpout(ComponentType.SPEAKER_DISCO_KAFKA_SPOUT, speakerDiscoSpout, parallelism);
    }

    private void createSpeakerDiscoKafkaBolt(TopologyBuilder builder, int parallelism,
                                             KafkaTopicsConfig topicsConfig) {
        for (String region: topologyConfig.getFloodlightRegions()) {
            KafkaBolt speakerDiscoKafkaBolt = buildKafkaBolt(
                    Stream.formatWithRegion(topicsConfig.getSpeakerDiscoRegionTopic(), region));
            builder.setBolt(Stream.formatWithRegion(ComponentType.SPEAKER_DISCO_KAFKA_BOLT, region),
                    speakerDiscoKafkaBolt, parallelism)
                    .shuffleGrouping(ComponentType.KILDA_TOPO_DISCO_BOLT,
                            Stream.formatWithRegion(Stream.SPEAKER_DISCO, region))
                    .shuffleGrouping(ComponentType.SPEAKER_DISCO_REQUEST_BOLT,
                            Stream.formatWithRegion(Stream.SPEAKER_DISCO, region));

        }
    }

    private void createSpeakerDiscoRequestStream(TopologyBuilder builder, int parallelism,
                                                 KafkaTopicsConfig topicsConfig) {
        createSpeakerDiscoSpout(builder, parallelism, topicsConfig.getSpeakerDiscoTopic());
        createSpeakerDiscoKafkaBolt(builder, parallelism, topicsConfig);
        RequestBolt speakerDiscoRequestBolt = new RequestBolt(Stream.SPEAKER_DISCO,
                topologyConfig.getFloodlightRegions());
        builder.setBolt(ComponentType.SPEAKER_DISCO_REQUEST_BOLT, speakerDiscoRequestBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_DISCO_KAFKA_SPOUT)
                .allGrouping(ComponentType.KILDA_TOPO_DISCO_BOLT, Stream.REGION_NOTIFICATION);

    }

    private void createDiscoveryPipelines(TopologyBuilder builder, int parallelism, KafkaTopicsConfig topicsConfig,
                                          List<String> kildaTopoDiscoTopics) {

        DiscoveryBolt discoveryBolt = new DiscoveryBolt(
                persistenceManager,
                topologyConfig.getFloodlightRegions(), topologyConfig.getFloodlightAliveTimeout(),
                topologyConfig.getFloodlightAliveInterval(), topologyConfig.getFloodlightDumpInterval());
        builder.setBolt(ComponentType.KILDA_TOPO_DISCO_BOLT, discoveryBolt, parallelism)
                .shuffleGrouping(ComponentType.KILDA_TOPO_DISCO_REPLY_BOLT, Stream.DISCO_REPLY);
    }

    private void createStatsStatsRequestStream(TopologyBuilder builder, int parallelism,
                                               KafkaTopicsConfig topicsConfig) {
        KafkaSpout statsStatsRequestKafkaSpout = buildKafkaSpout(topicsConfig.getStatsStatsRequestPrivTopic(),
                ComponentType.STATS_STATS_REQUEST_KAFKA_SPOUT);
        builder.setSpout(ComponentType.STATS_STATS_REQUEST_KAFKA_SPOUT, statsStatsRequestKafkaSpout);

        for (String region: topologyConfig.getFloodlightRegions()) {
            KafkaBolt statsStatsRequestKafkaBolt = buildKafkaBolt(
                    Stream.formatWithRegion(topicsConfig.getStatsStatsRequestPrivRegionTopic(), region));
            builder.setBolt(Stream.formatWithRegion(ComponentType.STATS_STATS_REQUEST_KAFKA_BOLT, region),
                    statsStatsRequestKafkaBolt, parallelism)
                    .shuffleGrouping(ComponentType.STATS_STATS_REQUEST_BOLT,
                            Stream.formatWithRegion(Stream.STATS_STATS_REQUEST_PRIV, region));
        }

        BroadcastRequestBolt speakerRequestBolt = new BroadcastRequestBolt(Stream.STATS_STATS_REQUEST_PRIV,
                topologyConfig.getFloodlightRegions());
        builder.setBolt(ComponentType.STATS_STATS_REQUEST_BOLT, speakerRequestBolt, parallelism)
                .shuffleGrouping(ComponentType.STATS_STATS_REQUEST_KAFKA_SPOUT);
    }

    private void createFlStatsSwitchesStream(TopologyBuilder builder,
                                             int parallelism,
                                             KafkaTopicsConfig topicsConfig,
                                             List<String> kildaFlStatsSwitchesTopics) {
        KafkaSpout flStatsSwitchesSpout = buildKafkaSpout(kildaFlStatsSwitchesTopics,
                ComponentType.FL_STATS_SWITCHES_SPOUT);
        builder.setSpout(ComponentType.FL_STATS_SWITCHES_SPOUT, flStatsSwitchesSpout);

        ReplyBolt replyBolt = new ReplyBolt(Stream.FL_STATS_SWITCHES);
        builder.setBolt(ComponentType.FL_STATS_SWITCHES_REPLY_BOLT, replyBolt, parallelism)
                .shuffleGrouping(ComponentType.FL_STATS_SWITCHES_SPOUT);

        KafkaBolt kildaFlStatsSwtichesKafkaBolt = buildKafkaBolt(topicsConfig.getFlStatsSwitchesPrivTopic());
        builder.setBolt(ComponentType.FL_STATS_SWITCHES_KAFKA_BOLT, kildaFlStatsSwtichesKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.FL_STATS_SWITCHES_REPLY_BOLT, Stream.FL_STATS_SWITCHES);
    }

    @Override
    public StormTopology createTopology() {
        logger.info("Creating FlowTopology - {}", topologyName);

        TopologyBuilder builder = new TopologyBuilder();
        Integer newParallelism = topologyConfig.getNewParallelism();
        Integer parallelism = topologyConfig.getParallelism();

        KafkaTopicsConfig topicsConfig = topologyConfig.getKafkaTopics();
        Set<String> regions = topologyConfig.getFloodlightRegions();
        // Floodlight -- kilda.flow --> Router
        List<String> kildaFlowTopics = new ArrayList<>();
        List<String> kildaFlowHsTopics = new ArrayList<>();
        for (String region: regions) {
            kildaFlowTopics.add(Stream.formatWithRegion(topicsConfig.getFlowRegionTopic(), region));
            kildaFlowHsTopics.add(Stream.formatWithRegion(topicsConfig.getFlowHsSpeakerRegionTopic(), region));
        }
        createKildaFlowReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaFlowTopics);
        createKildaFlowHsReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaFlowHsTopics);

        // Floodlight -- kilda.ping --> Router
        List<String> kildaPingTopics = new ArrayList<>();
        for (String region: regions) {
            kildaPingTopics.add(Stream.formatWithRegion(topicsConfig.getPingRegionTopic(), region));
        }
        createKildaPingReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaPingTopics);

        // Floodlight -- kilda.stats --> Router
        List<String> kildaStatsTopics = new ArrayList<>();
        for (String region: regions) {
            kildaStatsTopics.add(Stream.formatWithRegion(topicsConfig.getStatsRegionTopic(), region));
        }
        createKildaStatsReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaStatsTopics);

        // Floodlight -- kilda.topo.isl.latency --> Router
        List<String> kildaIslLatencyTopics = new ArrayList<>();
        for (String region: regions) {
            kildaIslLatencyTopics.add(Stream.formatWithRegion(topicsConfig.getTopoIslLatencyRegionTopic(), region));
        }
        createKildaIslLatencyReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaIslLatencyTopics);

        // Floodlight -- kilda.floodlight.connected.devices.priv --> Router
        List<String> kildaConnectedDevicesTopics = new ArrayList<>();
        for (String region: regions) {
            kildaConnectedDevicesTopics.add(Stream.formatWithRegion(
                    topicsConfig.getTopoConnectedDevicesRegionTopic(), region));
        }
        createKildaConnectedDevicesReplyStream(builder, parallelism, newParallelism,
                topicsConfig, kildaConnectedDevicesTopics);

        // Floodlight -- kilda.topo.switch.manager --> Router
        List<String> kildaSwitchManagerTopics = new ArrayList<>();
        for (String region: regions) {
            kildaSwitchManagerTopics.add(
                    Stream.formatWithRegion(topicsConfig.getTopoSwitchManagerRegionTopic(), region));
        }
        createKildaSwitchManagerReplyStream(builder, parallelism,
                newParallelism, topicsConfig, kildaSwitchManagerTopics);

        // Floodlight -- kilda.northbound --> Router
        List<String> kildaNorthboundTopics = new ArrayList<>();
        for (String region: regions) {
            kildaNorthboundTopics.add(Stream.formatWithRegion(topicsConfig.getNorthboundRegionTopic(), region));
        }
        createKildaNorthboundReplyStream(builder, parallelism,
                newParallelism, topicsConfig, kildaNorthboundTopics);

        // Floodlight -- kilda.topo.nb --> Router
        List<String> kildaNbWorkerTopics = new ArrayList<>();
        for (String region: regions) {
            kildaNbWorkerTopics.add(Stream.formatWithRegion(topicsConfig.getTopoNbRegionTopic(), region));
        }
        createKildaNbWorkerReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaNbWorkerTopics);

        // Part3 Request to Floodlights
        // Storm -- kilda.speaker.flow --> Floodlight
        createSpeakerFlowRequestStream(builder, newParallelism, topicsConfig);

        // Storm -- kilda.speaker.flow.ping --> Floodlight
        createSpeakerFlowPingRequestStream(builder, newParallelism, topicsConfig);

        // Storm -- kilda.speaker --> Floodlight
        createSpeakerRequestStream(builder, newParallelism, topicsConfig);

        // Storm -- kilda.speaker.disco --> Floodlight
        createSpeakerDiscoRequestStream(builder, newParallelism, topicsConfig);

        // Storm <-- kilda.topo.disco -- Floodlight
        List<String> kildaTopoDiscoTopics = new ArrayList<>();
        for (String region : topologyConfig.getFloodlightRegions()) {
            kildaTopoDiscoTopics.add(Stream.formatWithRegion(topicsConfig.getTopoDiscoRegionTopic(), region));

        }
        createKildaTopoDiscoReplyStream(builder, parallelism, newParallelism, topicsConfig, kildaTopoDiscoTopics);

        createDiscoveryPipelines(builder, parallelism, topicsConfig, kildaTopoDiscoTopics);

        // Storm -- kilda.stats.stats-request.priv --> Floodlight
        createStatsStatsRequestStream(builder, parallelism, topicsConfig);

        // Storm <-- kilda.fl-stats.switches.priv -- Floodlight
        List<String> kildaFlStatsSwitchesTopics = regions.stream()
                .map(region -> Stream.formatWithRegion(topicsConfig.getFlStatsSwitchesPrivRegionTopic(), region))
                .collect(Collectors.toList());
        createFlStatsSwitchesStream(builder, parallelism, topicsConfig, kildaFlStatsSwitchesTopics);

        return builder.createTopology();
    }

    /**
     * Topology entry point.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new FloodlightRouterTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
