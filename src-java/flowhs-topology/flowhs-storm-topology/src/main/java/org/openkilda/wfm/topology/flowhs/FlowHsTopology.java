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

package org.openkilda.wfm.topology.flowhs;

import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_CREATE_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_DELETE_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_PATH_SWAP_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_REROUTE_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_SWAP_ENDPOINTS_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.ROUTER_TO_FLOW_UPDATE_HUB;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_TO_HUB_CREATE;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_TO_HUB_DELETE;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_TO_HUB_PATH_SWAP;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_TO_HUB_REROUTE;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SPEAKER_WORKER_TO_HUB_UPDATE;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.SWAP_ENDPOINTS_HUB_TO_ROUTER_BOLT;
import static org.openkilda.wfm.topology.flowhs.FlowHsTopology.Stream.UPDATE_HUB_TO_SWAP_ENDPOINTS_HUB;
import static org.openkilda.wfm.topology.flowhs.bolts.RouterBolt.FLOW_ID_FIELD;

import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.kafka.AbstractMessageSerializer;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.history.bolt.HistoryBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorBolt;
import org.openkilda.wfm.share.hubandspoke.CoordinatorSpout;
import org.openkilda.wfm.share.hubandspoke.HubBolt;
import org.openkilda.wfm.share.hubandspoke.WorkerBolt.Config;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.flowhs.bolts.FlowCreateHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.FlowCreateHubBolt.FlowCreateConfig;
import org.openkilda.wfm.topology.flowhs.bolts.FlowDeleteHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.FlowDeleteHubBolt.FlowDeleteConfig;
import org.openkilda.wfm.topology.flowhs.bolts.FlowPathSwapHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.FlowPathSwapHubBolt.FlowPathSwapConfig;
import org.openkilda.wfm.topology.flowhs.bolts.FlowRerouteHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.FlowRerouteHubBolt.FlowRerouteConfig;
import org.openkilda.wfm.topology.flowhs.bolts.FlowSwapEndpointsHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.FlowUpdateHubBolt;
import org.openkilda.wfm.topology.flowhs.bolts.FlowUpdateHubBolt.FlowUpdateConfig;
import org.openkilda.wfm.topology.flowhs.bolts.RouterBolt;
import org.openkilda.wfm.topology.flowhs.bolts.SpeakerWorkerBolt;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.concurrent.TimeUnit;

public class FlowHsTopology extends AbstractTopology<FlowHsTopologyConfig> {
    private static final Fields FLOW_FIELD = new Fields(FLOW_ID_FIELD);

    private int parallelism;
    private int workerParallelism;

    public FlowHsTopology(LaunchEnvironment env) {
        super(env, FlowHsTopologyConfig.class);

        parallelism = topologyConfig.getFlowHsParallelism();
        workerParallelism = topologyConfig.getFlowHsWorkerParallelism();
    }

    @Override
    protected org.apache.storm.Config makeStormConfig() {
        org.apache.storm.Config stormConfig = super.makeStormConfig();
        stormConfig.setNumWorkers(1);
        return stormConfig;
    }

    @Override
    public StormTopology createTopology() {
        TopologyBuilder tb = new TopologyBuilder();

        inputSpout(tb);
        inputRouter(tb);

        PersistenceManager persistenceManager =
                PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);

        flowCreateHub(tb, persistenceManager);
        flowUpdateHub(tb, persistenceManager);
        flowRerouteHub(tb, persistenceManager);
        flowDeleteHub(tb, persistenceManager);
        flowSwapProtectedHub(tb, persistenceManager);
        flowSwapEndpointsHub(tb, persistenceManager);

        speakerSpout(tb);
        flowCreateSpeakerWorker(tb);
        flowUpdateSpeakerWorker(tb);
        flowRerouteSpeakerWorker(tb);
        flowDeleteSpeakerWorker(tb);
        flowPathSwapSpeakerWorker(tb);
        speakerOutput(tb);

        coordinator(tb);

        northboundOutput(tb);
        rerouteTopologyOutput(tb);
        pingOutput(tb);
        server42ControlTopologyOutput(tb);

        history(tb, persistenceManager);

        return tb.createTopology();
    }

    private void inputSpout(TopologyBuilder topologyBuilder) {
        KafkaSpout<String, Message> mainSpout = buildKafkaSpout(getConfig().getKafkaFlowHsTopic(),
                ComponentId.FLOW_SPOUT.name());
        topologyBuilder.setSpout(ComponentId.FLOW_SPOUT.name(), mainSpout, parallelism);
    }

    private void inputRouter(TopologyBuilder topologyBuilder) {
        topologyBuilder.setBolt(ComponentId.FLOW_ROUTER_BOLT.name(), new RouterBolt(), parallelism)
                .shuffleGrouping(ComponentId.FLOW_SPOUT.name())
                .shuffleGrouping(ComponentId.FLOW_SWAP_ENDPOINTS_HUB.name(), SWAP_ENDPOINTS_HUB_TO_ROUTER_BOLT.name());
    }

    private void flowCreateHub(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        int hubTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getCreateHubTimeoutSeconds());

        FlowCreateConfig config = FlowCreateConfig.flowCreateBuilder()
                .flowCreationRetriesLimit(topologyConfig.getCreateHubRetries())
                .pathAllocationRetriesLimit(topologyConfig.getPathAllocationRetriesLimit())
                .pathAllocationRetryDelay(topologyConfig.getPathAllocationRetryDelay())
                .speakerCommandRetriesLimit(topologyConfig.getCreateSpeakerCommandRetries())
                .autoAck(true)
                .timeoutMs(hubTimeout)
                .requestSenderComponent(ComponentId.FLOW_ROUTER_BOLT.name())
                .workerComponent(ComponentId.FLOW_CREATE_SPEAKER_WORKER.name())
                .build();

        PathComputerConfig pathComputerConfig = configurationProvider.getConfiguration(PathComputerConfig.class);
        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        FlowCreateHubBolt hubBolt = new FlowCreateHubBolt(config, persistenceManager, pathComputerConfig,
                flowResourcesConfig);
        topologyBuilder.setBolt(ComponentId.FLOW_CREATE_HUB.name(), hubBolt, parallelism)
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_CREATE_HUB.name(), FLOW_FIELD)
                .directGrouping(ComponentId.FLOW_CREATE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_TO_HUB_CREATE.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowUpdateHub(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        int hubTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getUpdateHubTimeoutSeconds());

        FlowUpdateConfig config = FlowUpdateConfig.flowUpdateBuilder()
                .pathAllocationRetriesLimit(topologyConfig.getPathAllocationRetriesLimit())
                .pathAllocationRetryDelay(topologyConfig.getPathAllocationRetryDelay())
                .speakerCommandRetriesLimit(topologyConfig.getUpdateSpeakerCommandRetries())
                .autoAck(true)
                .timeoutMs(hubTimeout)
                .requestSenderComponent(ComponentId.FLOW_ROUTER_BOLT.name())
                .workerComponent(ComponentId.FLOW_UPDATE_SPEAKER_WORKER.name())
                .build();

        PathComputerConfig pathComputerConfig = configurationProvider.getConfiguration(PathComputerConfig.class);
        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        FlowUpdateHubBolt hubBolt = new FlowUpdateHubBolt(config, persistenceManager, pathComputerConfig,
                flowResourcesConfig);
        topologyBuilder.setBolt(ComponentId.FLOW_UPDATE_HUB.name(), hubBolt, parallelism)
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_UPDATE_HUB.name(), FLOW_FIELD)
                .directGrouping(ComponentId.FLOW_UPDATE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_TO_HUB_UPDATE.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowSwapProtectedHub(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        int hubTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getPathSwapHubTimeoutSeconds());

        FlowPathSwapConfig config = FlowPathSwapConfig.flowPathSwapBuilder()
                .speakerCommandRetriesLimit(topologyConfig.getPathSwapSpeakerCommandRetries())
                .autoAck(true)
                .timeoutMs(hubTimeout)
                .requestSenderComponent(ComponentId.FLOW_ROUTER_BOLT.name())
                .workerComponent(ComponentId.FLOW_PATH_SWAP_SPEAKER_WORKER.name())
                .build();

        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        FlowPathSwapHubBolt hubBolt = new FlowPathSwapHubBolt(config, persistenceManager, flowResourcesConfig);
        topologyBuilder.setBolt(ComponentId.FLOW_PATH_SWAP_HUB.name(), hubBolt, parallelism)
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_PATH_SWAP_HUB.name(), FLOW_FIELD)
                .directGrouping(ComponentId.FLOW_PATH_SWAP_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_TO_HUB_PATH_SWAP.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowRerouteHub(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        int hubTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getRerouteHubTimeoutSeconds());

        FlowRerouteConfig config = FlowRerouteConfig.flowRerouteBuilder()
                .pathAllocationRetriesLimit(topologyConfig.getPathAllocationRetriesLimit())
                .pathAllocationRetryDelay(topologyConfig.getPathAllocationRetryDelay())
                .speakerCommandRetriesLimit(topologyConfig.getRerouteSpeakerCommandRetries())
                .autoAck(true)
                .timeoutMs(hubTimeout)
                .requestSenderComponent(ComponentId.FLOW_ROUTER_BOLT.name())
                .workerComponent(ComponentId.FLOW_REROUTE_SPEAKER_WORKER.name())
                .build();

        PathComputerConfig pathComputerConfig = configurationProvider.getConfiguration(PathComputerConfig.class);
        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        FlowRerouteHubBolt hubBolt = new FlowRerouteHubBolt(config, persistenceManager, pathComputerConfig,
                flowResourcesConfig);
        topologyBuilder.setBolt(ComponentId.FLOW_REROUTE_HUB.name(), hubBolt, parallelism)
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_REROUTE_HUB.name(), FLOW_FIELD)
                .directGrouping(ComponentId.FLOW_REROUTE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_TO_HUB_REROUTE.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowDeleteHub(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        int hubTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getDeleteHubTimeoutSeconds());

        FlowDeleteConfig config = FlowDeleteConfig.flowDeleteBuilder()
                .speakerCommandRetriesLimit(topologyConfig.getDeleteSpeakerCommandRetries())
                .autoAck(true)
                .timeoutMs(hubTimeout)
                .requestSenderComponent(ComponentId.FLOW_ROUTER_BOLT.name())
                .workerComponent(ComponentId.FLOW_DELETE_SPEAKER_WORKER.name())
                .build();

        FlowResourcesConfig flowResourcesConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        FlowDeleteHubBolt hubBolt = new FlowDeleteHubBolt(config, persistenceManager, flowResourcesConfig);
        topologyBuilder.setBolt(ComponentId.FLOW_DELETE_HUB.name(), hubBolt, parallelism)
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(), ROUTER_TO_FLOW_DELETE_HUB.name(), FLOW_FIELD)
                .directGrouping(ComponentId.FLOW_DELETE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_TO_HUB_DELETE.name())
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowSwapEndpointsHub(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        int hubTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getSwapEndpointsHubTimeoutSeconds());

        HubBolt.Config config = HubBolt.Config.builder()
                .requestSenderComponent(ComponentId.FLOW_ROUTER_BOLT.name())
                .workerComponent(ComponentId.FLOW_UPDATE_HUB.name())
                .timeoutMs(hubTimeout)
                .build();

        FlowSwapEndpointsHubBolt hubBolt = new FlowSwapEndpointsHubBolt(config, persistenceManager);
        topologyBuilder.setBolt(ComponentId.FLOW_SWAP_ENDPOINTS_HUB.name(), hubBolt, parallelism)
                .fieldsGrouping(ComponentId.FLOW_ROUTER_BOLT.name(),
                        ROUTER_TO_FLOW_SWAP_ENDPOINTS_HUB.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_UPDATE_HUB.name(),
                        UPDATE_HUB_TO_SWAP_ENDPOINTS_HUB.name(), FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void speakerSpout(TopologyBuilder topologyBuilder) {
        KafkaSpout<String, AbstractMessage> flWorkerSpout = buildKafkaSpoutForAbstractMessage(
                getConfig().getKafkaFlowSpeakerWorkerTopic(),
                ComponentId.SPEAKER_WORKER_SPOUT.name());
        topologyBuilder.setSpout(ComponentId.SPEAKER_WORKER_SPOUT.name(), flWorkerSpout, parallelism);
    }

    private void flowCreateSpeakerWorker(TopologyBuilder topologyBuilder) {
        int speakerTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getCreateSpeakerTimeoutSeconds());
        SpeakerWorkerBolt speakerWorker = new SpeakerWorkerBolt(Config.builder()
                .autoAck(true)
                .defaultTimeout(speakerTimeout)
                .workerSpoutComponent(ComponentId.SPEAKER_WORKER_SPOUT.name())
                .hubComponent(ComponentId.FLOW_CREATE_HUB.name())
                .streamToHub(SPEAKER_WORKER_TO_HUB_CREATE.name())
                .build());
        topologyBuilder.setBolt(ComponentId.FLOW_CREATE_SPEAKER_WORKER.name(), speakerWorker, workerParallelism)
                .fieldsGrouping(ComponentId.SPEAKER_WORKER_SPOUT.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_CREATE_HUB.name(), Stream.HUB_TO_SPEAKER_WORKER.name(),
                        FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowUpdateSpeakerWorker(TopologyBuilder topologyBuilder) {
        int speakerTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getUpdateSpeakerTimeoutSeconds());
        SpeakerWorkerBolt speakerWorker = new SpeakerWorkerBolt(Config.builder()
                .autoAck(true)
                .defaultTimeout(speakerTimeout)
                .workerSpoutComponent(ComponentId.SPEAKER_WORKER_SPOUT.name())
                .hubComponent(ComponentId.FLOW_UPDATE_HUB.name())
                .streamToHub(SPEAKER_WORKER_TO_HUB_UPDATE.name())
                .build());
        topologyBuilder.setBolt(ComponentId.FLOW_UPDATE_SPEAKER_WORKER.name(), speakerWorker, parallelism)
                .fieldsGrouping(ComponentId.SPEAKER_WORKER_SPOUT.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_UPDATE_HUB.name(), Stream.HUB_TO_SPEAKER_WORKER.name(),
                        FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowPathSwapSpeakerWorker(TopologyBuilder topologyBuilder) {
        int speakerTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getPathSwapSpeakerTimeoutSeconds());
        SpeakerWorkerBolt speakerWorker = new SpeakerWorkerBolt(Config.builder()
                .autoAck(true)
                .defaultTimeout(speakerTimeout)
                .workerSpoutComponent(ComponentId.SPEAKER_WORKER_SPOUT.name())
                .hubComponent(ComponentId.FLOW_PATH_SWAP_HUB.name())
                .streamToHub(SPEAKER_WORKER_TO_HUB_PATH_SWAP.name())
                .build());
        topologyBuilder.setBolt(ComponentId.FLOW_PATH_SWAP_SPEAKER_WORKER.name(), speakerWorker, parallelism)
                .fieldsGrouping(ComponentId.SPEAKER_WORKER_SPOUT.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_PATH_SWAP_HUB.name(), Stream.HUB_TO_SPEAKER_WORKER.name(),
                        FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowRerouteSpeakerWorker(TopologyBuilder topologyBuilder) {
        int speakerTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getRerouteSpeakerTimeoutSeconds());
        SpeakerWorkerBolt speakerWorker = new SpeakerWorkerBolt(Config.builder()
                .autoAck(true)
                .defaultTimeout(speakerTimeout)
                .workerSpoutComponent(ComponentId.SPEAKER_WORKER_SPOUT.name())
                .hubComponent(ComponentId.FLOW_REROUTE_HUB.name())
                .streamToHub(SPEAKER_WORKER_TO_HUB_REROUTE.name())
                .build());
        topologyBuilder.setBolt(ComponentId.FLOW_REROUTE_SPEAKER_WORKER.name(), speakerWorker, workerParallelism)
                .fieldsGrouping(ComponentId.SPEAKER_WORKER_SPOUT.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_REROUTE_HUB.name(), Stream.HUB_TO_SPEAKER_WORKER.name(),
                        FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void flowDeleteSpeakerWorker(TopologyBuilder topologyBuilder) {
        int speakerTimeout = (int) TimeUnit.SECONDS.toMillis(topologyConfig.getDeleteSpeakerTimeoutSeconds());
        SpeakerWorkerBolt speakerWorker = new SpeakerWorkerBolt(Config.builder()
                .autoAck(true)
                .defaultTimeout(speakerTimeout)
                .workerSpoutComponent(ComponentId.SPEAKER_WORKER_SPOUT.name())
                .hubComponent(ComponentId.FLOW_DELETE_HUB.name())
                .streamToHub(SPEAKER_WORKER_TO_HUB_DELETE.name())
                .build());
        topologyBuilder.setBolt(ComponentId.FLOW_DELETE_SPEAKER_WORKER.name(), speakerWorker, parallelism)
                .fieldsGrouping(ComponentId.SPEAKER_WORKER_SPOUT.name(), FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_DELETE_HUB.name(), Stream.HUB_TO_SPEAKER_WORKER.name(),
                        FIELDS_KEY)
                .directGrouping(CoordinatorBolt.ID);
    }

    private void speakerOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt<String, AbstractMessage> flKafkaBolt = makeKafkaBolt(
                getConfig().getKafkaSpeakerFlowTopic(), AbstractMessageSerializer.class);
        topologyBuilder.setBolt(ComponentId.SPEAKER_REQUEST_SENDER.name(), flKafkaBolt, parallelism)
                .shuffleGrouping(ComponentId.FLOW_CREATE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_REQUEST_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_UPDATE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_REQUEST_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_REROUTE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_REQUEST_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_DELETE_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_REQUEST_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_PATH_SWAP_SPEAKER_WORKER.name(),
                        Stream.SPEAKER_WORKER_REQUEST_SENDER.name());
    }

    private void coordinator(TopologyBuilder topologyBuilder) {
        topologyBuilder.setSpout(CoordinatorSpout.ID, new CoordinatorSpout());
        topologyBuilder.setBolt(CoordinatorBolt.ID, new CoordinatorBolt(), parallelism)
                .allGrouping(CoordinatorSpout.ID)
                .fieldsGrouping(ComponentId.FLOW_CREATE_SPEAKER_WORKER.name(),
                        CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_UPDATE_SPEAKER_WORKER.name(),
                        CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_PATH_SWAP_SPEAKER_WORKER.name(),
                        CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_REROUTE_SPEAKER_WORKER.name(),
                        CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_DELETE_SPEAKER_WORKER.name(),
                        CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_CREATE_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_UPDATE_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_PATH_SWAP_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_REROUTE_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_DELETE_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY)
                .fieldsGrouping(ComponentId.FLOW_SWAP_ENDPOINTS_HUB.name(), CoordinatorBolt.INCOME_STREAM, FIELDS_KEY);
    }

    private void northboundOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt nbKafkaBolt = buildKafkaBolt(getConfig().getKafkaNorthboundTopic());
        topologyBuilder.setBolt(ComponentId.NB_RESPONSE_SENDER.name(), nbKafkaBolt, parallelism)
                .shuffleGrouping(ComponentId.FLOW_CREATE_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_UPDATE_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_PATH_SWAP_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_REROUTE_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_DELETE_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_SWAP_ENDPOINTS_HUB.name(), Stream.HUB_TO_NB_RESPONSE_SENDER.name());
    }

    private void rerouteTopologyOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt rerouteKafkaBolt = buildKafkaBolt(getConfig().getKafkaRerouteTopic());
        topologyBuilder.setBolt(ComponentId.REROUTE_RESPONSE_SENDER.name(), rerouteKafkaBolt, parallelism)
                .shuffleGrouping(ComponentId.FLOW_REROUTE_HUB.name(), Stream.HUB_TO_REROUTE_RESPONSE_SENDER.name());
    }

    private void pingOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt pingKafkaBolt = buildKafkaBolt(getConfig().getKafkaPingTopic());
        topologyBuilder.setBolt(ComponentId.FLOW_PING_SENDER.name(), pingKafkaBolt, parallelism)
                .shuffleGrouping(ComponentId.FLOW_CREATE_HUB.name(), Stream.HUB_TO_PING_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_UPDATE_HUB.name(), Stream.HUB_TO_PING_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_DELETE_HUB.name(), Stream.HUB_TO_PING_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_REROUTE_HUB.name(), Stream.HUB_TO_PING_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_PATH_SWAP_HUB.name(), Stream.HUB_TO_PING_SENDER.name());
    }

    private void server42ControlTopologyOutput(TopologyBuilder topologyBuilder) {
        KafkaBolt server42ControlKafkaBolt = buildKafkaBolt(getConfig().getKafkaFlowHsServer42StormNotifyTopic());
        topologyBuilder.setBolt(ComponentId.SERVER42_CONTROL_TOPOLOGY_SENDER.name(),
                server42ControlKafkaBolt, parallelism)
                .shuffleGrouping(ComponentId.FLOW_CREATE_HUB.name(),
                        Stream.HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_UPDATE_HUB.name(),
                        Stream.HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name())
                .shuffleGrouping(ComponentId.FLOW_DELETE_HUB.name(),
                        Stream.HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER.name());
    }

    private void history(TopologyBuilder topologyBuilder, PersistenceManager persistenceManager) {
        HistoryBolt historyBolt = new HistoryBolt(persistenceManager);
        topologyBuilder.setBolt(ComponentId.HISTORY_BOLT.name(), historyBolt, parallelism)
                .shuffleGrouping(ComponentId.FLOW_CREATE_HUB.name(), Stream.HUB_TO_HISTORY_BOLT.name())
                .shuffleGrouping(ComponentId.FLOW_UPDATE_HUB.name(), Stream.HUB_TO_HISTORY_BOLT.name())
                .shuffleGrouping(ComponentId.FLOW_REROUTE_HUB.name(), Stream.HUB_TO_HISTORY_BOLT.name())
                .shuffleGrouping(ComponentId.FLOW_DELETE_HUB.name(), Stream.HUB_TO_HISTORY_BOLT.name())
                .shuffleGrouping(ComponentId.FLOW_PATH_SWAP_HUB.name(), Stream.HUB_TO_HISTORY_BOLT.name())
                .shuffleGrouping(ComponentId.FLOW_SWAP_ENDPOINTS_HUB.name(), Stream.HUB_TO_HISTORY_BOLT.name());
    }

    public enum ComponentId {
        FLOW_SPOUT("flow.spout"),
        SPEAKER_WORKER_SPOUT("fl.worker.spout"),

        FLOW_ROUTER_BOLT("flow.router.bolt"),
        FLOW_CREATE_HUB("flow.create.hub.bolt"),
        FLOW_UPDATE_HUB("flow.update.hub.bolt"),
        FLOW_PATH_SWAP_HUB("flow.pathswap.hub.bolt"),
        FLOW_REROUTE_HUB("flow.reroute.hub.bolt"),
        FLOW_DELETE_HUB("flow.delete.hub.bolt"),
        FLOW_SWAP_ENDPOINTS_HUB("flow.swap.endpoints.hub.bolt"),

        FLOW_CREATE_SPEAKER_WORKER("flow.create.worker.bolt"),
        FLOW_UPDATE_SPEAKER_WORKER("flow.update.worker.bolt"),
        FLOW_PATH_SWAP_SPEAKER_WORKER("flow.pathswap.worker.bolt"),
        FLOW_REROUTE_SPEAKER_WORKER("flow.reroute.worker.bolt"),
        FLOW_DELETE_SPEAKER_WORKER("flow.delete.worker.bolt"),

        NB_RESPONSE_SENDER("nb.kafka.bolt"),
        REROUTE_RESPONSE_SENDER("reroute.kafka.bolt"),
        RESPONSE_SENDER("response.kafka.bolt"),
        FLOW_PING_SENDER("ping.kafka.bolt"),
        SERVER42_CONTROL_TOPOLOGY_SENDER("server42.control.kafka.bolt"),

        SPEAKER_REQUEST_SENDER("speaker.kafka.bolt"),

        HISTORY_BOLT("flow.history.bolt");

        private final String value;

        ComponentId(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }

    }

    public enum Stream {
        ROUTER_TO_FLOW_CREATE_HUB,
        ROUTER_TO_FLOW_UPDATE_HUB,
        ROUTER_TO_FLOW_PATH_SWAP_HUB,
        ROUTER_TO_FLOW_REROUTE_HUB,
        ROUTER_TO_FLOW_DELETE_HUB,
        ROUTER_TO_FLOW_SWAP_ENDPOINTS_HUB,

        HUB_TO_SPEAKER_WORKER,
        HUB_TO_HISTORY_BOLT,

        SPEAKER_WORKER_TO_HUB_CREATE,
        SPEAKER_WORKER_TO_HUB_UPDATE,
        SPEAKER_WORKER_TO_HUB_REROUTE,
        SPEAKER_WORKER_TO_HUB_DELETE,
        SPEAKER_WORKER_TO_HUB_PATH_SWAP,

        SWAP_ENDPOINTS_HUB_TO_ROUTER_BOLT,
        UPDATE_HUB_TO_SWAP_ENDPOINTS_HUB,

        SPEAKER_WORKER_REQUEST_SENDER,
        HUB_TO_NB_RESPONSE_SENDER,
        HUB_TO_REROUTE_RESPONSE_SENDER,
        HUB_TO_RESPONSE_SENDER,
        HUB_TO_PING_SENDER,
        HUB_TO_SERVER42_CONTROL_TOPOLOGY_SENDER
    }

    /**
     * Launches and sets up the topology.
     *
     * @param args the command-line arguments.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            new FlowHsTopology(env).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
