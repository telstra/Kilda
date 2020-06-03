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

package org.openkilda.wfm.topology.nbworker;

import org.openkilda.pce.PathComputerConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.hubandspoke.CoordinatorBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.nbworker.bolts.DiscoveryEncoderBolt;
import org.openkilda.wfm.topology.nbworker.bolts.FeatureTogglesBolt;
import org.openkilda.wfm.topology.nbworker.bolts.FlowMeterModifyHubBolt;
import org.openkilda.wfm.topology.nbworker.bolts.FlowOperationsBolt;
import org.openkilda.wfm.topology.nbworker.bolts.FlowPatchBolt;
import org.openkilda.wfm.topology.nbworker.bolts.FlowValidationHubBolt;
import org.openkilda.wfm.topology.nbworker.bolts.HistoryOperationsBolt;
import org.openkilda.wfm.topology.nbworker.bolts.KildaConfigurationBolt;
import org.openkilda.wfm.topology.nbworker.bolts.LinkOperationsBolt;
import org.openkilda.wfm.topology.nbworker.bolts.MessageEncoder;
import org.openkilda.wfm.topology.nbworker.bolts.PathsBolt;
import org.openkilda.wfm.topology.nbworker.bolts.ResponseSplitterBolt;
import org.openkilda.wfm.topology.nbworker.bolts.RouterBolt;
import org.openkilda.wfm.topology.nbworker.bolts.Server42EncoderBolt;
import org.openkilda.wfm.topology.nbworker.bolts.SpeakerWorkerBolt;
import org.openkilda.wfm.topology.nbworker.bolts.SwitchOperationsBolt;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.concurrent.TimeUnit;

/**
 * Storm topology to read data from database.
 * Topology design:
 * kilda.topo.nb-spout ---> router-bolt ---> validation-operations-bolt ---> response-splitter-bolt ---> nb-kafka-bolt
 * | ---> links-operations-bolt      ---> |   \                       |
 * | ---> flows-operations-bolt      ---> |  \ \                      |
 * | ---> switches-operations-bolt   ---> | \ \ \                     |
 * | ---> history-operations-bolt    ---> | \ \ \ \                   |
 * | --->                   message-encoder-bolt (in error case) ---> |
 *
 * <p>kilda.topo.nb-spout: reads data from kafka.
 * router-bolt: detects what kind of request is send, defines the stream. neo-bolt: performs operation with the
 * database. response-splitter-bolt: split response into small chunks, because kafka has limited size of messages.
 * nb-kafka-bolt: sends responses back to kafka to northbound topic.
 */
public class NbWorkerTopology extends AbstractTopology<NbWorkerTopologyConfig> {

    private static final String ROUTER_BOLT_NAME = "router-bolt";
    private static final String SWITCHES_BOLT_NAME = "switches-operations-bolt";
    private static final String LINKS_BOLT_NAME = "links-operations-bolt";
    private static final String FLOWS_BOLT_NAME = "flows-operations-bolt";
    private static final String FEATURE_TOGGLES_BOLT_NAME = "feature-toggles-bolt";
    private static final String KILDA_CONFIG_BOLT_NAME = "kilda-config-bolt";
    private static final String PATHS_BOLT_NAME = "paths-bolt";
    private static final String MESSAGE_ENCODER_BOLT_NAME = "message-encoder-bolt";
    private static final String DISCOVERY_ENCODER_BOLT_NAME = "discovery-encoder-bolt";
    private static final String SPLITTER_BOLT_NAME = "response-splitter-bolt";
    private static final String NB_KAFKA_BOLT_NAME = "nb-kafka-bolt";
    private static final String FLOW_HS_KAFKA_BOLT_NAME = "flowhs-kafka-bolt";
    private static final String DISCO_KAFKA_BOLT_NAME = "disco-kafka-bolt";
    private static final String PING_KAFKA_BOLT_NAME = "ping-kafka-bolt";
    private static final String HISTORY_BOLT_NAME = "history-operations-bolt";
    public static final String NB_SPOUT_ID = "nb-spout";
    private static final String SPEAKER_KAFKA_BOLT = "speaker-bolt";
    private static final String SWITCH_MANAGER_KAFKA_BOLT = "switch-manger-bolt";
    private static final String REROUTE_KAFKA_BOLT = "reroute-bolt";
    private static final String VALIDATION_WORKER_BOLT = "validation." + SpeakerWorkerBolt.ID;
    private static final String METER_MODIFY_WORKER_BOLT = "meter.modify." + SpeakerWorkerBolt.ID;
    private static final String FLOW_PATCH_BOLT_NAME = "flow-patch-bolt";
    private static final String SERVER42_ENCODER_BOLT_NAME = "server42-encoder-bolt";
    private static final String SERVER42_KAFKA_BOLT = "server42-kafka-bolt";
    private static final String METRICS_BOLT = "metrics.bolt";

    private static final Fields FIELDS_KEY = new Fields(MessageKafkaTranslator.FIELD_ID_KEY);

    public NbWorkerTopology(LaunchEnvironment env) {
        super(env, "nbworker-topology", NbWorkerTopologyConfig.class);
    }

    @Override
    public StormTopology createTopology() {
        logger.info("Creating NbWorkerTopology - {}", topologyName);

        TopologyBuilder tb = new TopologyBuilder();

        declareSpout(tb, new CoordinatorSpout(), CoordinatorSpout.ID);
        declareBolt(tb, new CoordinatorBolt(), CoordinatorBolt.ID)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(FlowValidationHubBolt.ID, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(VALIDATION_WORKER_BOLT, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(FlowMeterModifyHubBolt.ID, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(METER_MODIFY_WORKER_BOLT, CoordinatorBolt.INCOME_STREAM, FIELDS_KEY);

        declareKafkaSpout(tb, topologyConfig.getKafkaTopoNbTopic(), NB_SPOUT_ID);

        RouterBolt router = new RouterBolt(ZooKeeperSpout.SPOUT_ID);
        declareBolt(tb, router, ROUTER_BOLT_NAME)
                .shuffleGrouping(NB_SPOUT_ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);

        ZooKeeperSpout zooKeeperSpout = new ZooKeeperSpout(getConfig().getBlueGreenMode(), getZkTopoName(),
                getZookeeperConfig().getConnectString());
        declareSpout(tb, zooKeeperSpout, ZooKeeperSpout.SPOUT_ID);


        ZooKeeperBolt zooKeeperBolt = new ZooKeeperBolt(getConfig().getBlueGreenMode(), getZkTopoName(),
                getZookeeperConfig().getConnectString(), getBoltInstancesCount(ROUTER_BOLT_NAME,
                FlowMeterModifyHubBolt.ID, FlowValidationHubBolt.ID));
        declareBolt(tb, zooKeeperBolt, ZooKeeperBolt.BOLT_ID)
                .allGrouping(ROUTER_BOLT_NAME, ZkStreams.ZK.toString())
                .allGrouping(FlowMeterModifyHubBolt.ID, ZkStreams.ZK.toString())
                .allGrouping(FlowValidationHubBolt.ID, ZkStreams.ZK.toString());

        PersistenceManager persistenceManager =
                PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);
        PathComputerConfig pathComputerConfig = configurationProvider.getConfiguration(PathComputerConfig.class);

        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);

        HubBolt.Config validationHubConfig = HubBolt.Config.builder()
                .requestSenderComponent(ROUTER_BOLT_NAME)
                .workerComponent(VALIDATION_WORKER_BOLT)
                .lifeCycleEventComponent(ZooKeeperSpout.SPOUT_ID)
                .timeoutMs((int) TimeUnit.SECONDS.toMillis(topologyConfig.getProcessTimeout()))
                .build();
        declareBolt(tb,
                new FlowValidationHubBolt(validationHubConfig, persistenceManager, flowResourcesConfig,
                        topologyConfig.getFlowMeterMinBurstSizeInKbits(),
                        topologyConfig.getFlowMeterBurstCoefficient()), FlowValidationHubBolt.ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID)
                .fieldsGrouping(ROUTER_BOLT_NAME, FlowValidationHubBolt.INCOME_STREAM, FIELDS_KEY)
                .directGrouping(VALIDATION_WORKER_BOLT, FlowValidationHubBolt.INCOME_STREAM)
                .directGrouping(CoordinatorBolt.ID);

        WorkerBolt.Config speakerValidationWorkerConfig = WorkerBolt.Config.builder()
                .hubComponent(FlowValidationHubBolt.ID)
                .streamToHub(FlowValidationHubBolt.INCOME_STREAM)
                .workerSpoutComponent(ROUTER_BOLT_NAME)
                .defaultTimeout((int) TimeUnit.SECONDS.toMillis(topologyConfig.getOperationTimeout()))
                .build();
        declareBolt(tb, new SpeakerWorkerBolt(speakerValidationWorkerConfig), VALIDATION_WORKER_BOLT)
                .fieldsGrouping(ROUTER_BOLT_NAME, SpeakerWorkerBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(FlowValidationHubBolt.ID, StreamType.FLOW_VALIDATION_WORKER.toString(), FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);

        HubBolt.Config meterModifyHubConfig = HubBolt.Config.builder()
                .requestSenderComponent(ROUTER_BOLT_NAME)
                .workerComponent(METER_MODIFY_WORKER_BOLT)
                .lifeCycleEventComponent(ZooKeeperSpout.SPOUT_ID)
                .timeoutMs((int) TimeUnit.SECONDS.toMillis(topologyConfig.getProcessTimeout()))
                .build();
        declareBolt(tb, new FlowMeterModifyHubBolt(meterModifyHubConfig, persistenceManager), FlowMeterModifyHubBolt.ID)
                .fieldsGrouping(ROUTER_BOLT_NAME, FlowMeterModifyHubBolt.INCOME_STREAM, FIELDS_KEY)
                .directGrouping(METER_MODIFY_WORKER_BOLT, FlowMeterModifyHubBolt.INCOME_STREAM)
                .directGrouping(CoordinatorBolt.ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);

        WorkerBolt.Config speakerMeterModifyWorkerConfig = WorkerBolt.Config.builder()
                .hubComponent(FlowMeterModifyHubBolt.ID)
                .streamToHub(FlowMeterModifyHubBolt.INCOME_STREAM)
                .workerSpoutComponent(ROUTER_BOLT_NAME)
                .defaultTimeout((int) TimeUnit.SECONDS.toMillis(topologyConfig.getOperationTimeout()))
                .build();
        declareBolt(tb, new SpeakerWorkerBolt(speakerMeterModifyWorkerConfig), METER_MODIFY_WORKER_BOLT)
                .fieldsGrouping(ROUTER_BOLT_NAME, SpeakerWorkerBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(FlowMeterModifyHubBolt.ID, StreamType.METER_MODIFY_WORKER.toString(), FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);

        SwitchOperationsBolt switchesBolt = new SwitchOperationsBolt(persistenceManager);
        declareBolt(tb, switchesBolt, SWITCHES_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.SWITCH.toString());

        LinkOperationsBolt linksBolt = new LinkOperationsBolt(persistenceManager);
        declareBolt(tb, linksBolt, LINKS_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.ISL.toString());

        FlowOperationsBolt flowsBolt = new FlowOperationsBolt(persistenceManager);
        declareBolt(tb, flowsBolt, FLOWS_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.FLOW.toString());

        FlowPatchBolt flowPatchBolt = new FlowPatchBolt(persistenceManager);
        declareBolt(tb, flowPatchBolt, FLOW_PATCH_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.FLOW_PATCH.toString());

        FeatureTogglesBolt featureTogglesBolt = new FeatureTogglesBolt(persistenceManager);
        declareBolt(tb, featureTogglesBolt, FEATURE_TOGGLES_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.FEATURE_TOGGLES.toString());

        KildaConfigurationBolt kildaConfigurationBolt = new KildaConfigurationBolt(persistenceManager);
        declareBolt(tb, kildaConfigurationBolt, KILDA_CONFIG_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.KILDA_CONFIG.toString());

        PathsBolt pathsBolt = new PathsBolt(persistenceManager, pathComputerConfig);
        declareBolt(tb, pathsBolt, PATHS_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.PATHS.toString());

        HistoryOperationsBolt historyBolt = new HistoryOperationsBolt(persistenceManager);
        declareBolt(tb, historyBolt, HISTORY_BOLT_NAME)
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.HISTORY.toString());

        ResponseSplitterBolt splitterBolt = new ResponseSplitterBolt();
        declareBolt(tb, splitterBolt, SPLITTER_BOLT_NAME)
                .shuffleGrouping(SWITCHES_BOLT_NAME)
                .shuffleGrouping(LINKS_BOLT_NAME)
                .shuffleGrouping(FLOWS_BOLT_NAME)
                .shuffleGrouping(FEATURE_TOGGLES_BOLT_NAME)
                .shuffleGrouping(KILDA_CONFIG_BOLT_NAME)
                .shuffleGrouping(PATHS_BOLT_NAME)
                .shuffleGrouping(HISTORY_BOLT_NAME)
                .shuffleGrouping(FlowValidationHubBolt.ID)
                .shuffleGrouping(FlowMeterModifyHubBolt.ID)
                .shuffleGrouping(FLOW_PATCH_BOLT_NAME);

        MessageEncoder messageEncoder = new MessageEncoder();
        declareBolt(tb, messageEncoder, MESSAGE_ENCODER_BOLT_NAME)
                .shuffleGrouping(LINKS_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(LINKS_BOLT_NAME, StreamType.REROUTE.toString())
                .shuffleGrouping(FLOWS_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(FLOWS_BOLT_NAME, StreamType.REROUTE.toString())
                .shuffleGrouping(SWITCHES_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(SWITCHES_BOLT_NAME, StreamType.REROUTE.toString())
                .shuffleGrouping(SWITCHES_BOLT_NAME, StreamType.TO_SWITCH_MANAGER.toString())
                .shuffleGrouping(ROUTER_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(FEATURE_TOGGLES_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(FEATURE_TOGGLES_BOLT_NAME, StreamType.TO_SWITCH_MANAGER.toString())
                .shuffleGrouping(KILDA_CONFIG_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(PATHS_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(HISTORY_BOLT_NAME, StreamType.ERROR.toString())
                .shuffleGrouping(FlowValidationHubBolt.ID, StreamType.ERROR.toString())
                .shuffleGrouping(FlowMeterModifyHubBolt.ID, StreamType.ERROR.toString())
                .shuffleGrouping(FLOW_PATCH_BOLT_NAME, StreamType.ERROR.toString());

        DiscoveryEncoderBolt discoveryEncoder = new DiscoveryEncoderBolt();
        declareBolt(tb, discoveryEncoder, DISCOVERY_ENCODER_BOLT_NAME)
                .shuffleGrouping(LINKS_BOLT_NAME, StreamType.DISCO.toString())
                .shuffleGrouping(SWITCHES_BOLT_NAME, StreamType.DISCO.toString())
                .shuffleGrouping(FEATURE_TOGGLES_BOLT_NAME, FeatureTogglesBolt.STREAM_NOTIFICATION_ID);

        KafkaBolt kafkaNbBolt = buildKafkaBolt(topologyConfig.getKafkaNorthboundTopic());
        declareBolt(tb, kafkaNbBolt, NB_KAFKA_BOLT_NAME)
                .shuffleGrouping(SPLITTER_BOLT_NAME)
                .shuffleGrouping(MESSAGE_ENCODER_BOLT_NAME, StreamType.ERROR.toString());

        KafkaBolt kafkaFlowHsBolt = buildKafkaBolt(topologyConfig.getKafkaFlowHsTopic());
        declareBolt(tb, kafkaFlowHsBolt, FLOW_HS_KAFKA_BOLT_NAME)
                .shuffleGrouping(MESSAGE_ENCODER_BOLT_NAME, StreamType.FLOWHS.toString())
                .shuffleGrouping(FLOW_PATCH_BOLT_NAME, StreamType.FLOWHS.toString());

        KafkaBolt kafkaDiscoBolt = buildKafkaBolt(topologyConfig.getKafkaDiscoTopic());
        declareBolt(tb, kafkaDiscoBolt, DISCO_KAFKA_BOLT_NAME)
                .shuffleGrouping(DISCOVERY_ENCODER_BOLT_NAME);

        KafkaBolt kafkaPingBolt = buildKafkaBolt(topologyConfig.getKafkaPingTopic());
        declareBolt(tb, kafkaPingBolt, PING_KAFKA_BOLT_NAME)
                .shuffleGrouping(FLOW_PATCH_BOLT_NAME, StreamType.PING.toString());

        declareBolt(tb, buildKafkaBolt(topologyConfig.getKafkaSpeakerTopic()), SPEAKER_KAFKA_BOLT)
                .shuffleGrouping(VALIDATION_WORKER_BOLT, StreamType.TO_SPEAKER.toString())
                .shuffleGrouping(METER_MODIFY_WORKER_BOLT, StreamType.TO_SPEAKER.toString());

        declareBolt(tb, buildKafkaBolt(topologyConfig.getKafkaSwitchManagerTopic()), SWITCH_MANAGER_KAFKA_BOLT)
                .shuffleGrouping(MESSAGE_ENCODER_BOLT_NAME, StreamType.TO_SWITCH_MANAGER.toString());

        Server42EncoderBolt server42EncoderBolt = new Server42EncoderBolt();
        declareBolt(tb, server42EncoderBolt, SERVER42_ENCODER_BOLT_NAME)
                .shuffleGrouping(SWITCHES_BOLT_NAME, StreamType.TO_SERVER42.toString())
                .shuffleGrouping(FEATURE_TOGGLES_BOLT_NAME, FeatureTogglesBolt.STREAM_NOTIFICATION_ID);

        declareBolt(tb, buildKafkaBolt(topologyConfig.getKafkaServer42StormNotifyTopic()), SERVER42_KAFKA_BOLT)
                .shuffleGrouping(SERVER42_ENCODER_BOLT_NAME);

        declareBolt(tb, buildKafkaBolt(topologyConfig.getKafkaRerouteTopic()), REROUTE_KAFKA_BOLT)
                .shuffleGrouping(MESSAGE_ENCODER_BOLT_NAME, StreamType.REROUTE.toString());

        metrics(tb);

        return tb.createTopology();
    }

    @Override
    protected String getZkTopoName() {
        return "nbworker";
    }

    private void metrics(TopologyBuilder topologyBuilder) {
        String openTsdbTopic = topologyConfig.getKafkaTopics().getOtsdbTopic();
        KafkaBolt kafkaBolt = createKafkaBolt(openTsdbTopic);
        topologyBuilder.setBolt(METRICS_BOLT, kafkaBolt)
                .shuffleGrouping(FLOWS_BOLT_NAME, StreamType.TO_METRICS_BOLT.name())
                .shuffleGrouping(FlowValidationHubBolt.ID, StreamType.TO_METRICS_BOLT.name())
                .shuffleGrouping(LINKS_BOLT_NAME, StreamType.TO_METRICS_BOLT.name())
                .shuffleGrouping(SWITCHES_BOLT_NAME, StreamType.TO_METRICS_BOLT.name())
                .shuffleGrouping(HISTORY_BOLT_NAME, StreamType.TO_METRICS_BOLT.name());
    }

    /**
     * Launches and sets up the workflow manager environment.
     *
     * @param args the command-line arguments.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            new NbWorkerTopology(env).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }

}
