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
import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.error.ConfigurationException;
import org.openkilda.wfm.kafka.AbstractMessageSerializer;
import org.openkilda.wfm.kafka.MessageSerializer;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.floodlightrouter.bolts.ControllerToSpeakerProxyBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.ControllerToSpeakerSharedProxyBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.MonotonicTick;
import org.openkilda.wfm.topology.floodlightrouter.bolts.RegionTrackerBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.SpeakerToControllerProxyBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.SpeakerToNetworkProxyBolt;
import org.openkilda.wfm.topology.floodlightrouter.bolts.SwitchMonitorBolt;

import joptsimple.internal.Strings;
import lombok.Value;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.BoltDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Floodlight router topology.
 */
public class FloodlightRouterTopology extends AbstractTopology<FloodlightRouterTopologyConfig> {
    private final Set<String> regions;

    private final KafkaTopicsConfig kafkaTopics;
    private final PersistenceManager persistenceManager;

    public FloodlightRouterTopology(LaunchEnvironment env) throws ConfigurationException {
        super(env, FloodlightRouterTopologyConfig.class);

        regions = topologyConfig.getFloodlightRegions().stream()
                .filter(entry -> !Strings.isNullOrEmpty(entry))
                .collect(Collectors.toSet());
        if (regions.isEmpty()) {
            throw new ConfigurationException("regions list must not be empty");
        }

        kafkaTopics = topologyConfig.getKafkaTopics();
        persistenceManager = PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);
    }

    @Override
    public StormTopology createTopology() {
        logger.info("Creating FloodlightRouter topology as {}", topologyName);

        TopologyBuilder builder = new TopologyBuilder();
        int newParallelism = topologyConfig.getNewParallelism();
        int parallelism = topologyConfig.getParallelism();

        TopologyOutput output = kafkaOutput(builder, newParallelism);
        zkSpout(builder);
        zkBolt(builder);

        speakerToNetwork(builder, parallelism, newParallelism, output);
        networkToSpeaker(builder, parallelism, newParallelism, output);

        speakerToFlowHs(builder, parallelism, newParallelism, output);
        flowHsToSpeaker(builder, parallelism, newParallelism, output);

        speakerToPing(builder, parallelism, newParallelism, output);
        pingToSpeaker(builder, parallelism, newParallelism, output);

        speakerToStats(builder, parallelism, newParallelism, output);
        speakerToIslLatency(builder, parallelism, newParallelism, output);
        speakerToConnectedDevices(builder, parallelism, newParallelism, output);
        speakerToSwitchManager(builder, parallelism, newParallelism, output);
        speakerToNorthbound(builder, parallelism, newParallelism, output);
        speakerToNbWorker(builder, parallelism, newParallelism, output);

        controllerToSpeaker(builder, parallelism, newParallelism, output);

        regionTracker(builder, output);
        switchMonitor(builder, output, newParallelism);

        clock(builder);

        return builder.createTopology();
    }

    private void zkSpout(TopologyBuilder topology) {
        String zkString = getZookeeperConfig().getHosts();

        ZooKeeperSpout zooKeeperSpout = new ZooKeeperSpout(topologyConfig.getBlueGreenMode(), getZkTopoName(),
                zkString);
        topology.setSpout(ZooKeeperSpout.BOLT_ID, zooKeeperSpout, 1);
    }

    private void zkBolt(TopologyBuilder topology) {
        String zkString = getZookeeperConfig().getHosts();
        ZooKeeperBolt zooKeeperBolt = new ZooKeeperBolt(topologyConfig.getBlueGreenMode(), getZkTopoName(), zkString);
        topology.setBolt(ZooKeeperBolt.BOLT_ID, zooKeeperBolt, 1)
                .allGrouping(SpeakerToNetworkProxyBolt.BOLT_ID, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.SPEAKER_DISCO_REQUEST_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_FLOW_HS_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.SPEAKER_FLOW_REQUEST_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_PING_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(Stream.SPEAKER_PING, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_STATS_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_ISL_LATENCY_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_CONNECTED_DEVICES_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_SWITCH_MANAGER_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.NORTHBOUND_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.KILDA_NB_WORKER_REPLY_BOLT, ZkStreams.ZK.toString())
                .allGrouping(ComponentType.SPEAKER_REQUEST_BOLT, ZkStreams.ZK.toString())
                .allGrouping(RegionTrackerBolt.BOLT_ID, ZkStreams.ZK.toString())
                .allGrouping(SwitchMonitorBolt.BOLT_ID, ZkStreams.ZK.toString());
    }

    private void speakerToNetwork(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        KafkaSpout<String, Message> spout = buildKafkaSpout(
                makeRegionTopics(kafkaTopics.getTopoDiscoRegionTopic()), ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT,
                getZkTopoName(), getConfig().getBlueGreenMode());
        topology.setSpout(ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT, spout, spoutParallelism);

        SpeakerToNetworkProxyBolt proxy = new SpeakerToNetworkProxyBolt(
                kafkaTopics.getTopoDiscoTopic(), Duration.ofSeconds(topologyConfig.getSwitchMappingRemoveDelay()));
        topology.setBolt(SpeakerToNetworkProxyBolt.BOLT_ID, proxy, parallelism)
                .allGrouping(SwitchMonitorBolt.BOLT_ID, SwitchMonitorBolt.STREAM_REGION_MAPPING_ID)
                .shuffleGrouping(ComponentType.KILDA_TOPO_DISCO_KAFKA_SPOUT)
                .allGrouping(ZooKeeperSpout.BOLT_ID);

        output.getKafkaGenericOutput()
                .shuffleGrouping(SpeakerToNetworkProxyBolt.BOLT_ID);
    }

    private void networkToSpeaker(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareControllerToSpeakerProxy(
                topology, kafkaTopics.getSpeakerDiscoRegionTopic(), kafkaTopics.getSpeakerDiscoTopic(),
                ComponentType.SPEAKER_DISCO_KAFKA_SPOUT, ComponentType.SPEAKER_DISCO_REQUEST_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToFlowHs(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        KafkaSpout<String, AbstractMessage> spout = buildKafkaSpoutForAbstractMessage(
                makeRegionTopics(kafkaTopics.getFlowHsSpeakerRegionTopic()), ComponentType.KILDA_FLOW_HS_KAFKA_SPOUT,
                getZkTopoName(), getConfig().getBlueGreenMode());
        topology.setSpout(ComponentType.KILDA_FLOW_HS_KAFKA_SPOUT, spout, spoutParallelism);

        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getFlowHsSpeakerTopic(),
                ComponentType.KILDA_FLOW_HS_KAFKA_SPOUT, ComponentType.KILDA_FLOW_HS_REPLY_BOLT,
                output.getKafkaHsOutput(), parallelism);
    }

    private void flowHsToSpeaker(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        KafkaSpout<String, AbstractMessage> spout = buildKafkaSpoutForAbstractMessage(
                kafkaTopics.getSpeakerFlowHsTopic(), ComponentType.SPEAKER_FLOW_HS_KAFKA_SPOUT,
                getZkTopoName(), getConfig().getBlueGreenMode());
        topology.setSpout(ComponentType.SPEAKER_FLOW_HS_KAFKA_SPOUT, spout, spoutParallelism);

        declareControllerToSpeakerProxy(
                topology, kafkaTopics.getSpeakerFlowRegionTopic(),
                ComponentType.SPEAKER_FLOW_HS_KAFKA_SPOUT, ComponentType.SPEAKER_FLOW_REQUEST_BOLT,
                output.getKafkaHsOutput(), parallelism);
    }

    private void speakerToPing(TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getPingRegionTopic(), kafkaTopics.getPingTopic(),
                ComponentType.KILDA_PING_KAFKA_SPOUT, ComponentType.KILDA_PING_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void pingToSpeaker(TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareControllerToSpeakerProxy(
                topology, kafkaTopics.getSpeakerFlowPingRegionTopic(), kafkaTopics.getSpeakerFlowPingTopic(),
                ComponentType.SPEAKER_PING_KAFKA_SPOUT, Stream.SPEAKER_PING,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToStats(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getStatsRegionTopic(), kafkaTopics.getStatsTopic(),
                ComponentType.KILDA_STATS_KAFKA_SPOUT, ComponentType.KILDA_STATS_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToIslLatency(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getTopoIslLatencyRegionTopic(), kafkaTopics.getTopoIslLatencyTopic(),
                ComponentType.KILDA_ISL_LATENCY_KAFKA_SPOUT, ComponentType.KILDA_ISL_LATENCY_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToConnectedDevices(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getTopoConnectedDevicesRegionTopic(), kafkaTopics.getTopoConnectedDevicesTopic(),
                ComponentType.KILDA_CONNECTED_DEVICES_KAFKA_SPOUT, ComponentType.KILDA_CONNECTED_DEVICES_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToSwitchManager(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getTopoSwitchManagerRegionTopic(), kafkaTopics.getTopoSwitchManagerTopic(),
                ComponentType.KILDA_SWITCH_MANAGER_KAFKA_SPOUT, ComponentType.KILDA_SWITCH_MANAGER_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToNorthbound(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getNorthboundRegionTopic(), kafkaTopics.getNorthboundTopic(),
                ComponentType.NORTHBOUND_REPLY_KAFKA_SPOUT, ComponentType.NORTHBOUND_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void speakerToNbWorker(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        declareSpeakerToControllerProxy(
                topology, kafkaTopics.getTopoNbRegionTopic(), kafkaTopics.getTopoNbTopic(),
                ComponentType.KILDA_NB_WORKER_KAFKA_SPOUT, ComponentType.KILDA_NB_WORKER_REPLY_BOLT,
                output.getKafkaGenericOutput(), spoutParallelism, parallelism);
    }

    private void controllerToSpeaker(
            TopologyBuilder topology, int spoutParallelism, int parallelism, TopologyOutput output) {
        BoltDeclarer kafkaProducer = output.getKafkaGenericOutput();

        KafkaSpout<String, Message> spout = buildKafkaSpout(
                kafkaTopics.getSpeakerTopic(), ComponentType.SPEAKER_KAFKA_SPOUT, getZkTopoName(),
                getConfig().getBlueGreenMode());
        topology.setSpout(ComponentType.SPEAKER_KAFKA_SPOUT, spout, spoutParallelism);

        ControllerToSpeakerProxyBolt proxy = new ControllerToSpeakerSharedProxyBolt(
                kafkaTopics.getSpeakerRegionTopic(), regions, kafkaTopics,
                Duration.ofSeconds(topologyConfig.getSwitchMappingRemoveDelay()));
        topology.setBolt(ComponentType.SPEAKER_REQUEST_BOLT, proxy, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_KAFKA_SPOUT)
                .allGrouping(SwitchMonitorBolt.BOLT_ID, SwitchMonitorBolt.STREAM_REGION_MAPPING_ID)
                .allGrouping(ZooKeeperSpout.BOLT_ID);

        kafkaProducer
                .shuffleGrouping(ComponentType.SPEAKER_REQUEST_BOLT)
                .shuffleGrouping(ComponentType.SPEAKER_REQUEST_BOLT, Stream.KILDA_SWITCH_MANAGER)
                .shuffleGrouping(ComponentType.SPEAKER_REQUEST_BOLT, Stream.NORTHBOUND_REPLY);
    }

    private void regionTracker(TopologyBuilder topology, TopologyOutput output) {
        RegionTrackerBolt bolt = new RegionTrackerBolt(
                kafkaTopics.getSpeakerDiscoRegionTopic(), persistenceManager, regions,
                topologyConfig.getFloodlightAliveTimeout(), topologyConfig.getFloodlightAliveInterval(),
                topologyConfig.getFloodlightDumpInterval());
        topology.setBolt(RegionTrackerBolt.BOLT_ID, bolt, 1)  // must be 1 for now
                .allGrouping(MonotonicTick.BOLT_ID)
                .allGrouping(ZooKeeperSpout.BOLT_ID)
                .shuffleGrouping(SpeakerToNetworkProxyBolt.BOLT_ID, SpeakerToNetworkProxyBolt.STREAM_ALIVE_EVIDENCE_ID);

        output.getKafkaGenericOutput()
                .shuffleGrouping(RegionTrackerBolt.BOLT_ID, RegionTrackerBolt.STREAM_SPEAKER_ID);
    }


    private void switchMonitor(TopologyBuilder topology, TopologyOutput output, int parallelism) {
        Fields switchIdGrouping = new Fields(SpeakerToNetworkProxyBolt.FIELD_ID_SWITCH_ID);

        SwitchMonitorBolt bolt = new SwitchMonitorBolt(kafkaTopics.getTopoDiscoTopic());
        topology.setBolt(SwitchMonitorBolt.BOLT_ID, bolt, parallelism)
                .allGrouping(MonotonicTick.BOLT_ID)
                .allGrouping(RegionTrackerBolt.BOLT_ID, RegionTrackerBolt.STREAM_REGION_NOTIFICATION_ID)
                .allGrouping(ZooKeeperSpout.BOLT_ID)
                .fieldsGrouping(
                        SpeakerToNetworkProxyBolt.BOLT_ID, SpeakerToNetworkProxyBolt.STREAM_CONNECT_NOTIFICATION_ID,
                        switchIdGrouping);

        output.getKafkaGenericOutput().shuffleGrouping(SwitchMonitorBolt.BOLT_ID, SwitchMonitorBolt.STREAM_NETWORK_ID);
    }

    private void clock(TopologyBuilder topology) {
        topology.setBolt(MonotonicTick.BOLT_ID, new MonotonicTick(), 1);
    }

    private TopologyOutput kafkaOutput(TopologyBuilder topology, int scaleFactor) {
        RegionAwareKafkaTopicSelector topicSelector = new RegionAwareKafkaTopicSelector();
        BoltDeclarer generic = topology.setBolt(
                ComponentType.KAFKA_GENERIC_OUTPUT,
                makeKafkaBolt(MessageSerializer.class, getZkTopoName(), getConfig().getBlueGreenMode())
                        .withTopicSelector(topicSelector),
                scaleFactor);
        BoltDeclarer hs = topology.setBolt(
                ComponentType.KAFKA_HS_OUTPUT,
                makeKafkaBolt(AbstractMessageSerializer.class, getZkTopoName(), getConfig().getBlueGreenMode())
                        .withTopicSelector(topicSelector),
                scaleFactor);

        return new TopologyOutput(generic, hs);
    }

    private void declareSpeakerToControllerProxy(
            TopologyBuilder topology, String speakerTopicsSeed, String controllerTopic, String spoutId,
            String proxyBoltId, BoltDeclarer output, int spoutParallelism, int proxyParallelism) {
        KafkaSpout<String, Message> spout = buildKafkaSpout(makeRegionTopics(speakerTopicsSeed), spoutId,
                getZkTopoName(), getConfig().getBlueGreenMode());
        topology.setSpout(spoutId, spout, spoutParallelism);

        declareSpeakerToControllerProxy(topology, controllerTopic, spoutId, proxyBoltId, output, proxyParallelism);
    }

    private void declareSpeakerToControllerProxy(
            TopologyBuilder topology, String controllerTopic, String spoutId, String proxyBoltId, BoltDeclarer output,
            int parallelism) {
        SpeakerToControllerProxyBolt proxy = new SpeakerToControllerProxyBolt(
                controllerTopic, Duration.ofSeconds(topologyConfig.getSwitchMappingRemoveDelay()));
        topology.setBolt(proxyBoltId, proxy, parallelism)
                .allGrouping(SwitchMonitorBolt.BOLT_ID, SwitchMonitorBolt.STREAM_REGION_MAPPING_ID)
                .shuffleGrouping(spoutId)
                .allGrouping(ZooKeeperSpout.BOLT_ID);

        output.shuffleGrouping(proxyBoltId);
    }

    private void declareControllerToSpeakerProxy(
            TopologyBuilder topology, String speakerTopicsSeed, String controllerTopic, String spoutId,
            String proxyBoltId, BoltDeclarer output, int spoutParallelism, int proxyParallelism) {
        KafkaSpout<String, Message> spout = buildKafkaSpout(controllerTopic, spoutId,
                getZkTopoName(), getConfig().getBlueGreenMode());
        topology.setSpout(spoutId, spout, spoutParallelism);

        declareControllerToSpeakerProxy(
                topology, speakerTopicsSeed, spoutId, proxyBoltId, output, proxyParallelism);
    }

    private void declareControllerToSpeakerProxy(
            TopologyBuilder topology, String speakerTopicsSeed, String spoutId, String proxyBoltId,
            BoltDeclarer output, int parallelism) {
        ControllerToSpeakerProxyBolt proxy = new ControllerToSpeakerProxyBolt(
                speakerTopicsSeed, regions, Duration.ofSeconds(topologyConfig.getSwitchMappingRemoveDelay()));
        topology.setBolt(proxyBoltId, proxy, parallelism)
                .shuffleGrouping(spoutId)
                .allGrouping(SwitchMonitorBolt.BOLT_ID, SwitchMonitorBolt.STREAM_REGION_MAPPING_ID)
                .allGrouping(ZooKeeperSpout.BOLT_ID);


        output.shuffleGrouping(proxyBoltId);
    }

    private List<String> makeRegionTopics(String topicSeed) {
        List<String> regionTopics = new ArrayList<>(regions.size());
        for (String entry : regions) {
            regionTopics.add(RegionAwareKafkaTopicSelector.formatTopicName(topicSeed, entry));
        }
        return regionTopics;
    }

    @Override
    protected String getZkTopoName() {
        return "floodlight_router";
    }

    @Value
    private static class TopologyOutput {
        BoltDeclarer kafkaGenericOutput;
        BoltDeclarer kafkaHsOutput;
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
