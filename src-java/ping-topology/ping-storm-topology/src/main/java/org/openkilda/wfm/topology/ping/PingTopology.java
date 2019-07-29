/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.topology.ping;

import org.openkilda.messaging.Message;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.ping.bolt.Blacklist;
import org.openkilda.wfm.topology.ping.bolt.ComponentId;
import org.openkilda.wfm.topology.ping.bolt.FailReporter;
import org.openkilda.wfm.topology.ping.bolt.FlowFetcher;
import org.openkilda.wfm.topology.ping.bolt.FlowStatusEncoder;
import org.openkilda.wfm.topology.ping.bolt.GroupCollector;
import org.openkilda.wfm.topology.ping.bolt.InputRouter;
import org.openkilda.wfm.topology.ping.bolt.MonotonicTick;
import org.openkilda.wfm.topology.ping.bolt.NorthboundEncoder;
import org.openkilda.wfm.topology.ping.bolt.OnDemandResultManager;
import org.openkilda.wfm.topology.ping.bolt.OtsdbEncoder;
import org.openkilda.wfm.topology.ping.bolt.PeriodicPingShaping;
import org.openkilda.wfm.topology.ping.bolt.PeriodicResultManager;
import org.openkilda.wfm.topology.ping.bolt.PingProducer;
import org.openkilda.wfm.topology.ping.bolt.PingRouter;
import org.openkilda.wfm.topology.ping.bolt.ResultDispatcher;
import org.openkilda.wfm.topology.ping.bolt.SpeakerEncoder;
import org.openkilda.wfm.topology.ping.bolt.StatsProducer;
import org.openkilda.wfm.topology.ping.bolt.TickDeduplicator;
import org.openkilda.wfm.topology.ping.bolt.TickId;
import org.openkilda.wfm.topology.ping.bolt.TimeoutManager;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.concurrent.TimeUnit;

public class PingTopology extends AbstractTopology<PingTopologyConfig> {
    private final int scaleFactor;

    protected PingTopology(LaunchEnvironment env) {
        super(env, PingTopologyConfig.class);
        scaleFactor = topologyConfig.getScaleFactor();
    }

    /**
     * Implement flow pings.
     *
     * <p>Topology sequence diagram plus design document:
     * https://github.com/telstra/open-kilda/tree/master/docs/design/flow-ping/flow-ping.md
     */
    @Override
    public StormTopology createTopology() {
        TopologyBuilder topology = new TopologyBuilder();

        monotonicTick(topology);
        tickDeduplicator(topology);

        input(topology);
        inputRouter(topology);

        flowFetcher(topology);
        periodicPingShaping(topology);
        pingProducer(topology);
        pingRouter(topology);
        blacklist(topology);
        timeoutManager(topology);
        resultDispatcher(topology);
        periodicResultManager(topology);
        onDemandResultManager(topology);
        groupCollector(topology);
        statsProducer(topology);
        failReporter(topology);

        flowStatusEncoder(topology);
        otsdbEncoder(topology);
        speakerEncoder(topology);
        northboundEncoder(topology);

        return topology.createTopology();
    }

    private void monotonicTick(TopologyBuilder topology) {
        MonotonicTick bolt = new MonotonicTick(
                new MonotonicTick.ClockConfig()
                        .addTickInterval(TickId.PERIODIC_PING, topologyConfig.getPingInterval()));

        topology.setBolt(MonotonicTick.BOLT_ID, bolt, scaleFactor);
    }

    private void tickDeduplicator(TopologyBuilder topology) {
        topology.setBolt(TickDeduplicator.BOLT_ID, new TickDeduplicator(1, TimeUnit.SECONDS), scaleFactor)
                .globalGrouping(MonotonicTick.BOLT_ID);
    }

    private void input(TopologyBuilder topology) {
        KafkaSpout<String, Message> spout = buildKafkaSpout(
                topologyConfig.getKafkaPingTopic(), ComponentId.INPUT.toString());
        topology.setSpout(ComponentId.INPUT.toString(), spout, scaleFactor);
    }

    private void inputRouter(TopologyBuilder topology) {
        InputRouter bolt = new InputRouter();
        topology.setBolt(InputRouter.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(ComponentId.INPUT.toString());
    }

    private void flowFetcher(TopologyBuilder topology) {
        PersistenceManager persistenceManager =
                PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);

        FlowFetcher bolt = new FlowFetcher(persistenceManager, topologyConfig.getPeriodicPingCacheExpirationInterval());
        topology.setBolt(FlowFetcher.BOLT_ID, bolt, scaleFactor)
                // NOTE(tdurakov): global grouping is responsible for proper handling parallelism of 2
                .globalGrouping(TickDeduplicator.BOLT_ID, TickDeduplicator.STREAM_PING_ID)
                .shuffleGrouping(InputRouter.BOLT_ID, InputRouter.STREAM_ON_DEMAND_REQUEST_ID)
                .allGrouping(InputRouter.BOLT_ID, InputRouter.STREAM_PERIODIC_PING_UPDATE_REQUEST_ID);
    }

    private void periodicPingShaping(TopologyBuilder topology) {
        PeriodicPingShaping bolt = new PeriodicPingShaping(topologyConfig.getPingInterval());
        topology.setBolt(PeriodicPingShaping.BOLT_ID, bolt, scaleFactor)
                .allGrouping(TickDeduplicator.BOLT_ID)
                .shuffleGrouping(FlowFetcher.BOLT_ID);
    }

    private void pingProducer(TopologyBuilder topology) {
        PingProducer bolt = new PingProducer();
        topology.setBolt(PingProducer.BOLT_ID, bolt, scaleFactor)
                .fieldsGrouping(PeriodicPingShaping.BOLT_ID, new Fields(PeriodicPingShaping.FIELD_ID_FLOW_ID));
    }

    private void pingRouter(TopologyBuilder topology) {
        PingRouter bolt = new PingRouter();
        topology.setBolt(PingRouter.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(PingProducer.BOLT_ID)
                .shuffleGrouping(Blacklist.BOLT_ID)
                .shuffleGrouping(InputRouter.BOLT_ID, InputRouter.STREAM_SPEAKER_PING_RESPONSE_ID)
                .shuffleGrouping(
                        PeriodicResultManager.BOLT_ID, PeriodicResultManager.STREAM_BLACKLIST_ID);
    }

    private void blacklist(TopologyBuilder topology) {
        Blacklist bolt = new Blacklist();
        Fields grouping = new Fields(PingRouter.FIELD_ID_PING_MATCH);
        topology.setBolt(Blacklist.BOLT_ID, bolt, scaleFactor)
                .fieldsGrouping(PingRouter.BOLT_ID, PingRouter.STREAM_BLACKLIST_FILTER_ID, grouping)
                .fieldsGrouping(PingRouter.BOLT_ID, PingRouter.STREAM_BLACKLIST_UPDATE_ID, grouping);
    }

    private void timeoutManager(TopologyBuilder topology) {
        TimeoutManager bolt = new TimeoutManager(topologyConfig.getTimeout());
        final Fields pingIdGrouping = new Fields(PingRouter.FIELD_ID_PING_ID);
        topology.setBolt(TimeoutManager.BOLT_ID, bolt, scaleFactor)
                .allGrouping(TickDeduplicator.BOLT_ID)
                .fieldsGrouping(PingRouter.BOLT_ID, PingRouter.STREAM_REQUEST_ID, pingIdGrouping)
                .fieldsGrouping(PingRouter.BOLT_ID, PingRouter.STREAM_RESPONSE_ID, pingIdGrouping);
    }

    private void resultDispatcher(TopologyBuilder topology) {
        ResultDispatcher bolt = new ResultDispatcher();
        topology.setBolt(ResultDispatcher.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(TimeoutManager.BOLT_ID, TimeoutManager.STREAM_RESPONSE_ID);
    }

    private void periodicResultManager(TopologyBuilder topology) {
        PeriodicResultManager bolt = new PeriodicResultManager();
        topology.setBolt(PeriodicResultManager.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(ResultDispatcher.BOLT_ID, ResultDispatcher.STREAM_PERIODIC_ID);
    }

    private void onDemandResultManager(TopologyBuilder topology) {
        OnDemandResultManager bolt = new OnDemandResultManager();
        topology.setBolt(OnDemandResultManager.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(ResultDispatcher.BOLT_ID, ResultDispatcher.STREAM_MANUAL_ID)
                .shuffleGrouping(GroupCollector.BOLT_ID, GroupCollector.STREAM_ON_DEMAND_ID);
    }

    private void groupCollector(TopologyBuilder topology) {
        GroupCollector bolt = new GroupCollector(topologyConfig.getTimeout());
        topology.setBolt(GroupCollector.BOLT_ID, bolt, scaleFactor)
                .allGrouping(TickDeduplicator.BOLT_ID)
                .fieldsGrouping(
                        OnDemandResultManager.BOLT_ID, OnDemandResultManager.STREAM_GROUP_ID,
                        new Fields(OnDemandResultManager.FIELD_ID_GROUP_ID));
    }

    private void statsProducer(TopologyBuilder topology) {
        StatsProducer bolt = new StatsProducer(topologyConfig.getMetricPrefix());
        topology.setBolt(StatsProducer.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(PeriodicResultManager.BOLT_ID, PeriodicResultManager.STREAM_STATS_ID);
    }

    private void failReporter(TopologyBuilder topology) {
        FailReporter bolt = new FailReporter(
                topologyConfig.getFailDelay(), topologyConfig.getFailReset());

        Fields groupBy = new Fields(PeriodicResultManager.FIELD_ID_FLOW_ID);
        topology.setBolt(FailReporter.BOLT_ID, bolt, scaleFactor)
                .allGrouping(TickDeduplicator.BOLT_ID)
                .allGrouping(FlowFetcher.BOLT_ID, FlowFetcher.STREAM_EXPIRE_CACHE_ID)
                .fieldsGrouping(PeriodicResultManager.BOLT_ID, PeriodicResultManager.STREAM_FAIL_ID, groupBy);
    }

    private void flowStatusEncoder(TopologyBuilder topology) {
        FlowStatusEncoder bolt = new FlowStatusEncoder();
        topology.setBolt(FlowStatusEncoder.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(FailReporter.BOLT_ID);

        KafkaBolt output = buildKafkaBolt(topologyConfig.getKafkaFlowStatusTopic());
        topology.setBolt(ComponentId.FLOW_STATUS_OUTPUT.toString(), output, scaleFactor)
                .shuffleGrouping(FlowStatusEncoder.BOLT_ID);
    }

    private void otsdbEncoder(TopologyBuilder topology) {
        OtsdbEncoder bolt = new OtsdbEncoder();
        topology.setBolt(OtsdbEncoder.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(StatsProducer.BOLT_ID);

        KafkaBolt output = createKafkaBolt(topologyConfig.getKafkaOtsdbTopic());
        topology.setBolt(ComponentId.OTSDB_OUTPUT.toString(), output, scaleFactor)
                .shuffleGrouping(OtsdbEncoder.BOLT_ID);
    }

    private void speakerEncoder(TopologyBuilder topology) {
        SpeakerEncoder bolt = new SpeakerEncoder();
        topology.setBolt(SpeakerEncoder.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(TimeoutManager.BOLT_ID, TimeoutManager.STREAM_REQUEST_ID);

        KafkaBolt output = buildKafkaBolt(topologyConfig.getKafkaSpeakerFlowPingTopic());
        topology.setBolt(ComponentId.SPEAKER_OUTPUT.toString(), output, scaleFactor)
                .shuffleGrouping(SpeakerEncoder.BOLT_ID);
    }

    private void northboundEncoder(TopologyBuilder topology) {
        NorthboundEncoder bolt = new NorthboundEncoder();
        topology.setBolt(NorthboundEncoder.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(FlowFetcher.BOLT_ID, FlowFetcher.STREAM_ON_DEMAND_RESPONSE_ID)
                .shuffleGrouping(OnDemandResultManager.BOLT_ID);

        KafkaBolt output = buildKafkaBolt(topologyConfig.getKafkaNorthboundTopic());
        topology.setBolt(ComponentId.NORTHBOUND_OUTPUT.toString(), output, scaleFactor)
                .shuffleGrouping(NorthboundEncoder.BOLT_ID);
    }

    /**
     * Topology entry point.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new PingTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
