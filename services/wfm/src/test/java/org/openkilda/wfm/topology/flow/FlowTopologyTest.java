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

package org.openkilda.wfm.topology.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.BaseFlow;
import org.openkilda.messaging.command.flow.FlowCreateRequest;
import org.openkilda.messaging.command.flow.FlowDeleteRequest;
import org.openkilda.messaging.command.flow.FlowReadRequest;
import org.openkilda.messaging.command.flow.FlowUpdateRequest;
import org.openkilda.messaging.command.flow.FlowsDumpRequest;
import org.openkilda.messaging.command.flow.InstallOneSwitchFlow;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.messaging.ctrl.CtrlRequest;
import org.openkilda.messaging.ctrl.CtrlResponse;
import org.openkilda.messaging.ctrl.DumpStateResponseData;
import org.openkilda.messaging.ctrl.RequestData;
import org.openkilda.messaging.ctrl.ResponseData;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.rule.FlowCommandErrorData;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.PathInfoData;
import org.openkilda.messaging.info.flow.FlowInfoData;
import org.openkilda.messaging.info.flow.FlowReadResponse;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.model.DetectConnectedDevicesDto;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.model.FlowPairDto;
import org.openkilda.messaging.payload.flow.FlowEncapsulationType;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Metadata;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.Neo4jConfig;
import org.openkilda.persistence.Neo4jPersistenceManager;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.impl.Neo4jSessionFactory;
import org.openkilda.wfm.AbstractStormTest;
import org.openkilda.wfm.EmbeddedNeo4jDatabase;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.TestKafkaConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.storm.Config;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.utils.Utils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class FlowTopologyTest extends AbstractStormTest {
    private static final long FLOW_STATUS_UPDATE_TIMEOUT_MS = 5000;

    private static final long COOKIE = 0x1FFFFFFFFL;
    private static final UUID TRANSACTION_ID = UUID.randomUUID();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static TestKafkaConsumer nbConsumer;
    private static TestKafkaConsumer ofsConsumer;
    private static TestKafkaConsumer ctrlConsumer;
    private static FlowTopology flowTopology;
    private static FlowTopologyConfig topologyConfig;

    private static EmbeddedNeo4jDatabase embeddedNeo4jDb;
    private static PersistenceManager persistenceManager;

    @BeforeClass
    public static void setupOnce() throws Exception {
        AbstractStormTest.startZooKafkaAndStorm();

        embeddedNeo4jDb = new EmbeddedNeo4jDatabase(fsData.getRoot());

        LaunchEnvironment launchEnvironment = makeLaunchEnvironment();
        Properties configOverlay = new Properties();
        configOverlay.setProperty("neo4j.uri", embeddedNeo4jDb.getConnectionUri());
        configOverlay.setProperty("neo4j.indexes.auto", "update"); // ask to create indexes/constraints if needed
        launchEnvironment.setupOverlay(configOverlay);

        Neo4jConfig neo4jConfig = launchEnvironment.getConfigurationProvider().getConfiguration(Neo4jConfig.class);
        NetworkConfig networkConfig
                = launchEnvironment.getConfigurationProvider().getConfiguration(NetworkConfig.class);
        persistenceManager = new Neo4jPersistenceManager(neo4jConfig, networkConfig);

        flowTopology = new FlowTopology(launchEnvironment);
        topologyConfig = flowTopology.getConfig();

        StormTopology stormTopology = flowTopology.createTopology();
        Config config = stormConfig();
        cluster.submitTopology(FlowTopologyTest.class.getSimpleName(), config, stormTopology);

        nbConsumer = new TestKafkaConsumer(
                topologyConfig.getKafkaNorthboundTopic(), Destination.NORTHBOUND,
                kafkaProperties(UUID.nameUUIDFromBytes(Destination.NORTHBOUND.toString().getBytes()).toString()));
        nbConsumer.start();

        ofsConsumer = new TestKafkaConsumer(topologyConfig.getKafkaSpeakerFlowTopic(),
                Destination.CONTROLLER,
                kafkaProperties(UUID.nameUUIDFromBytes(Destination.CONTROLLER.toString().getBytes()).toString()));
        ofsConsumer.start();

        ctrlConsumer = new TestKafkaConsumer(flowTopology.getConfig().getKafkaCtrlTopic(), Destination.CTRL_CLIENT,
                kafkaProperties(UUID.nameUUIDFromBytes(Destination.CTRL_CLIENT.toString().getBytes()).toString()));
        ctrlConsumer.start();

        Utils.sleep(TOPOLOGY_START_TIMEOUT);
    }

    @AfterClass
    public static void teardownOnce() throws Exception {
        nbConsumer.wakeup();
        nbConsumer.join();
        ofsConsumer.wakeup();
        ofsConsumer.join();

        embeddedNeo4jDb.stop();

        AbstractStormTest.stopZooKafkaAndStorm();
    }

    @Before
    public void setup() throws IOException, InterruptedException {
        nbConsumer.clear();
        ofsConsumer.clear();


        // Clean the CrudBolt's state.
        sendClearState();

        ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession().purgeDatabase();

        persistenceManager.getRepositoryFactory().createFeatureTogglesRepository().createOrUpdate(
                FeatureToggles.builder()
                        .createFlowEnabled(true)
                        .updateFlowEnabled(true)
                        .deleteFlowEnabled(true)
                        .pushFlowEnabled(true)
                        .unpushFlowEnabled(true)
                        .build());
    }

    @Test
    public void createFlowCommandBoltTest() throws Exception {
        ConsumerRecord<String, String> record;
        String flowId = UUID.randomUUID().toString();

        createFlow(flowId);
        for (int i = 0; i < 4; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        FlowResponse response = (FlowResponse) infoMessage.getData();
        assertNotNull(response);
    }

    @Test
    public void createAlreadyExistsFlowCommandBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        createFlow(flowId);

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.ALREADY_EXISTS);
    }

    @Test
    public void shouldFailOnCreatingConflictingFlow() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        createFlow(flowId + "_alt");

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.ALREADY_EXISTS);
    }

    @Test
    public void deleteFlowCommandBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);


        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        FlowDto payload = deleteFlow(flowId);
        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        System.out.println("record = " + record);
        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        FlowResponse response = (FlowResponse) infoMessage.getData();
        assertNotNull(response);
    }

    @Test
    public void deleteUnknownFlowCommandBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        deleteFlow(flowId);

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.NOT_FOUND);
    }

    @Test
    public void updateFlowCommandBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 4; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        updateFlow(flowId);
        for (int i = 0; i < 8; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        FlowResponse payload = (FlowResponse) infoMessage.getData();
        assertNotNull(payload);
    }

    @Test
    public void updateUnknownFlowCommandBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createSwitchIfNotExist("ff:00");
        createSwitchIfNotExist("ff:01");
        createIslIfNotExist("ff:00", "ff:01");
        updateFlow(flowId);

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.NOT_FOUND);
    }

    @Test
    public void statusFlowTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        statusFlow(flowId);

        record = nbConsumer.pollMessage();
        assertEquals(FlowState.IN_PROGRESS, getFlowReadStatus(record, flowId));
    }

    @Test
    public void statusUnknownFlowTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        statusFlow(flowId);

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.NOT_FOUND);
    }

    @Test
    public void pathFlowTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 4; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        pathFlow(flowId);

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        FlowReadResponse infoData = (FlowReadResponse) infoMessage.getData();
        assertNotNull(infoData);
    }

    @Test
    public void pathUnknownFlowTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        pathFlow(flowId);

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.NOT_FOUND);
    }

    @Test
    public void getFlowTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        FlowDto flow = createFlow(flowId);
        flow.setCookie(1);
        flow.setMeterId(1);
        flow.setTransitEncapsulationId(2);
        flow.setState(FlowState.IN_PROGRESS);
        flow.setEncapsulationType(FlowEncapsulationType.TRANSIT_VLAN);
        flow.setPathComputationStrategy(PathComputationStrategy.COST);

        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        getFlow(flowId);

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        FlowReadResponse infoData = (FlowReadResponse) infoMessage.getData();
        assertNotNull(infoData);

        FlowDto flowTePayload = infoData.getPayload();
        assertEquals(flow, flowTePayload);
    }

    @Test
    public void getUnknownFlowTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        getFlow(flowId);

        record = nbConsumer.pollMessage();
        checkErrorResponseType(record, ErrorType.NOT_FOUND);
    }

    @Test
    public void dumpFlowsTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 2; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        dumpFlows();

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        FlowReadResponse infoData = (FlowReadResponse) infoMessage.getData();
        assertNotNull(infoData);
        assertNotNull(infoData.getPayload());
        assertEquals(flowId, infoData.getPayload().getFlowId());
    }

    @Test
    public void dumpFlowsWhenThereIsNoFlowsCreated() throws Exception {
        dumpFlows();

        ConsumerRecord<String, String> record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage infoMessage = objectMapper.readValue(record.value(), InfoMessage.class);
        assertNull(infoMessage.getData());
    }

    @Test
    public void installFlowTopologyEngineSpeakerBoltTest() throws Exception {
        /*
         * This test will verify the state transitions of a flow, through the status mechanism.
         * It achieves this by doing the following:
         *      - CreateFlow .. clear both cache and northbound consumers
         *      - GetStatus .. confirm STATE = FlowState.ALLOCATED
         *      - baseInstallFlowCommand .. read speaker .. validate data/responsedata
         *      - GetStatus .. confirm STATE = FlowState.IN_PROGRESS
         *      - baseInstallRuleCommand ..
         *      - GetStatus .. confirm STATE = FlowState.UP
         */
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        for (int i = 0; i < 4; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            assertNotNull(record.value());

            CommandMessage response = objectMapper.readValue(record.value(), CommandMessage.class);
            response.setDestination(Destination.WFM_TRANSACTION);

            assertNotNull(response);
            sendFlowMessage(response);

        }


        statusFlow(flowId);

        record = nbConsumer.pollMessage();
        assertEquals(FlowState.UP, getFlowReadStatus(record, flowId));
    }

    @Test
    public void removeFlowTopologyEngineSpeakerBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> ofsRecord;
        ConsumerRecord<String, String> record;

        createFlow(flowId);
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        for (int i = 0; i < 4; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            commandMessage.setDestination(Destination.WFM_TRANSACTION);

            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());
            sendFlowMessage(commandMessage);

        }

        statusFlow(flowId);

        record = nbConsumer.pollMessage();
        assertEquals(FlowState.UP, getFlowReadStatus(record, flowId));

    }

    @Test
    public void errorMessageStatusBoltSpeakerBoltTest() throws Exception {
        String flowId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record;

        createFlow(flowId);

        for (int i = 0; i < 3; i++) {
            record = ofsConsumer.pollMessage();
            assertNotNull(record);
            CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
            assertNotNull(commandMessage);
            assertNotNull(commandMessage.getData());

            commandMessage.setDestination(Destination.WFM_TRANSACTION);
            sendFlowMessage(commandMessage);
        }
        record = nbConsumer.pollMessage();
        assertNotNull(record);
        assertNotNull(record.value());

        statusFlow(flowId);

        record = nbConsumer.pollMessage();
        assertEquals(FlowState.IN_PROGRESS, getFlowReadStatus(record, flowId));

        record = ofsConsumer.pollMessage();
        assertNotNull(record);
        CommandMessage commandMessage = objectMapper.readValue(record.value(), CommandMessage.class);
        assertNotNull(commandMessage);
        CommandData data = commandMessage.getData();
        assertNotNull(data);

        errorFlowSpeakerCommand(flowId, ((BaseFlow) data).getCookie(), ((BaseFlow) data).getTransactionId());

        nbConsumer.pollMessage();

        FlowState state = Failsafe.with(new RetryPolicy()
                .withDelay(1, TimeUnit.SECONDS)
                .withMaxDuration(FLOW_STATUS_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryIf(flowState -> flowState != FlowState.DOWN))
                .get(() -> {
                    statusFlow(flowId);
                    ConsumerRecord<String, String> stateRecord = nbConsumer.pollMessage();
                    return getFlowReadStatus(stateRecord, flowId);
                });
        assertEquals(FlowState.DOWN, state);
    }

    @Test
    @Ignore("Not reliable during batch run")
    public void ctrlDumpHandler() throws Exception {
        CtrlRequest request = new CtrlRequest("flowtopology/" + ComponentType.CRUD_BOLT.toString(),
                new RequestData("dump"), 1, "dump-correlation-id", Destination.WFM_CTRL);

        sendMessage(request, flowTopology.getConfig().getKafkaFlowTopic());

        ConsumerRecord<String, String> raw = ctrlConsumer.pollMessage();

        assertNotNull(raw);
        assertNotNull(raw.value());

        Message responseGeneric = objectMapper.readValue(raw.value(), Message.class);
        CtrlResponse response = (CtrlResponse) responseGeneric;
        ResponseData payload = response.getData();

        assertEquals(request.getCorrelationId(), response.getCorrelationId());
        assertEquals(ComponentType.CRUD_BOLT.toString(), payload.getComponent());
        assertTrue(payload instanceof DumpStateResponseData);
    }

    private FlowState getFlowReadStatus(ConsumerRecord<String, String> record, String flowId) throws IOException {
        assertNotNull(record);
        assertNotNull(record.value());

        InfoMessage message = objectMapper.readValue(record.value(), InfoMessage.class);
        assertNotNull(message);

        FlowReadResponse flowResponse = (FlowReadResponse) message.getData();
        assertNotNull(flowResponse);

        FlowDto flowPayload = flowResponse.getPayload();
        assertNotNull(flowPayload);
        assertEquals(flowId, flowPayload.getFlowId());
        return flowPayload.getState();
    }

    private void checkErrorResponseType(ConsumerRecord<String, String> record, ErrorType type) throws IOException {
        assertNotNull(record);
        assertNotNull(record.value());

        ErrorMessage errorMessage = objectMapper.readValue(record.value(), ErrorMessage.class);
        assertNotNull(errorMessage);
        assertEquals(type, errorMessage.getData().getErrorType());
    }

    private FlowDto deleteFlow(final String flowId) throws IOException {
        System.out.println("NORTHBOUND: Delete flow");
        FlowDeleteRequest commandData = new FlowDeleteRequest(flowId);
        CommandMessage message = new CommandMessage(commandData, 0, "delete-flow", Destination.WFM);

        //sendNorthboundMessage(message);
        //sendTopologyEngineMessage(message);
        sendFlowMessage(message);

        FlowDto payload = new FlowDto();
        payload.setFlowId(flowId);
        return payload;
    }

    private FlowDto createFlow(final String flowId) throws IOException {
        System.out.println("NORTHBOUND: Create flow");

        createSwitchIfNotExist("ff:00");
        createSwitchIfNotExist("ff:01");
        createIslIfNotExist("ff:00", "ff:01");

        FlowDto flowPayload =
                new FlowDto(flowId, 10000, false, "", new SwitchId("ff:00"), 10, 20,
                        new SwitchId("ff:01"), 10, 20, false,
                        new DetectConnectedDevicesDto(false, false, false, false, false, false));
        FlowCreateRequest commandData = new FlowCreateRequest(flowPayload);
        CommandMessage message = new CommandMessage(commandData, 0, "create-flow", Destination.WFM);
        //sendNorthboundMessage(message);
        sendFlowMessage(message);
        return flowPayload;
    }

    private void createSwitchIfNotExist(String switchId) {
        SwitchId switchIdObj = new SwitchId(switchId);

        SwitchRepository switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        if (!switchRepository.exists(switchIdObj)) {
            Switch sw = Switch.builder().switchId(switchIdObj).status(SwitchStatus.ACTIVE)
                    .build();
            switchRepository.createOrUpdate(sw);

            SwitchPropertiesRepository switchPropertiesRepository = persistenceManager.getRepositoryFactory()
                    .createSwitchPropertiesRepository();
            Optional<SwitchProperties> switchPropertiesResult = switchPropertiesRepository.findBySwitchId(
                    sw.getSwitchId());
            if (!switchPropertiesResult.isPresent()) {
                SwitchProperties switchProperties = SwitchProperties.builder().switchObj(sw)
                        .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES).build();
                switchPropertiesRepository.createOrUpdate(switchProperties);
            }

        }
    }

    private void createIslIfNotExist(String sourceSwitchId, String destinationSwitchId) {
        SwitchRepository switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        SwitchId sourceSwitchIdObj = new SwitchId(sourceSwitchId);
        Switch source = switchRepository.findById(sourceSwitchIdObj).get();
        SwitchId destinationSwitchIdObj = new SwitchId(destinationSwitchId);
        Switch destination = switchRepository.findById(destinationSwitchIdObj).get();
        IslRepository islRepository = persistenceManager.getRepositoryFactory().createIslRepository();

        Isl forward = new Isl();
        forward.setSrcSwitch(source);
        forward.setSrcPort(1);
        forward.setDestSwitch(destination);
        forward.setDestPort(1);
        forward.setMaxBandwidth(10000);
        forward.setAvailableBandwidth(10000);
        forward.setStatus(IslStatus.ACTIVE);
        islRepository.createOrUpdate(forward);

        Isl backward = new Isl();
        backward.setSrcSwitch(destination);
        backward.setSrcPort(1);
        backward.setDestSwitch(source);
        backward.setDestPort(1);
        backward.setMaxBandwidth(10000);
        backward.setAvailableBandwidth(10000);
        backward.setStatus(IslStatus.ACTIVE);
        islRepository.createOrUpdate(backward);
    }

    private FlowDto updateFlow(final String flowId) throws IOException {
        System.out.println("NORTHBOUND: Update flow");
        FlowDto flowPayload =
                new FlowDto(flowId, 10000, true, "", new SwitchId("ff:00"), 10, 20,
                        new SwitchId("ff:01"), 10, 20, false,
                        new DetectConnectedDevicesDto(false, false, false, false, false, false));
        FlowUpdateRequest commandData = new FlowUpdateRequest(flowPayload);
        CommandMessage message = new CommandMessage(commandData, 0, "update-flow", Destination.WFM);
        //sendNorthboundMessage(message);
        sendFlowMessage(message);
        return flowPayload;
    }

    private void statusFlow(final String flowId) throws IOException {
        System.out.println("NORTHBOUND: Status flow");
        FlowReadRequest commandData = new FlowReadRequest(flowId);
        CommandMessage message = new CommandMessage(commandData, 0, "status-flow", Destination.WFM);
        //sendNorthboundMessage(message);
        sendFlowMessage(message);
    }

    private PathInfoData pathFlow(final String flowId) throws IOException {
        System.out.println("NORTHBOUND: Path flow");
        FlowReadRequest commandData = new FlowReadRequest(flowId);
        CommandMessage message = new CommandMessage(commandData, 0, "path-flow", Destination.WFM);
        //sendNorthboundMessage(message);
        sendFlowMessage(message);
        return new PathInfoData(0L, Collections.emptyList());
    }

    private void getFlow(final String flowId) throws IOException {
        System.out.println("NORTHBOUND: Get flow");
        FlowReadRequest commandData = new FlowReadRequest(flowId);
        CommandMessage message = new CommandMessage(commandData, 0, "get-flow", Destination.WFM);
        //sendNorthboundMessage(message);
        sendFlowMessage(message);
    }

    private void dumpFlows() throws IOException {
        System.out.println("NORTHBOUND: Get flows");
        FlowsDumpRequest commandData = new FlowsDumpRequest();
        CommandMessage message = new CommandMessage(commandData, 0, "get-flows", Destination.WFM);
        //sendNorthboundMessage(message);
        sendFlowMessage(message);
    }

    private InstallOneSwitchFlow baseInstallFlowCommand(final String flowId) throws IOException {
        System.out.println("TOPOLOGY: Install flow");
        InstallOneSwitchFlow commandData = new InstallOneSwitchFlow(TRANSACTION_ID, flowId,
                COOKIE, new SwitchId("ff:04"), 1, 2, 0, 0, OutputVlanType.NONE, 10000L, 0L,
                false, false, new HashSet<>(), Metadata.builder().build());
        CommandMessage commandMessage = new CommandMessage(commandData, 0, "install-flow", Destination.WFM);
        //sendTopologyEngineMessage(commandMessage);
        //sendSpeakerMessage(commandMessage);
        sendFlowMessage(commandMessage);
        return commandData;
    }

    private RemoveFlow removeFlowCommand(final String flowId) throws IOException {
        System.out.println("TOPOLOGY: Remove flow");
        RemoveFlow commandData = RemoveFlow.builder()
                .transactionId(TRANSACTION_ID)
                .flowId(flowId)
                .cookie(COOKIE)
                .switchId(new SwitchId("ff:04"))
                .meterId(0L)
                .criteria(DeleteRulesCriteria.builder().cookie(COOKIE).build())
                .build();
        CommandMessage commandMessage = new CommandMessage(commandData, 0, "remove-flow", Destination.WFM);
        //sendTopologyEngineMessage(commandMessage);
        sendFlowMessage(commandMessage);
        return commandData;
    }

    private ErrorMessage errorFlowTopologyEngineCommand(final String flowId, final ErrorType type) throws IOException {
        System.out.println("TOPOLOGY: Error flow");
        ErrorData errorData = new ErrorData(type, "Could not operate with flow", flowId);
        ErrorMessage errorMessage = new ErrorMessage(errorData, 0, "error-flow", Destination.WFM);
        //sendTopologyEngineMessage(errorMessage);
        sendMessage(errorMessage, topologyConfig.getKafkaFlowTopic());
        return errorMessage;
    }

    private Message baseInstallRuleCommand(final Message message) throws IOException {
        System.out.println("TOPOLOGY: Install rule");
        sendMessage(message, topologyConfig.getKafkaFlowTopic());
        return message;
    }

    private Message removeRuleCommand(final Message message) throws IOException {
        System.out.println("TOPOLOGY: Remove rule");
        sendMessage(message, topologyConfig.getKafkaFlowTopic());
        return message;
    }

    private ErrorMessage errorFlowSpeakerCommand(String flowId, long cookie, UUID transactionId) throws IOException {
        System.out.println("TOPOLOGY: Error rule");
        FlowCommandErrorData errorData = new FlowCommandErrorData(flowId, cookie, transactionId,
                ErrorType.REQUEST_INVALID, "Could not operate with flow", flowId);
        ErrorMessage errorMessage = new ErrorMessage(errorData, 0, "error-flow", Destination.WFM_TRANSACTION);
        sendMessage(errorMessage, topologyConfig.getKafkaFlowTopic());
        return errorMessage;
    }

    private void sendFlowMessage(final CommandMessage message) throws IOException {
        sendMessage(message, topologyConfig.getKafkaFlowTopic());
    }

    private void sendNorthboundMessage(final CommandMessage message) throws IOException {
        sendMessage(message, topologyConfig.getKafkaNorthboundTopic());
    }

    private void sendMessage(Object object, String topic) throws IOException {
        String request = objectMapper.writeValueAsString(object);
        kProducer.pushMessage(topic, request);
    }

    private FlowPairDto<FlowDto, FlowDto> getFlowPayload(InfoMessage message) {
        InfoData data = message.getData();
        FlowInfoData flow = (FlowInfoData) data;
        return flow.getPayload();
    }

    private void sendClearState() throws IOException, InterruptedException {
        CtrlRequest request = new CtrlRequest("flowtopology/" + ComponentType.CRUD_BOLT.toString(),
                new RequestData("clearState"), 1, "clear-state-correlation-id", Destination.WFM_CTRL);
        sendMessage(request, topologyConfig.getKafkaCtrlTopic());
    }
}
