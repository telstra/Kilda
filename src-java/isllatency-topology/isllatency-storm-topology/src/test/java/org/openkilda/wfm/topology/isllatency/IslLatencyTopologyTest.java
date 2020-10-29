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

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.openkilda.wfm.topology.isllatency.bolts.IslStatsBolt.LATENCY_METRIC_NAME;
import static org.openkilda.wfm.topology.isllatency.service.OneWayLatencyManipulationService.ONE_WAY_LATENCY_MULTIPLIER;

import org.openkilda.messaging.info.Datapoint;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.IslOneWayLatency;
import org.openkilda.messaging.info.event.IslRoundTripLatency;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.inmemory.InMemoryGraphPersistenceManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.AbstractStormTest;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.config.provider.MultiPrefixConfigurationProvider;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.topology.TestKafkaConsumer;
import org.openkilda.wfm.topology.isllatency.model.IslKey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.storm.Config;
import org.apache.storm.generated.StormTopology;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

public class IslLatencyTopologyTest extends AbstractStormTest {

    private static final int POLL_TIMEOUT = 1000;
    private static final String POLL_DATAPOINT_ASSERT_MESSAGE = "Could not poll any datapoint";
    private static final String METRIC_PREFIX = "kilda.";
    private static final int PORT_1 = 1;
    private static final int PORT_2 = 2;
    private static final int INITIAL_FORWARD_LATENCY = 123;
    private static final int INITIAL_REVERSE_LATENCY = 456;
    private static final long PACKET_ID = 555;
    private static final SwitchId SWITCH_ID_1 = new SwitchId(1L);
    private static final SwitchId SWITCH_ID_2 = new SwitchId(2L);
    private static final IslKey FORWARD_ISL = new IslKey(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2);
    private static final IslKey REVERSE_ISL = new IslKey(SWITCH_ID_2, PORT_2, SWITCH_ID_1, PORT_1);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static IslLatencyTopologyConfig islLatencyTopologyConfig;
    private static TestKafkaConsumer otsdbConsumer;
    private static InMemoryGraphPersistenceManager persistenceManager;
    private static SwitchRepository switchRepository;
    private static IslRepository islRepository;

    @BeforeClass
    public static void setupOnce() throws Exception {
        AbstractStormTest.startZooKafkaAndStorm();

        LaunchEnvironment launchEnvironment = makeLaunchEnvironment();
        Properties configOverlay = new Properties();
        configOverlay.setProperty("opentsdb.metric.prefix", METRIC_PREFIX);

        launchEnvironment.setupOverlay(configOverlay);
        MultiPrefixConfigurationProvider configurationProvider = launchEnvironment.getConfigurationProvider();

        persistenceManager = new InMemoryGraphPersistenceManager(
                configurationProvider.getConfiguration(NetworkConfig.class));

        IslLatencyTopology islLatencyTopology = new IslLatencyTopology(launchEnvironment);
        islLatencyTopologyConfig = islLatencyTopology.getConfig();

        StormTopology stormTopology = islLatencyTopology.createTopology();
        Config config = stormConfig();

        cluster.submitTopology(IslLatencyTopologyTest.class.getSimpleName(), config, stormTopology);

        otsdbConsumer = new TestKafkaConsumer(islLatencyTopologyConfig.getKafkaOtsdbTopic(),
                kafkaConsumerProperties(UUID.randomUUID().toString()));
        otsdbConsumer.start();

        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();

        sleep(TOPOLOGY_START_TIMEOUT);
    }

    @AfterClass
    public static void teardownOnce() throws Exception {
        otsdbConsumer.wakeup();
        otsdbConsumer.join();

        AbstractStormTest.stopZooKafkaAndStorm();
    }

    @Before
    public void setup() {
        otsdbConsumer.clear();

        persistenceManager.purgeData();

        Switch firstSwitch = createSwitch(SWITCH_ID_1);
        Switch secondSwitch = createSwitch(SWITCH_ID_2);
        createIsl(firstSwitch, PORT_1, secondSwitch, PORT_2, INITIAL_FORWARD_LATENCY);
        createIsl(secondSwitch, PORT_2, firstSwitch, PORT_1, INITIAL_REVERSE_LATENCY);
    }

    @Test
    public void checkTopologyMetricAndDatabaseUpdateTest() throws IslNotFoundException, JsonProcessingException {
        // It's hard to split this test on several tests because IslStatsBolts and IslLatencyBolt has internal states
        long latency1 = 1;
        long latency2 = 2;
        long latency3 = 3;
        long latency4 = 4;
        long latency5 = 5;

        IslOneWayLatency firstOneWayLatency = createForwardIslOneWayLatency(latency1);
        IslOneWayLatency secondOneWayLatency = createForwardIslOneWayLatency(latency3);
        IslOneWayLatency reverseOneWayLatency = createReverseIslOneWayLatency(latency5);

        IslRoundTripLatency firstRoundTripLatency = new IslRoundTripLatency(SWITCH_ID_1, PORT_1, latency2, PACKET_ID);
        IslRoundTripLatency secondRoundTripLatency = new IslRoundTripLatency(SWITCH_ID_1, PORT_1, latency4, PACKET_ID);

        // we have no round trip latency so we have to use one way latency for the database, but not for OpenTSDB
        pushMessage(firstOneWayLatency);
        assertTrue(otsdbConsumer.isEmpty());
        assertEquals(latency1 * ONE_WAY_LATENCY_MULTIPLIER, getIslLatency(FORWARD_ISL));

        // we got round trip latency so we will use it for metric and database
        long timestamp2 = pushMessage(firstRoundTripLatency);
        assertMetric(FORWARD_ISL, latency2, timestamp2);
        assertEquals(latency2, getIslLatency(FORWARD_ISL));

        // we got one way latency but bolts already has data with RTL latency. one way latency will be ignored
        pushMessage(secondOneWayLatency);
        assertTrue(otsdbConsumer.isEmpty());
        assertEquals(latency2, getIslLatency(FORWARD_ISL));

        // we got new round trip latency and it will be used for metric
        long timestamp4 = pushMessage(secondRoundTripLatency);
        assertMetric(FORWARD_ISL, latency4, timestamp4);
        // but not for database, because of big update time interval
        assertEquals(latency2, getIslLatency(FORWARD_ISL));

        // we got one way latency for reverse isl, but we already has RTL for forward ISL and we can use it
        long timestamp5 = pushMessage(reverseOneWayLatency);
        assertMetric(REVERSE_ISL, latency4, timestamp5);
        assertEquals((latency2 + latency4) / 2, getIslLatency(REVERSE_ISL));
    }

    private long pushMessage(InfoData infoData) throws JsonProcessingException {
        long timestamp = System.currentTimeMillis();
        InfoMessage infoMessage = new InfoMessage(infoData, timestamp, UUID.randomUUID().toString(), null, null);
        String request = objectMapper.writeValueAsString(infoMessage);
        kProducer.pushMessage(islLatencyTopologyConfig.getKafkaTopoIslLatencyTopic(), request);

        return timestamp;
    }

    private void assertMetric(IslKey isl, long expectedLatency, Long expectedTimestamp) {
        Datapoint datapoint = pollDataPoint();

        assertEquals(isl.getSrcSwitchId().toOtsdFormat(), datapoint.getTags().get("src_switch"));
        assertEquals(String.valueOf(isl.getSrcPort()), datapoint.getTags().get("src_port"));
        assertEquals(isl.getDstSwitchId().toOtsdFormat(), datapoint.getTags().get("dst_switch"));
        assertEquals(String.valueOf(isl.getDstPort()), datapoint.getTags().get("dst_port"));
        assertEquals(expectedTimestamp, datapoint.getTime());
        assertEquals(METRIC_PREFIX + LATENCY_METRIC_NAME, datapoint.getMetric());
        assertEquals(expectedLatency, datapoint.getValue().longValue());
    }

    private Datapoint pollDataPoint() {
        ConsumerRecord<String, String> record = null;
        try {
            record = otsdbConsumer.pollMessage(POLL_TIMEOUT);
            if (record == null) {
                throw new AssertionError(POLL_DATAPOINT_ASSERT_MESSAGE);
            }
            return objectMapper.readValue(record.value(), Datapoint.class);
        } catch (InterruptedException e) {
            throw new AssertionError(POLL_DATAPOINT_ASSERT_MESSAGE);
        } catch (IOException e) {
            throw new AssertionError(String.format("Could not parse datapoint object: '%s'", record.value()));
        }
    }

    private static Switch createSwitch(SwitchId switchId) {
        Switch sw = Switch.builder().switchId(switchId).build();
        switchRepository.add(sw);
        return sw;
    }

    private void createIsl(Switch srcSwitch, int srcPort, Switch dstSwitch, int dstPort, int latency) {
        Isl isl = Isl.builder()
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .actualStatus(IslStatus.ACTIVE)
                .status(IslStatus.ACTIVE)
                .latency(latency).build();
        islRepository.add(isl);
    }

    private long getIslLatency(IslKey islKey) throws IslNotFoundException {
        return getIslLatency(
                islKey.getSrcSwitchId(), islKey.getSrcPort(), islKey.getDstSwitchId(), islKey.getDstPort());
    }

    private long getIslLatency(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort)
            throws IslNotFoundException {
        return islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort)
                .orElseThrow(() -> new IslNotFoundException(srcSwitchId, srcPort, dstSwitchId, dstPort))
                .getLatency();
    }

    private IslOneWayLatency createForwardIslOneWayLatency(long latency) {
        return new IslOneWayLatency(SWITCH_ID_1, PORT_1, SWITCH_ID_2, PORT_2, latency, PACKET_ID);
    }

    private IslOneWayLatency createReverseIslOneWayLatency(long latency) {
        return new IslOneWayLatency(SWITCH_ID_2, PORT_2, SWITCH_ID_1, PORT_1, latency, PACKET_ID);
    }
}
