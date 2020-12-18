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

package org.openkilda.wfm.topology.network;

import org.openkilda.config.KafkaTopicsConfig;
import org.openkilda.messaging.Message;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.kafka.MessageSerializer;
import org.openkilda.wfm.share.hubandspoke.CoordinatorBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.storm.ComponentId;
import org.openkilda.wfm.topology.network.storm.bolt.FlowMonitoringEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.GrpcEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.GrpcRouter;
import org.openkilda.wfm.topology.network.storm.bolt.NetworkPersistedStateImportHandler;
import org.openkilda.wfm.topology.network.storm.bolt.NorthboundEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.RerouteEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.SpeakerEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.SpeakerRulesEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.StatusEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.SwitchManagerEncoder;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.BfdHub;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.BfdWorker;
import org.openkilda.wfm.topology.network.storm.bolt.decisionmaker.DecisionMakerHandler;
import org.openkilda.wfm.topology.network.storm.bolt.history.HistoryHandler;
import org.openkilda.wfm.topology.network.storm.bolt.isl.IslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.port.PortHandler;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRulesRouter;
import org.openkilda.wfm.topology.network.storm.bolt.speaker.SpeakerRulesWorker;
import org.openkilda.wfm.topology.network.storm.bolt.sw.SwitchHandler;
import org.openkilda.wfm.topology.network.storm.bolt.swmanager.SwitchManagerRouter;
import org.openkilda.wfm.topology.network.storm.bolt.swmanager.SwitchManagerWorker;
import org.openkilda.wfm.topology.network.storm.bolt.uniisl.UniIslHandler;
import org.openkilda.wfm.topology.network.storm.bolt.watcher.WatcherHandler;
import org.openkilda.wfm.topology.network.storm.bolt.watchlist.WatchListHandler;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.concurrent.TimeUnit;

public class NetworkTopology extends AbstractTopology<NetworkTopologyConfig> {
    private final PersistenceManager persistenceManager;
    private final NetworkOptions options;
    private final KafkaTopicsConfig kafkaTopics;

    public NetworkTopology(LaunchEnvironment env) {
        super(env, "network-topology", NetworkTopologyConfig.class);

        persistenceManager = PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);
        options = new NetworkOptions(getConfig());
        kafkaTopics = getConfig().getKafkaTopics();
    }

    /**
     * Discovery topology factory.
     */
    @Override
    public StormTopology createTopology() {
        TopologyBuilder topology = new TopologyBuilder();

        zookeeperSpout(topology);
        inputSwitchManager(topology);
        switchManagerRouter(topology);
        workerSwitchManager(topology);

        inputSpeaker(topology);
        inputSpeakerRules(topology);
        workerSpeakerRules(topology);

        inputGrpc(topology);
        routeGrpc(topology);

        coordinator(topology);
        networkHistory(topology);

        speakerRouter(topology);
        speakerRulesRouter(topology);
        watchList(topology);
        watcher(topology);
        decisionMaker(topology);

        switchHandler(topology);
        portHandler(topology);
        bfdHub(topology);
        bfdWorker(topology);
        uniIslHandler(topology);
        islHandler(topology);

        outputSpeaker(topology);
        outputSwitchManager(topology);
        outputSpeakerRules(topology);
        outputReroute(topology);
        outputStatus(topology);
        outputNorthbound(topology);
        outputGrpc(topology);
        outputFlowMonitoring(topology);

        historyBolt(topology);

        zookeeperBolt(topology);

        return topology.createTopology();
    }

    private void coordinator(TopologyBuilder topology) {
        declareSpout(topology, new CoordinatorSpout(), CoordinatorSpout.ID);

        Fields keyGrouping = new Fields(MessageKafkaTranslator.KEY_FIELD);
        declareBolt(topology, new CoordinatorBolt(), CoordinatorBolt.ID)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(BfdWorker.BOLT_ID, CoordinatorBolt.INCOME_STREAM, keyGrouping)
                .fieldsGrouping(SwitchManagerWorker.BOLT_ID, CoordinatorBolt.INCOME_STREAM, keyGrouping);
    }

    private void zookeeperSpout(TopologyBuilder topology) {
        ZooKeeperSpout zooKeeperSpout = new ZooKeeperSpout(getConfig().getBlueGreenMode(), getZkTopoName(),
                getZookeeperConfig().getConnectString());
        declareSpout(topology, zooKeeperSpout, ZooKeeperSpout.SPOUT_ID);
    }

    private void inputSpeaker(TopologyBuilder topology) {
        declareKafkaSpout(topology,
                kafkaTopics.getTopoDiscoTopic(), ComponentId.INPUT_SPEAKER.toString());
    }

    private void inputSwitchManager(TopologyBuilder topology) {
        declareKafkaSpout(topology,
                kafkaTopics.getNorthboundTopic(), ComponentId.INPUT_SWMANAGER.toString());
    }

    private void inputSpeakerRules(TopologyBuilder topology) {
        declareKafkaSpout(topology,
                kafkaTopics.getTopoSwitchManagerTopic(), ComponentId.INPUT_SPEAKER_RULES.toString());
    }

    private void workerSpeakerRules(TopologyBuilder topology) {
        long speakerIoTimeout = TimeUnit.SECONDS.toMillis(topologyConfig.getSpeakerIoTimeoutSeconds());
        WorkerBolt.Config workerConfig = SpeakerRulesWorker.Config.builder()
                .hubComponent(IslHandler.BOLT_ID)
                .workerSpoutComponent(SpeakerRulesRouter.BOLT_ID)
                .streamToHub(SpeakerRulesWorker.STREAM_HUB_ID)
                .defaultTimeout((int) speakerIoTimeout)
                .build();
        SpeakerRulesWorker speakerRulesWorker = new SpeakerRulesWorker(workerConfig);
        Fields keyGrouping = new Fields(MessageKafkaTranslator.KEY_FIELD);
        declareBolt(topology, speakerRulesWorker, SpeakerRulesWorker.BOLT_ID)
                .directGrouping(CoordinatorBolt.ID)
                .fieldsGrouping(workerConfig.getHubComponent(), IslHandler.STREAM_SPEAKER_RULES_ID, keyGrouping)
                .fieldsGrouping(workerConfig.getWorkerSpoutComponent(),
                        SpeakerRulesRouter.STREAM_WORKER_ID, keyGrouping);
    }

    private void speakerRouter(TopologyBuilder topology) {
        Fields keyGrouping = new Fields(MessageKafkaTranslator.KEY_FIELD);
        SpeakerRouter bolt = new SpeakerRouter(ZooKeeperSpout.SPOUT_ID);
        declareBolt(topology, bolt, SpeakerRouter.BOLT_ID)
                .fieldsGrouping(ComponentId.INPUT_SPEAKER.toString(), keyGrouping)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);
    }

    private void inputGrpc(TopologyBuilder topology) {
        declareKafkaSpout(topology,
                kafkaTopics.getGrpcResponseTopic(), ComponentId.INPUT_GRPC.toString());
    }

    private void routeGrpc(TopologyBuilder topology) {
        GrpcRouter bolt = new GrpcRouter();
        declareBolt(topology, bolt, GrpcRouter.BOLT_ID)
                .shuffleGrouping(ComponentId.INPUT_GRPC.toString());
    }

    private void workerSwitchManager(TopologyBuilder topology) {
        long swmanagerIoTimeout = TimeUnit.SECONDS.toMillis(topologyConfig.getSwitchManagerIoTimeoutSeconds());
        WorkerBolt.Config workerConfig = SwitchManagerWorker.Config.builder()
                .hubComponent(SwitchHandler.BOLT_ID)
                .workerSpoutComponent(SwitchManagerRouter.BOLT_ID)
                .streamToHub(SwitchManagerWorker.STREAM_HUB_ID)
                .defaultTimeout((int) swmanagerIoTimeout)
                .build();
        SwitchManagerWorker switchManagerWorker = new SwitchManagerWorker(workerConfig, options);
        Fields keyGrouping = new Fields(MessageKafkaTranslator.FIELD_ID_KEY);
        declareBolt(topology, switchManagerWorker, SwitchManagerWorker.BOLT_ID)
                .directGrouping(CoordinatorBolt.ID)
                .fieldsGrouping(workerConfig.getHubComponent(), SwitchHandler.STREAM_SWMANAGER_ID, keyGrouping)
                .fieldsGrouping(workerConfig.getWorkerSpoutComponent(),
                        SwitchManagerRouter.STREAM_WORKER_ID, keyGrouping);
    }

    private void switchManagerRouter(TopologyBuilder topology) {
        Fields keyGrouping = new Fields(MessageKafkaTranslator.FIELD_ID_KEY);
        SwitchManagerRouter bolt = new SwitchManagerRouter();
        declareBolt(topology, bolt, SwitchManagerRouter.BOLT_ID)
                .fieldsGrouping(ComponentId.INPUT_SWMANAGER.toString(), keyGrouping);
    }

    private void speakerRulesRouter(TopologyBuilder topology) {
        Fields keyGrouping = new Fields(MessageKafkaTranslator.KEY_FIELD);
        SpeakerRulesRouter bolt = new SpeakerRulesRouter();
        declareBolt(topology, bolt, SpeakerRulesRouter.BOLT_ID)
                .fieldsGrouping(ComponentId.INPUT_SPEAKER_RULES.toString(), keyGrouping);
    }

    private void networkHistory(TopologyBuilder topology) {
        NetworkPersistedStateImportHandler bolt = new NetworkPersistedStateImportHandler(
                persistenceManager, ZooKeeperSpout.SPOUT_ID);
        declareBolt(topology, bolt, NetworkPersistedStateImportHandler.BOLT_ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);
    }

    private void watchList(TopologyBuilder topology) {
        WatchListHandler bolt = new WatchListHandler(options, ZooKeeperSpout.SPOUT_ID);
        Fields portGrouping = new Fields(PortHandler.FIELD_ID_DATAPATH, PortHandler.FIELD_ID_PORT_NUMBER);
        Fields uniIslGrouping = new Fields(UniIslHandler.FIELD_ID_DATAPATH, UniIslHandler.FIELD_ID_PORT_NUMBER);
        Fields islGrouping = new Fields(IslHandler.FIELD_ID_DATAPATH, IslHandler.FIELD_ID_PORT_NUMBER);
        declareBolt(topology, bolt, WatchListHandler.BOLT_ID)
                .allGrouping(CoordinatorSpout.ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID)
                .fieldsGrouping(PortHandler.BOLT_ID, PortHandler.STREAM_POLL_ID, portGrouping)
                .fieldsGrouping(UniIslHandler.BOLT_ID, UniIslHandler.STREAM_POLL_ID, uniIslGrouping)
                .fieldsGrouping(IslHandler.BOLT_ID, IslHandler.STREAM_POLL_ID, islGrouping);
    }

    private void watcher(TopologyBuilder topology) {
        WatcherHandler bolt = new WatcherHandler(options);
        Fields watchListGrouping = new Fields(WatchListHandler.FIELD_ID_DATAPATH,
                WatchListHandler.FIELD_ID_PORT_NUMBER);
        Fields speakerGrouping = new Fields(SpeakerRouter.FIELD_ID_DATAPATH, SpeakerRouter.FIELD_ID_PORT_NUMBER);
        declareBolt(topology, bolt, WatcherHandler.BOLT_ID)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(WatchListHandler.BOLT_ID, watchListGrouping)
                .fieldsGrouping(SpeakerRouter.BOLT_ID, SpeakerRouter.STREAM_WATCHER_ID, speakerGrouping);
    }

    private void decisionMaker(TopologyBuilder topology) {
        DecisionMakerHandler bolt = new DecisionMakerHandler(options);
        Fields watcherGrouping = new Fields(WatcherHandler.FIELD_ID_DATAPATH, WatcherHandler.FIELD_ID_PORT_NUMBER);
        declareBolt(topology, bolt, DecisionMakerHandler.BOLT_ID)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(WatcherHandler.BOLT_ID, watcherGrouping);
    }

    private void switchHandler(TopologyBuilder topology) {
        SwitchHandler bolt = new SwitchHandler(options, persistenceManager);
        Fields grouping = new Fields(SpeakerRouter.FIELD_ID_DATAPATH);
        declareBolt(topology, bolt, SwitchHandler.BOLT_ID)
                .fieldsGrouping(NetworkPersistedStateImportHandler.BOLT_ID, grouping)
                .fieldsGrouping(SpeakerRouter.BOLT_ID, grouping)
                .directGrouping(SwitchManagerWorker.BOLT_ID, SwitchManagerWorker.STREAM_HUB_ID);
    }

    private void portHandler(TopologyBuilder topology) {
        PortHandler bolt = new PortHandler(options, persistenceManager, ZooKeeperSpout.SPOUT_ID);
        Fields endpointGrouping = new Fields(SwitchHandler.FIELD_ID_DATAPATH, SwitchHandler.FIELD_ID_PORT_NUMBER);
        Fields decisionMakerGrouping = new Fields(DecisionMakerHandler.FIELD_ID_DATAPATH,
                DecisionMakerHandler.FIELD_ID_PORT_NUMBER);
        declareBolt(topology, bolt, PortHandler.BOLT_ID)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(SwitchHandler.BOLT_ID, SwitchHandler.STREAM_PORT_ID, endpointGrouping)
                .fieldsGrouping(DecisionMakerHandler.BOLT_ID, decisionMakerGrouping)
                .fieldsGrouping(SpeakerRouter.BOLT_ID, SpeakerRouter.STREAM_PORT_ID, endpointGrouping)
                .allGrouping(SpeakerRouter.BOLT_ID, SpeakerRouter.STREAM_BCAST_ID);
    }

    private void bfdHub(TopologyBuilder topology) {
        BfdHub bolt = new BfdHub(options, persistenceManager);
        Fields switchGrouping = new Fields(SwitchHandler.FIELD_ID_DATAPATH);
        Fields islGrouping = new Fields(IslHandler.FIELD_ID_DATAPATH);
        declareBolt(topology, bolt, BfdHub.BOLT_ID)
                .fieldsGrouping(SwitchHandler.BOLT_ID, SwitchHandler.STREAM_BFD_HUB_ID, switchGrouping)
                .fieldsGrouping(IslHandler.BOLT_ID, IslHandler.STREAM_BFD_HUB_ID, islGrouping)
                .directGrouping(BfdWorker.BOLT_ID, BfdWorker.STREAM_HUB_ID)
                .allGrouping(SpeakerRouter.BOLT_ID, SpeakerRouter.STREAM_BCAST_ID);
    }

    private void bfdWorker(TopologyBuilder topology) {
        long speakerIoTimeout = TimeUnit.SECONDS.toMillis(topologyConfig.getSpeakerIoTimeoutSeconds());
        WorkerBolt.Config workerConfig = BfdWorker.Config.builder()
                .hubComponent(BfdHub.BOLT_ID)
                .workerSpoutComponent(SpeakerRouter.BOLT_ID)
                .streamToHub(BfdWorker.STREAM_HUB_ID)
                .defaultTimeout((int) speakerIoTimeout)
                .build();
        BfdWorker bolt = new BfdWorker(workerConfig, persistenceManager);
        Fields keyGrouping = new Fields(MessageKafkaTranslator.KEY_FIELD);
        declareBolt(topology, bolt, BfdWorker.BOLT_ID)
                .directGrouping(CoordinatorBolt.ID)
                .fieldsGrouping(workerConfig.getHubComponent(), BfdHub.STREAM_WORKER_ID, keyGrouping)
                .fieldsGrouping(
                        workerConfig.getWorkerSpoutComponent(), SpeakerRouter.STREAM_BFD_WORKER_ID, keyGrouping)
                .fieldsGrouping(GrpcRouter.BOLT_ID, GrpcRouter.STREAM_BFD_WORKER_ID, keyGrouping);
    }

    private void uniIslHandler(TopologyBuilder topology) {
        UniIslHandler bolt = new UniIslHandler();
        Fields portGrouping = new Fields(PortHandler.FIELD_ID_DATAPATH, PortHandler.FIELD_ID_PORT_NUMBER);
        Fields bfdPortGrouping = new Fields(BfdHub.FIELD_ID_DATAPATH, BfdHub.FIELD_ID_PORT_NUMBER);
        Fields islGrouping = new Fields(IslHandler.FIELD_ID_DATAPATH, IslHandler.FIELD_ID_PORT_NUMBER);
        declareBolt(topology, bolt, UniIslHandler.BOLT_ID)
                .fieldsGrouping(PortHandler.BOLT_ID, portGrouping)
                .fieldsGrouping(BfdHub.BOLT_ID, BfdHub.STREAM_UNIISL_ID, bfdPortGrouping)
                .fieldsGrouping(IslHandler.BOLT_ID, IslHandler.STREAM_UNIISL_ID, islGrouping);
    }

    private void islHandler(TopologyBuilder topology) {
        IslHandler bolt = new IslHandler(persistenceManager, options);
        Fields islGrouping = new Fields(UniIslHandler.FIELD_ID_ISL_SOURCE, UniIslHandler.FIELD_ID_ISL_DEST);
        declareBolt(topology, bolt, IslHandler.BOLT_ID)
                .fieldsGrouping(UniIslHandler.BOLT_ID, islGrouping)
                .fieldsGrouping(SpeakerRouter.BOLT_ID, SpeakerRouter.STREAM_ISL_ID, islGrouping)
                .directGrouping(SpeakerRulesWorker.BOLT_ID, SpeakerRulesWorker.STREAM_HUB_ID);
    }

    private void outputSpeaker(TopologyBuilder topology) {
        SpeakerEncoder bolt = new SpeakerEncoder();
        declareBolt(topology, bolt, SpeakerEncoder.BOLT_ID)
                .shuffleGrouping(WatcherHandler.BOLT_ID, WatcherHandler.STREAM_SPEAKER_ID)
                .shuffleGrouping(BfdWorker.BOLT_ID, BfdWorker.STREAM_SPEAKER_ID);

        KafkaBolt output = buildKafkaBolt(kafkaTopics.getSpeakerDiscoTopic());
        declareBolt(topology, output, ComponentId.SPEAKER_OUTPUT.toString())
                .shuffleGrouping(SpeakerEncoder.BOLT_ID);
    }

    private void outputSwitchManager(TopologyBuilder topology) {
        SwitchManagerEncoder bolt = new SwitchManagerEncoder();
        declareBolt(topology, bolt, SwitchManagerEncoder.BOLT_ID)
                .shuffleGrouping(SwitchManagerWorker.BOLT_ID);

        KafkaBolt output = buildKafkaBolt(kafkaTopics.getTopoSwitchManagerNetworkTopic());
        declareBolt(topology, output, ComponentId.SWMANAGER_OUTPUT.toString())
                .shuffleGrouping(SwitchManagerEncoder.BOLT_ID);
    }

    private void outputSpeakerRules(TopologyBuilder topology) {
        SpeakerRulesEncoder encoderRules = new SpeakerRulesEncoder();
        declareBolt(topology, encoderRules, SpeakerRulesEncoder.BOLT_ID)
                .shuffleGrouping(SpeakerRulesWorker.BOLT_ID);

        KafkaBolt outputRules = buildKafkaBolt(kafkaTopics.getSpeakerTopic());
        declareBolt(topology, outputRules, ComponentId.SPEAKER_RULES_OUTPUT.toString())
                .shuffleGrouping(SpeakerRulesEncoder.BOLT_ID);

    }

    private void outputReroute(TopologyBuilder topology) {
        RerouteEncoder bolt = new RerouteEncoder();
        declareBolt(topology, bolt, RerouteEncoder.BOLT_ID)
                .shuffleGrouping(IslHandler.BOLT_ID, IslHandler.STREAM_REROUTE_ID)
                .shuffleGrouping(SwitchHandler.BOLT_ID, SwitchHandler.STREAM_REROUTE_ID);

        KafkaBolt output = buildKafkaBolt(kafkaTopics.getTopoRerouteTopic());
        declareBolt(topology, output, ComponentId.REROUTE_OUTPUT.toString())
                .shuffleGrouping(RerouteEncoder.BOLT_ID);
    }

    private void outputStatus(TopologyBuilder topology) {
        StatusEncoder bolt = new StatusEncoder();
        declareBolt(topology, bolt, StatusEncoder.BOLT_ID)
                .shuffleGrouping(IslHandler.BOLT_ID, IslHandler.STREAM_STATUS_ID);

        KafkaBolt output = buildKafkaBolt(kafkaTopics.getNetworkIslStatusTopic());
        declareBolt(topology, output, ComponentId.STATUS_OUTPUT.toString())
                .shuffleGrouping(StatusEncoder.BOLT_ID);
    }

    private void outputNorthbound(TopologyBuilder topology) {
        NorthboundEncoder bolt = new NorthboundEncoder();
        declareBolt(topology, bolt, NorthboundEncoder.BOLT_ID)
                .shuffleGrouping(PortHandler.BOLT_ID, PortHandler.STREAM_NORTHBOUND_ID);

        KafkaBolt kafkaNorthboundBolt = buildKafkaBolt(kafkaTopics.getNorthboundTopic());
        declareBolt(topology, kafkaNorthboundBolt, ComponentId.NB_OUTPUT.toString())
                .shuffleGrouping(NorthboundEncoder.BOLT_ID);
    }

    private void outputGrpc(TopologyBuilder topology) {
        GrpcEncoder encoder = new GrpcEncoder();
        declareBolt(topology, encoder, GrpcEncoder.BOLT_ID)
                .shuffleGrouping(BfdWorker.BOLT_ID, BfdWorker.STREAM_GRPC_ID);

        KafkaBolt<String, Message> output = makeKafkaBolt(kafkaTopics.getGrpcSpeakerTopic(), MessageSerializer.class);
        declareBolt(topology, output, ComponentId.GRPC_OUTPUT.toString())
                .shuffleGrouping(GrpcEncoder.BOLT_ID);
    }

    private void outputFlowMonitoring(TopologyBuilder topology) {
        FlowMonitoringEncoder encoder = new FlowMonitoringEncoder();
        declareBolt(topology, encoder, FlowMonitoringEncoder.BOLT_ID)
                .shuffleGrouping(IslHandler.BOLT_ID, IslHandler.STREAM_FLOW_MONITORING_ID);

        KafkaBolt<String, Message> output = makeKafkaBolt(kafkaTopics.getNetworkFlowMonitoringNotifyTopic(),
                MessageSerializer.class);
        declareBolt(topology, output, ComponentId.FLOW_MONITORING_OUTPUT.toString())
                .shuffleGrouping(FlowMonitoringEncoder.BOLT_ID);
    }

    private void historyBolt(TopologyBuilder topology) {
        HistoryHandler bolt = new HistoryHandler(persistenceManager);
        declareBolt(topology, bolt, ComponentId.HISTORY_HANDLER.toString())
                .shuffleGrouping(PortHandler.BOLT_ID, PortHandler.STREAM_HISTORY_ID);
    }

    private void zookeeperBolt(TopologyBuilder topology) {
        int expectedBoltsCount = getBoltInstancesCount(
                SpeakerRouter.BOLT_ID, WatchListHandler.BOLT_ID, NetworkPersistedStateImportHandler.BOLT_ID);
        ZooKeeperBolt zooKeeperBolt = new ZooKeeperBolt(topologyConfig.getBlueGreenMode(), getZkTopoName(),
                getZookeeperConfig().getConnectString(), expectedBoltsCount);
        declareBolt(topology, zooKeeperBolt, ZooKeeperBolt.BOLT_ID)
                .allGrouping(SpeakerRouter.BOLT_ID, SpeakerRouter.STREAM_ZOOKEEPER_ID)
                .allGrouping(WatchListHandler.BOLT_ID, WatchListHandler.STREAM_ZOOKEEPER_ID)
                .allGrouping(
                        NetworkPersistedStateImportHandler.BOLT_ID,
                        NetworkPersistedStateImportHandler.STREAM_ZOOKEEPER_ID);
    }

    @Override
    protected String getZkTopoName() {
        return "network";
    }

    /**
     * Discovery topology uploader.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new NetworkTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
