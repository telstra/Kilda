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

package org.openkilda.wfm.topology.stats;

import static java.util.stream.Collectors.toList;
import static org.apache.storm.utils.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.openkilda.model.FlowPathDirection.FORWARD;
import static org.openkilda.model.FlowPathDirection.REVERSE;
import static org.openkilda.model.cookie.Cookie.VERIFICATION_BROADCAST_RULE_COOKIE;

import org.openkilda.floodlight.api.request.EgressFlowSegmentInstallRequest;
import org.openkilda.floodlight.api.request.IngressFlowSegmentInstallRequest;
import org.openkilda.floodlight.api.request.IngressFlowSegmentRemoveRequest;
import org.openkilda.floodlight.api.request.SpeakerRequest;
import org.openkilda.floodlight.api.request.TransitFlowSegmentInstallRequest;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.BaseFlow;
import org.openkilda.messaging.command.flow.InstallOneSwitchFlow;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.info.Datapoint;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.grpc.GetPacketInOutStatsResponse;
import org.openkilda.messaging.info.stats.FlowRttStatsData;
import org.openkilda.messaging.info.stats.FlowStatsData;
import org.openkilda.messaging.info.stats.FlowStatsEntry;
import org.openkilda.messaging.info.stats.MeterConfigReply;
import org.openkilda.messaging.info.stats.MeterConfigStatsData;
import org.openkilda.messaging.info.stats.MeterStatsData;
import org.openkilda.messaging.info.stats.MeterStatsEntry;
import org.openkilda.messaging.info.stats.PortStatsData;
import org.openkilda.messaging.info.stats.PortStatsEntry;
import org.openkilda.messaging.info.stats.SwitchTableStatsData;
import org.openkilda.messaging.info.stats.TableStatsEntry;
import org.openkilda.messaging.model.grpc.PacketInOutStatsDto;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterId;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.Cookie;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.inmemory.InMemoryGraphPersistenceManager;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.AbstractStormTest;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.config.provider.MultiPrefixConfigurationProvider;
import org.openkilda.wfm.share.flow.TestFlowBuilder;
import org.openkilda.wfm.topology.TestKafkaConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.storm.Config;
import org.apache.storm.generated.StormTopology;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StatsTopologyTest extends AbstractStormTest {

    private static final long timestamp = System.currentTimeMillis();
    private static final int POLL_TIMEOUT = 1000;
    private static final String POLL_DATAPOINT_ASSERT_MESSAGE = "Could not poll all %d datapoints, got only %d records";
    private static final String METRIC_PREFIX = "kilda.";
    private static final int ENCAPSULATION_ID = 123;
    private static final UUID TRANSACTION_ID = UUID.randomUUID();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static InMemoryGraphPersistenceManager persistenceManager;
    private static StatsTopologyConfig statsTopologyConfig;
    private static TestKafkaConsumer otsdbConsumer;
    private static FlowRepository flowRepository;
    private static SwitchRepository switchRepository;
    private static final SwitchId SWITCH_ID_1 = new SwitchId(1L);
    private static final SwitchId SWITCH_ID_2 = new SwitchId(2L);
    private static final SwitchId SWITCH_ID_3 = new SwitchId(3L);
    private static final int PORT_1 = 1;
    private static final int PORT_2 = 2;
    private static final int PORT_3 = 3;
    private static final long MAIN_COOKIE = 15;
    private static final long PROTECTED_COOKIE = 17;
    private static final FlowSegmentCookie MAIN_FORWARD_COOKIE = new FlowSegmentCookie(FORWARD, MAIN_COOKIE);
    private static final FlowSegmentCookie MAIN_REVERSE_COOKIE = new FlowSegmentCookie(REVERSE, MAIN_COOKIE);
    private static final FlowSegmentCookie PROTECTED_FORWARD_COOKIE = new FlowSegmentCookie(FORWARD, PROTECTED_COOKIE);
    private static final FlowSegmentCookie PROTECTED_REVERSE_COOKIE = new FlowSegmentCookie(REVERSE, PROTECTED_COOKIE);
    private final String flowId = "f253423454343";

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

        StatsTopology statsTopology = new StatsTopology(launchEnvironment);
        statsTopologyConfig = statsTopology.getConfig();

        StormTopology stormTopology = statsTopology.createTopology();
        Config config = stormConfig();

        cluster.submitTopology(StatsTopologyTest.class.getSimpleName(), config, stormTopology);

        otsdbConsumer = new TestKafkaConsumer(statsTopologyConfig.getKafkaOtsdbTopic(),
                kafkaConsumerProperties(UUID.randomUUID().toString()));
        otsdbConsumer.start();

        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();

        sleep(TOPOLOGY_START_TIMEOUT);
    }

    @AfterClass
    public static void teardownOnce() throws Exception {
        otsdbConsumer.wakeup();
        otsdbConsumer.join();

        AbstractStormTest.stopZooKafkaAndStorm();
    }

    @Before
    public void setup() throws IOException {
        otsdbConsumer.clear();

        // need clear data in CacheBolt
        for (Flow flow : flowRepository.findAll()) {
            sendRemoveFlowCommand(flow, flow.getForwardPath());
        }

        persistenceManager.purgeData();
    }

    @Test
    public void portStatsTest() throws Exception {
        final SwitchId switchId = new SwitchId(1L);
        int portCount = 52;

        final List<PortStatsEntry> entries = IntStream.range(1, portCount + 1).boxed().map(port -> {
            int baseCount = port * 20;
            return new PortStatsEntry(port, baseCount, baseCount + 1, baseCount + 2, baseCount + 3,
                    baseCount + 4, baseCount + 5, baseCount + 6, baseCount + 7,
                    baseCount + 8, baseCount + 9, baseCount + 10, baseCount + 11);
        }).collect(toList());

        sendStatsMessage(new PortStatsData(switchId, entries));

        List<Datapoint> datapoints = pollDatapoints(728);

        Map<String, Map<String, Number>> metricsByPort = new HashMap<>();
        for (int i = 1; i <= portCount; i++) {
            metricsByPort.put(String.valueOf(i), new HashMap<>());
        }

        datapoints.forEach(datapoint -> {
            assertThat(datapoint.getTags().get("switchid"), is(switchId.toOtsdFormat()));
            assertThat(datapoint.getTime(), is(timestamp));
            assertThat(datapoint.getMetric(), startsWith(METRIC_PREFIX + "switch"));

            metricsByPort.get(datapoint.getTags().get("port"))
                    .put(datapoint.getMetric(), datapoint.getValue());
        });

        for (int i = 1; i <= portCount; i++) {
            Map<String, Number> metrics = metricsByPort.get(String.valueOf(i));
            assertEquals(14, metrics.size());

            int baseCount = i * 20;
            assertEquals(baseCount, metrics.get(METRIC_PREFIX + "switch.rx-packets"));
            assertEquals(baseCount + 1, metrics.get(METRIC_PREFIX + "switch.tx-packets"));
            assertEquals(baseCount + 2, metrics.get(METRIC_PREFIX + "switch.rx-bytes"));
            assertEquals((baseCount + 2) * 8, metrics.get(METRIC_PREFIX + "switch.rx-bits"));
            assertEquals(baseCount + 3, metrics.get(METRIC_PREFIX + "switch.tx-bytes"));
            assertEquals((baseCount + 3) * 8, metrics.get(METRIC_PREFIX + "switch.tx-bits"));
            assertEquals(baseCount + 4, metrics.get(METRIC_PREFIX + "switch.rx-dropped"));
            assertEquals(baseCount + 5, metrics.get(METRIC_PREFIX + "switch.tx-dropped"));
            assertEquals(baseCount + 6, metrics.get(METRIC_PREFIX + "switch.rx-errors"));
            assertEquals(baseCount + 7, metrics.get(METRIC_PREFIX + "switch.tx-errors"));
            assertEquals(baseCount + 8, metrics.get(METRIC_PREFIX + "switch.rx-frame-error"));
            assertEquals(baseCount + 9, metrics.get(METRIC_PREFIX + "switch.rx-over-error"));
            assertEquals(baseCount + 10, metrics.get(METRIC_PREFIX + "switch.rx-crc-error"));
            assertEquals(baseCount + 11, metrics.get(METRIC_PREFIX + "switch.collisions"));
        }
    }

    @Test
    public void meterConfigStatsTest() throws Exception {
        final SwitchId switchId = new SwitchId(1L);
        final List<MeterConfigReply> stats =
                Collections.singletonList(new MeterConfigReply(2, Arrays.asList(1L, 2L, 3L)));
        sendStatsMessage(new MeterConfigStatsData(switchId, stats));

        List<Datapoint> datapoints = pollDatapoints(3);

        datapoints.forEach(datapoint -> {
            assertThat(datapoint.getTags().get("switchid"), is(switchId.toOtsdFormat()));
            assertThat(datapoint.getTime(), is(timestamp));
            assertThat(datapoint.getMetric(), is(METRIC_PREFIX + "switch.meters"));
        });
    }

    @Test
    public void meterSystemRulesStatsTest() throws IOException {
        long meterId = MeterId.createMeterIdForDefaultRule(VERIFICATION_BROADCAST_RULE_COOKIE).getValue();
        MeterStatsEntry meterStats = new MeterStatsEntry(meterId, 400L, 500L);

        sendStatsMessage(new MeterStatsData(SWITCH_ID_1, Collections.singletonList(meterStats)));

        List<Datapoint> datapoints = pollDatapoints(3);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(meterStats.getPacketsInCount(),
                datapointMap.get(METRIC_PREFIX + "switch.flow.system.meter.packets").getValue().longValue());
        assertEquals(meterStats.getByteInCount(),
                datapointMap.get(METRIC_PREFIX + "switch.flow.system.meter.bytes").getValue().longValue());
        assertEquals(meterStats.getByteInCount() * 8,
                datapointMap.get(METRIC_PREFIX + "switch.flow.system.meter.bits").getValue().longValue());

        datapoints.forEach(datapoint -> {
            assertEquals(3, datapoint.getTags().size());
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals(String.valueOf(meterId), datapoint.getTags().get("meterid"));
            assertEquals(Cookie.toString(VERIFICATION_BROADCAST_RULE_COOKIE), datapoint.getTags().get("cookieHex"));
            assertEquals(timestamp, datapoint.getTime().longValue());
        });
    }

    @Test
    public void packetInOutStatsTest() throws IOException {
        PacketInOutStatsDto data = new PacketInOutStatsDto();
        data.setPacketInTotalPackets(1);
        data.setPacketInTotalPacketsDataplane(2);
        data.setPacketInNoMatchPackets(3);
        data.setPacketInApplyActionPackets(4);
        data.setPacketInInvalidTtlPackets(5);
        data.setPacketInActionSetPackets(6);
        data.setPacketInGroupPackets(7);
        data.setPacketInPacketOutPackets(8);
        data.setPacketOutTotalPacketsDataplane(9);
        data.setPacketOutTotalPacketsHost(10);
        data.setPacketOutEth0InterfaceUp(true);

        sendStatsMessage(new GetPacketInOutStatsResponse(SWITCH_ID_1, data));

        List<Datapoint> datapoints = pollDatapoints(11);

        Map<String, Datapoint> map = createDatapointMap(datapoints);

        assertMetric(data.getPacketInTotalPackets(), "switch.packet-in.total-packets", map);
        assertMetric(data.getPacketInTotalPacketsDataplane(), "switch.packet-in.total-packets.dataplane", map);
        assertMetric(data.getPacketInNoMatchPackets(), "switch.packet-in.no-match.packets", map);
        assertMetric(data.getPacketInApplyActionPackets(), "switch.packet-in.apply-action.packets", map);
        assertMetric(data.getPacketInInvalidTtlPackets(), "switch.packet-in.invalid-ttl.packets", map);
        assertMetric(data.getPacketInActionSetPackets(), "switch.packet-in.action-set.packets", map);
        assertMetric(data.getPacketInGroupPackets(), "switch.packet-in.group.packets", map);
        assertMetric(data.getPacketInPacketOutPackets(), "switch.packet-in.packet-out.packets", map);
        assertMetric(data.getPacketOutTotalPacketsHost(), "switch.packet-out.total-packets.host", map);
        assertMetric(data.getPacketOutTotalPacketsDataplane(), "switch.packet-out.total-packets.dataplane", map);
        assertMetric(1, "switch.packet-out.eth0-interface-up", map);

        datapoints.forEach(datapoint -> {
            assertEquals(1, datapoint.getTags().size());
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals(timestamp, datapoint.getTime().longValue());
        });
    }


    @Test
    public void meterFlowRulesStatsTest() throws IOException {
        Flow flow = createFlow(SWITCH_ID_1, flowId);
        FlowPath flowPath = flow.getForwardPath();
        sendInstallOneSwitchFlowCommand(flow, flowPath);

        MeterStatsEntry meterStats = new MeterStatsEntry(flowPath.getMeterId().getValue(), 500L, 700L);

        sendStatsMessage(new MeterStatsData(SWITCH_ID_1, Collections.singletonList(meterStats)));

        List<Datapoint> datapoints = pollDatapoints(3);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(meterStats.getPacketsInCount(),
                datapointMap.get(METRIC_PREFIX + "flow.meter.packets").getValue().longValue());
        assertEquals(meterStats.getByteInCount(),
                datapointMap.get(METRIC_PREFIX + "flow.meter.bytes").getValue().longValue());
        assertEquals(meterStats.getByteInCount() * 8,
                datapointMap.get(METRIC_PREFIX + "flow.meter.bits").getValue().longValue());


        datapoints.forEach(datapoint -> {
            assertEquals(5, datapoint.getTags().size());
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals(String.valueOf(flowPath.getMeterId().getValue()), datapoint.getTags().get("meterid"));
            assertEquals("forward", datapoint.getTags().get("direction"));
            assertEquals(flowId, datapoint.getTags().get("flowid"));
            assertEquals(String.valueOf(flowPath.getCookie().getValue()), datapoint.getTags().get("cookie"));
            assertEquals(timestamp, datapoint.getTime().longValue());
        });
    }

    @Test
    public void lldpMeterStatsTest() throws IOException {
        long lldpMeterId = MeterId.MIN_FLOW_METER_ID;

        MeterStatsEntry meterStats = new MeterStatsEntry(lldpMeterId, 400L, 500L);

        sendStatsMessage(new MeterStatsData(SWITCH_ID_1, Collections.singletonList(meterStats)));

        List<Datapoint> datapoints = pollDatapoints(3);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(meterStats.getPacketsInCount(),
                datapointMap.get(METRIC_PREFIX + "flow.meter.packets").getValue().longValue());
        assertEquals(meterStats.getByteInCount(),
                datapointMap.get(METRIC_PREFIX + "flow.meter.bytes").getValue().longValue());
        assertEquals(meterStats.getByteInCount() * 8,
                datapointMap.get(METRIC_PREFIX + "flow.meter.bits").getValue().longValue());


        datapoints.forEach(datapoint -> {
            assertEquals(5, datapoint.getTags().size());
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals(String.valueOf(lldpMeterId), datapoint.getTags().get("meterid"));
            assertEquals("unknown", datapoint.getTags().get("direction"));
            assertEquals("unknown", datapoint.getTags().get("cookie"));
            assertEquals("unknown", datapoint.getTags().get("flowid"));
            assertEquals(timestamp, datapoint.getTime().longValue());
        });
    }

    @Test
    public void flowStatsTest() throws Exception {
        Flow flow = createFlow(SWITCH_ID_1, flowId);
        sendInstallOneSwitchFlowCommand(flow, flow.getForwardPath());

        FlowStatsEntry flowStats = new FlowStatsEntry((short) 1, MAIN_FORWARD_COOKIE.getValue(), 150L, 300L, 10, 10);

        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(flowStats)));
        validateFlowStats(flowStats, MAIN_FORWARD_COOKIE, SWITCH_ID_1, true, true);
    }

    @Test
    public void flowStatsWithProtectedTest() throws Exception {
        // install 3 rules on switch 1
        sendInstallIngressCommand(MAIN_FORWARD_COOKIE);
        sendInstallEgressCommand(MAIN_REVERSE_COOKIE, SWITCH_ID_1, PORT_2);
        sendInstallEgressCommand(PROTECTED_REVERSE_COOKIE, SWITCH_ID_1, PORT_3);
        // install 2 transit rules on switch 2
        sendInstallTransitCommand(MAIN_FORWARD_COOKIE);
        sendInstallTransitCommand(MAIN_REVERSE_COOKIE);

        FlowStatsEntry mainForwardStats = new FlowStatsEntry(1, MAIN_FORWARD_COOKIE.getValue(), 1, 2, 3, 4);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(mainForwardStats)));
        validateFlowStats(mainForwardStats, MAIN_FORWARD_COOKIE, SWITCH_ID_1, true, false);

        FlowStatsEntry mainReverseStats = new FlowStatsEntry(1, MAIN_REVERSE_COOKIE.getValue(), 5, 6, 7, 8);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(mainReverseStats)));
        validateFlowStats(mainReverseStats, MAIN_REVERSE_COOKIE, SWITCH_ID_1, false, true);

        FlowStatsEntry protectedStats = new FlowStatsEntry(1, PROTECTED_REVERSE_COOKIE.getValue(), 9, 10, 11, 12);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(protectedStats)));
        validateFlowStats(protectedStats, PROTECTED_REVERSE_COOKIE, SWITCH_ID_1, false, true);

        FlowStatsEntry transitForwardStats = new FlowStatsEntry(1, MAIN_FORWARD_COOKIE.getValue(), 13, 14, 15, 16);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_2, Collections.singletonList(transitForwardStats)));
        validateFlowStats(transitForwardStats, MAIN_FORWARD_COOKIE, SWITCH_ID_2, false, false);

        FlowStatsEntry transitReverseStats = new FlowStatsEntry(1, MAIN_REVERSE_COOKIE.getValue(), 17, 18, 19, 20);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_2, Collections.singletonList(transitReverseStats)));
        validateFlowStats(transitReverseStats, MAIN_REVERSE_COOKIE, SWITCH_ID_2, false, false);
    }

    @Test
    public void flowStatsSwapPathTest() throws Exception {
        // install 3 rules on src switch 1
        sendInstallIngressCommand(MAIN_FORWARD_COOKIE);
        sendInstallEgressCommand(MAIN_REVERSE_COOKIE, SWITCH_ID_1, PORT_2);
        sendInstallEgressCommand(PROTECTED_REVERSE_COOKIE, SWITCH_ID_1, PORT_3);

        //install 2 egress rules on dst switch 3
        sendInstallEgressCommand(MAIN_FORWARD_COOKIE, SWITCH_ID_3, PORT_3);
        sendInstallEgressCommand(PROTECTED_FORWARD_COOKIE, SWITCH_ID_3, PORT_2);

        // install 2 transit rules on transit switch 2
        sendInstallTransitCommand(MAIN_FORWARD_COOKIE);
        sendInstallTransitCommand(MAIN_REVERSE_COOKIE);

        // swap main and protected paths
        sendInstallIngressCommand(PROTECTED_FORWARD_COOKIE);
        sendRemoveIngressCommand(MAIN_FORWARD_COOKIE);

        // check ingress protected rule on switch 1
        FlowStatsEntry protectedForwardStats = new FlowStatsEntry(1, PROTECTED_FORWARD_COOKIE.getValue(), 1, 2, 3, 4);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(protectedForwardStats)));
        validateFlowStats(protectedForwardStats, PROTECTED_FORWARD_COOKIE, SWITCH_ID_1, true, false);

        // check main forward egress rule on switch 3
        FlowStatsEntry mainForwardEgressStats = new FlowStatsEntry(1, MAIN_FORWARD_COOKIE.getValue(), 5, 6, 7, 8);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_3, Collections.singletonList(mainForwardEgressStats)));
        validateFlowStats(mainForwardEgressStats, MAIN_FORWARD_COOKIE, SWITCH_ID_3, false, true);

        // check main forward transit rule on switch 2
        FlowStatsEntry transitForwardStats = new FlowStatsEntry(1, MAIN_FORWARD_COOKIE.getValue(), 13, 14, 15, 16);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_2, Collections.singletonList(transitForwardStats)));
        validateFlowStats(transitForwardStats, MAIN_FORWARD_COOKIE, SWITCH_ID_2, false, false);

        // check main reverse transit rule on switch 2
        FlowStatsEntry transitReverseStats = new FlowStatsEntry(1, MAIN_REVERSE_COOKIE.getValue(), 17, 18, 19, 20);
        sendStatsMessage(new FlowStatsData(SWITCH_ID_2, Collections.singletonList(transitReverseStats)));
        validateFlowStats(transitReverseStats, MAIN_REVERSE_COOKIE, SWITCH_ID_2, false, false);
    }

    private void validateFlowStats(
            FlowStatsEntry flowStats, FlowSegmentCookie cookie, SwitchId switchId,
            boolean includeIngress, boolean includeEgress) {
        int expectedDatapointCount = 3;
        if (includeIngress) {
            expectedDatapointCount += 3;
        }
        if (includeEgress) {
            expectedDatapointCount += 3;
        }

        List<Datapoint> datapoints = pollDatapoints(expectedDatapointCount);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(flowStats.getPacketCount(),
                datapointMap.get(METRIC_PREFIX + "flow.raw.packets").getValue().longValue());
        assertEquals(flowStats.getByteCount(),
                datapointMap.get(METRIC_PREFIX + "flow.raw.bytes").getValue().longValue());
        assertEquals(flowStats.getByteCount() * 8,
                datapointMap.get(METRIC_PREFIX + "flow.raw.bits").getValue().longValue());

        if (includeIngress) {
            assertEquals(flowStats.getPacketCount(),
                    datapointMap.get(METRIC_PREFIX + "flow.ingress.packets").getValue().longValue());
            assertEquals(flowStats.getByteCount(),
                    datapointMap.get(METRIC_PREFIX + "flow.ingress.bytes").getValue().longValue());
            assertEquals(flowStats.getByteCount() * 8,
                    datapointMap.get(METRIC_PREFIX + "flow.ingress.bits").getValue().longValue());
        }

        if (includeEgress) {
            assertEquals(flowStats.getPacketCount(),
                    datapointMap.get(METRIC_PREFIX + "flow.packets").getValue().longValue());
            assertEquals(flowStats.getByteCount(),
                    datapointMap.get(METRIC_PREFIX + "flow.bytes").getValue().longValue());
            assertEquals(flowStats.getByteCount() * 8,
                    datapointMap.get(METRIC_PREFIX + "flow.bits").getValue().longValue());
        }

        String direction = cookie.getDirection().name().toLowerCase();

        datapoints.forEach(datapoint -> {
            switch (datapoint.getMetric()) {
                case METRIC_PREFIX + "flow.raw.packets":
                case METRIC_PREFIX + "flow.raw.bytes":
                case METRIC_PREFIX + "flow.raw.bits":
                    assertEquals(7, datapoint.getTags().size());
                    assertEquals(flowId, datapoint.getTags().get("flowid"));
                    assertEquals(direction, datapoint.getTags().get("direction"));
                    assertEquals(String.valueOf(flowStats.getTableId()), datapoint.getTags().get("tableid"));
                    assertEquals(Long.toString(cookie.getValue()), datapoint.getTags().get("cookie"));
                    assertEquals(switchId.toOtsdFormat(), datapoint.getTags().get("switchid"));
                    assertEquals(Integer.toString(flowStats.getOutPort()), datapoint.getTags().get("outPort"));
                    assertEquals(Integer.toString(flowStats.getInPort()), datapoint.getTags().get("inPort"));
                    break;
                case METRIC_PREFIX + "flow.ingress.packets":
                case METRIC_PREFIX + "flow.ingress.bytes":
                case METRIC_PREFIX + "flow.ingress.bits":
                case METRIC_PREFIX + "flow.packets":
                case METRIC_PREFIX + "flow.bytes":
                case METRIC_PREFIX + "flow.bits":
                    assertEquals(2, datapoint.getTags().size());
                    assertEquals(flowId, datapoint.getTags().get("flowid"));
                    assertEquals(direction, datapoint.getTags().get("direction"));
                    break;
                default:
                    throw new AssertionError(String.format("Unknown metric: %s", datapoint.getMetric()));
            }
        });
    }

    @Test
    @Ignore
    public void flowLldpStatsTest() throws Exception {
        long lldpCookie = 1;
        FlowStatsEntry stats = new FlowStatsEntry((short) 1, lldpCookie, 450, 550L, 10, 10);

        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(stats)));

        List<Datapoint> datapoints = pollDatapoints(3);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(stats.getPacketCount(),
                datapointMap.get(METRIC_PREFIX + "flow.raw.packets").getValue().longValue());
        assertEquals(stats.getByteCount(),
                datapointMap.get(METRIC_PREFIX + "flow.raw.bytes").getValue().longValue());
        assertEquals(stats.getByteCount() * 8,
                datapointMap.get(METRIC_PREFIX + "flow.raw.bits").getValue().longValue());

        datapoints.forEach(datapoint -> {
            assertEquals(7, datapoint.getTags().size());
            assertEquals("reverse", datapoint.getTags().get("direction"));
            assertEquals(String.valueOf(stats.getTableId()), datapoint.getTags().get("tableid"));
            assertEquals(String.valueOf(lldpCookie), datapoint.getTags().get("cookie"));
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals("10", datapoint.getTags().get("outPort"));
            assertEquals("10", datapoint.getTags().get("inPort"));
            assertEquals("unknown", datapoint.getTags().get("flowid"));
        });
    }

    @Test
    public void systemRulesStatsTest() throws Exception {
        FlowStatsEntry systemRuleStats = new FlowStatsEntry((short) 1, VERIFICATION_BROADCAST_RULE_COOKIE, 100L, 200L,
                10, 10);

        sendStatsMessage(new FlowStatsData(SWITCH_ID_1, Collections.singletonList(systemRuleStats)));

        List<Datapoint> datapoints = pollDatapoints(3);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(systemRuleStats.getPacketCount(),
                datapointMap.get(METRIC_PREFIX + "switch.flow.system.packets").getValue().longValue());
        assertEquals(systemRuleStats.getByteCount(),
                datapointMap.get(METRIC_PREFIX + "switch.flow.system.bytes").getValue().longValue());
        assertEquals(systemRuleStats.getByteCount() * 8,
                datapointMap.get(METRIC_PREFIX + "switch.flow.system.bits").getValue().longValue());


        datapoints.forEach(datapoint -> {
            assertEquals(2, datapoint.getTags().size());
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals(Cookie.toString(VERIFICATION_BROADCAST_RULE_COOKIE), datapoint.getTags().get("cookieHex"));
        });
    }

    @Test
    public void flowRttTest() throws IOException {
        FlowRttStatsData flowRttStatsData = FlowRttStatsData.builder()
                .flowId(flowId)
                .direction("forward")
                .t0(123456789_987654321L)
                .t1(123456789_987654321L + 1)
                .build();

        long seconds = (123456789_987654321L >> 32);
        long nanoseconds = (123456789_987654321L & 0xFFFFFFFFL);
        long timestamp = TimeUnit.NANOSECONDS.toMillis(TimeUnit.SECONDS.toNanos(seconds) + nanoseconds);

        InfoMessage infoMessage = new InfoMessage(flowRttStatsData, timestamp, UUID.randomUUID().toString(),
                Destination.WFM_STATS, null);

        sendMessage(infoMessage, statsTopologyConfig.getServer42StatsFlowRttTopic());

        List<Datapoint> datapoints = pollDatapoints(1);

        assertEquals(1, datapoints.size());

        Datapoint datapoint = datapoints.get(0);

        assertEquals(METRIC_PREFIX + "flow.rtt", datapoint.getMetric());
        assertEquals(1, datapoint.getValue());
        assertEquals("forward", datapoint.getTags().get("direction"));
        assertEquals(flowId, datapoint.getTags().get("flowid"));
        assertEquals(timestamp, datapoint.getTime().longValue());
    }


    @Test
    public void tableStatsTest() throws IOException {
        TableStatsEntry entry = TableStatsEntry.builder()
                .tableId(1)
                .activeEntries(100)
                .lookupCount(2000)
                .matchedCount(1900)
                .build();
        SwitchTableStatsData tableStatsData = SwitchTableStatsData.builder()
                .switchId(SWITCH_ID_1)
                .tableStatsEntries(ImmutableList.of(entry))
                .build();

        sendStatsMessage(tableStatsData);

        List<Datapoint> datapoints = pollDatapoints(4);

        Map<String, Datapoint> datapointMap = createDatapointMap(datapoints);

        assertEquals(entry.getActiveEntries(),
                datapointMap.get(METRIC_PREFIX + "switch.table.active").getValue().intValue());
        assertEquals(entry.getLookupCount(),
                datapointMap.get(METRIC_PREFIX + "switch.table.lookup").getValue().longValue());
        assertEquals(entry.getMatchedCount(),
                datapointMap.get(METRIC_PREFIX + "switch.table.matched").getValue().longValue());
        assertEquals(entry.getLookupCount() - entry.getMatchedCount(),
                datapointMap.get(METRIC_PREFIX + "switch.table.missed").getValue().longValue());


        datapoints.forEach(datapoint -> {
            assertEquals(SWITCH_ID_1.toOtsdFormat(), datapoint.getTags().get("switchid"));
            assertEquals(entry.getTableId(), Integer.parseInt(datapoint.getTags().get("tableid")));
            assertEquals(timestamp, datapoint.getTime().longValue());
        });
    }


    private Flow createFlow(SwitchId switchId, String flowId) {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();

        Switch sw = Switch.builder().switchId(switchId).build();
        switchRepository.add(sw);

        Flow flow = new TestFlowBuilder(flowId)
                .srcSwitch(sw)
                .srcPort(1)
                .srcVlan(5)
                .destSwitch(sw)
                .destPort(2)
                .destVlan(5)
                .cookie(MAIN_FORWARD_COOKIE.getValue())
                .meterId(456)
                .transitEncapsulationId(ENCAPSULATION_ID)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .build();

        FlowRepository flowRepository = repositoryFactory.createFlowRepository();
        flowRepository.add(flow);
        return flow;
    }

    private void sendStatsMessage(InfoData infoData) throws IOException {
        InfoMessage infoMessage = new InfoMessage(infoData, timestamp, UUID.randomUUID().toString(),
                Destination.WFM_STATS, null);
        sendMessage(infoMessage, statsTopologyConfig.getKafkaStatsTopic());
    }

    private void sendRemoveFlowCommand(Flow flow, FlowPath flowPath) throws IOException {
        RemoveFlow removeFlow = RemoveFlow.builder()
                .transactionId(TRANSACTION_ID)
                .flowId(flow.getFlowId())
                .cookie(flowPath.getCookie().getValue())
                .switchId(flow.getSrcSwitchId())
                .meterId(flowPath.getMeterId().getValue())
                .build();
        sendFlowCommand(removeFlow);
    }

    private void sendInstallOneSwitchFlowCommand(Flow flow, FlowPath flowPath) throws IOException {
        boolean isForward = flow.isForward(flowPath);
        InstallOneSwitchFlow installOneSwitchFlow = new InstallOneSwitchFlow(
                TRANSACTION_ID,
                flow.getFlowId(),
                flowPath.getCookie().getValue(),
                flow.getSrcSwitchId(),
                flow.getSrcPort(),
                flow.getDestPort(),
                flow.getSrcVlan(), 0,
                flow.getDestVlan(), 0,
                OutputVlanType.PUSH,
                flow.getBandwidth(),
                flowPath.getMeterId().getValue(),
                false,
                (isForward && flow.getDetectConnectedDevices().isSrcLldp())
                        || (!isForward && flow.getDetectConnectedDevices().isDstLldp()),
                false
        );
        sendFlowCommand(installOneSwitchFlow);
    }

    private void sendInstallIngressCommand(FlowSegmentCookie cookie) throws IOException {
        IngressFlowSegmentInstallRequest command = IngressFlowSegmentInstallRequest.builder()
                .commandId(UUID.randomUUID())
                .messageContext(new MessageContext())
                .endpoint(new FlowEndpoint(SWITCH_ID_1, PORT_1))
                .egressSwitchId(SWITCH_ID_3)
                .metadata(new FlowSegmentMetadata(flowId, cookie, false))
                .encapsulation(new FlowTransitEncapsulation(1, FlowEncapsulationType.TRANSIT_VLAN))
                .build();
        sendSpeakerCommand(command);
    }

    private void sendInstallEgressCommand(FlowSegmentCookie cookie, SwitchId switchId, int port) throws IOException {
        EgressFlowSegmentInstallRequest command = EgressFlowSegmentInstallRequest.builder()
                .commandId(UUID.randomUUID())
                .messageContext(new MessageContext())
                .ingressEndpoint(new FlowEndpoint(SWITCH_ID_3, PORT_3))
                .endpoint(new FlowEndpoint(switchId, port))
                .metadata(new FlowSegmentMetadata(flowId, cookie, false))
                .encapsulation(new FlowTransitEncapsulation(1, FlowEncapsulationType.TRANSIT_VLAN))
                .build();
        sendSpeakerCommand(command);
    }

    private void sendInstallTransitCommand(FlowSegmentCookie cookie) throws IOException {
        TransitFlowSegmentInstallRequest command = TransitFlowSegmentInstallRequest.builder()
                .commandId(UUID.randomUUID())
                .switchId(SWITCH_ID_2)
                .messageContext(new MessageContext())
                .ingressIslPort(cookie.getDirection().equals(FORWARD) ? PORT_1 : PORT_2)
                .egressIslPort(cookie.getDirection().equals(FORWARD) ? PORT_2 : PORT_1)
                .metadata(new FlowSegmentMetadata(flowId, cookie, false))
                .encapsulation(new FlowTransitEncapsulation(1, FlowEncapsulationType.TRANSIT_VLAN))
                .build();
        sendSpeakerCommand(command);
    }

    private void sendRemoveIngressCommand(FlowSegmentCookie cookie) throws IOException {
        IngressFlowSegmentRemoveRequest command = IngressFlowSegmentRemoveRequest.builder()
                .commandId(UUID.randomUUID())
                .endpoint(new FlowEndpoint(SWITCH_ID_1, PORT_1))
                .egressSwitchId(SWITCH_ID_3)
                .messageContext(new MessageContext())
                .metadata(new FlowSegmentMetadata(flowId, cookie, false))
                .encapsulation(new FlowTransitEncapsulation(1, FlowEncapsulationType.TRANSIT_VLAN))
                .build();
        sendSpeakerCommand(command);
    }

    private void sendFlowCommand(BaseFlow flowCommand) throws IOException {
        CommandMessage commandMessage = new CommandMessage(flowCommand, timestamp, UUID.randomUUID().toString());
        sendMessage(commandMessage, statsTopologyConfig.getKafkaSpeakerFlowHsTopic());
    }

    private void sendSpeakerCommand(SpeakerRequest speakerRequest) throws IOException {
        sendMessage(speakerRequest, statsTopologyConfig.getKafkaSpeakerFlowHsTopic());
    }

    private void sendMessage(Object object, String topic) throws IOException {
        String request = objectMapper.writeValueAsString(object);
        kProducer.pushMessage(topic, request);
    }

    private List<Datapoint> pollDatapoints(int expectedDatapointCount) {
        List<Datapoint> datapoints = new ArrayList<>();

        for (int i = 0; i < expectedDatapointCount; i++) {
            ConsumerRecord<String, String> record = null;
            try {
                record = otsdbConsumer.pollMessage(POLL_TIMEOUT);
                if (record == null) {
                    throw new AssertionError(String.format(POLL_DATAPOINT_ASSERT_MESSAGE,
                            expectedDatapointCount, datapoints.size()));
                }
                Datapoint datapoint = objectMapper.readValue(record.value(), Datapoint.class);
                datapoints.add(datapoint);
            } catch (InterruptedException e) {
                throw new AssertionError(String.format(POLL_DATAPOINT_ASSERT_MESSAGE,
                        expectedDatapointCount, datapoints.size()));
            } catch (IOException e) {
                throw new AssertionError(String.format("Could not parse datapoint object: '%s'", record.value()));
            }
        }
        try {
            // ensure that we received exact expected count of records
            ConsumerRecord<String, String> record = otsdbConsumer.pollMessage(POLL_TIMEOUT);
            if (record != null) {
                throw new AssertionError(String.format(
                        "Got more then %d records. Extra record '%s'", expectedDatapointCount, record));
            }
        } catch (InterruptedException e) {
            return datapoints;
        }
        return datapoints;
    }

    private void assertMetric(long expected, String metricName, Map<String, Datapoint> datapointMap) {
        assertEquals(expected, datapointMap.get(METRIC_PREFIX + metricName).getValue().longValue());
    }

    private Map<String, Datapoint> createDatapointMap(List<Datapoint> datapoints) {
        return datapoints
                .stream()
                .collect(Collectors.toMap(Datapoint::getMetric, Function.identity()));
    }
}
