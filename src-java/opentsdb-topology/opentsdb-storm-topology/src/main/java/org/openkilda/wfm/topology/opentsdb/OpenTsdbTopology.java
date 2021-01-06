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

package org.openkilda.wfm.topology.opentsdb;

import org.openkilda.messaging.info.InfoData;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.error.ConfigurationException;
import org.openkilda.wfm.kafka.InfoDataDeserializer;
import org.openkilda.wfm.share.zk.ZkStreams;
import org.openkilda.wfm.share.zk.ZooKeeperBolt;
import org.openkilda.wfm.share.zk.ZooKeeperSpout;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.opentsdb.OpenTsdbTopologyConfig.OpenTsdbConfig;
import org.openkilda.wfm.topology.opentsdb.bolts.DatapointParseBolt;
import org.openkilda.wfm.topology.opentsdb.bolts.MonitoringBolt;
import org.openkilda.wfm.topology.opentsdb.bolts.OpenTSDBFilterBolt;
import org.openkilda.wfm.topology.opentsdb.bolts.OpenTsdbTelnetProtocolBolt;
import org.openkilda.wfm.topology.opentsdb.spout.MonitoringKafkaSpoutProxy;
import org.openkilda.wfm.topology.utils.InfoDataTranslator;

import com.google.common.annotations.VisibleForTesting;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.opentsdb.bolt.ITupleOpenTsdbDatapointMapper;
import org.apache.storm.opentsdb.bolt.OpenTsdbBolt;
import org.apache.storm.opentsdb.bolt.TupleOpenTsdbDatapointMapper;
import org.apache.storm.opentsdb.client.OpenTsdbClient;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;

import java.util.Collections;
import java.util.List;

/**
 * Apache Storm topology for sending metrics into Open TSDB.
 */
public class OpenTsdbTopology extends AbstractTopology<OpenTsdbTopologyConfig> {

    public OpenTsdbTopology(LaunchEnvironment env) {
        super(env, "opentsdb-topology", OpenTsdbTopologyConfig.class);
    }

    @VisibleForTesting
    static final String OTSDB_SPOUT_ID = "kilda.otsdb-spout";
    private static final String OTSDB_BOLT_ID = "otsdb-bolt";
    private static final String OTSDB_FILTER_BOLT_ID = OpenTSDBFilterBolt.class.getSimpleName();
    private static final String OTSDB_PARSE_BOLT_ID = DatapointParseBolt.class.getSimpleName();

    @Override
    public StormTopology createTopology() throws ConfigurationException {
        logger.info("Creating OpenTsdbTopology - {}", topologyName);

        TopologyBuilder tb = new TopologyBuilder();

        ZooKeeperSpout zooKeeperSpout = new ZooKeeperSpout(getConfig().getBlueGreenMode(), getZkTopoName(),
                getZookeeperConfig().getConnectString());
        declareSpout(tb, zooKeeperSpout, ZooKeeperSpout.SPOUT_ID);


        ZooKeeperBolt zooKeeperBolt = new ZooKeeperBolt(getConfig().getBlueGreenMode(), getZkTopoName(),
                getZookeeperConfig().getConnectString());
        declareBolt(tb, zooKeeperBolt, ZooKeeperBolt.BOLT_ID)
                .allGrouping(OTSDB_PARSE_BOLT_ID, ZkStreams.ZK.toString());

        attachInput(tb);

        monitoring(tb);

        declareBolt(tb, new DatapointParseBolt(), OTSDB_PARSE_BOLT_ID)
                .shuffleGrouping(OTSDB_SPOUT_ID)
                .allGrouping(ZooKeeperSpout.SPOUT_ID);

        declareBolt(tb, new OpenTSDBFilterBolt(), OTSDB_FILTER_BOLT_ID)
                .fieldsGrouping(OTSDB_PARSE_BOLT_ID, new Fields("hash"));

        attachOutput(tb);

        return tb.createTopology();
    }

    private void attachInput(TopologyBuilder topology) {
        String otsdbTopic = topologyConfig.getKafkaOtsdbTopic();

        //FIXME: We have to use the Message class for messaging.
        KafkaSpoutConfig<String, InfoData> config = getKafkaSpoutConfigBuilder(otsdbTopic, OTSDB_SPOUT_ID,
                getZkTopoName(), getConfig().getBlueGreenMode())
                .setValue(InfoDataDeserializer.class)
                .setRecordTranslator(new InfoDataTranslator())
                .setFirstPollOffsetStrategy(KafkaSpoutConfig.FirstPollOffsetStrategy.UNCOMMITTED_EARLIEST)
                .setTupleTrackingEnforced(true)
                .build();

        KafkaSpout<String, InfoData> kafkaSpout = new KafkaSpout<>(config);
        MonitoringKafkaSpoutProxy<String, InfoData> proxy = new MonitoringKafkaSpoutProxy<>(kafkaSpout);
        declareSpout(topology, proxy, OTSDB_SPOUT_ID);
    }

    private void attachOutput(TopologyBuilder topology) throws ConfigurationException {
        final String httpProtocol = "http";
        final String telnetProtocol = "telnet";

        String protocol = topologyConfig.getOpenTsdbConfig().getProtocol().toLowerCase();
        if (httpProtocol.equals(protocol)) {
            outputHttpProtocol(topology);
        } else if (telnetProtocol.equals(protocol)) {
            outputTelnetProtocol(topology);
        } else {
            throw new ConfigurationException(String.format(
                    "invalid opentsdb.protocol option value \"%s\" (valid values: \"%s\")", protocol,
                    String.join("\", \"", httpProtocol, telnetProtocol)));
        }
    }

    private void outputHttpProtocol(TopologyBuilder topology) {
        OpenTsdbConfig config = topologyConfig.getOpenTsdbConfig();

        OpenTsdbClient.Builder builder = OpenTsdbClient
                .newBuilder(String.format("http://%s:%d", config.getHost(), config.getPort()))
                .returnDetails();
        if (config.getClientChunkedRequestsEnabled()) {
            builder.enableChunkedEncoding();
        }

        OpenTsdbBolt bolt = new OpenTsdbBolt(builder, makeOutputMappers());
        bolt.withBatchSize(config.getBatchSize()).withFlushInterval(config.getFlushInterval());
        output(topology, bolt);
    }

    private void outputTelnetProtocol(TopologyBuilder topology) {
        OpenTsdbConfig config = topologyConfig.getOpenTsdbConfig();
        OpenTsdbTelnetProtocolBolt bolt = new OpenTsdbTelnetProtocolBolt(
                config.getHost(), config.getPort(), makeOutputMappers());
        output(topology, bolt);
    }

    private List<? extends ITupleOpenTsdbDatapointMapper> makeOutputMappers() {
        return Collections.singletonList(TupleOpenTsdbDatapointMapper.DEFAULT_MAPPER);
    }

    private void output(TopologyBuilder topology, BaseRichBolt bolt) {
        declareBolt(topology, bolt, OTSDB_BOLT_ID)
                .shuffleGrouping(OTSDB_FILTER_BOLT_ID)
                .shuffleGrouping(MonitoringBolt.BOLT_ID);
    }

    private void monitoring(TopologyBuilder topology) {
        MonitoringBolt bolt = new MonitoringBolt(2);
        declareBolt(topology, bolt, MonitoringBolt.BOLT_ID)
                .allGrouping(OTSDB_SPOUT_ID, MonitoringKafkaSpoutProxy.STREAM_PERFORMANCE_ID);
    }

    @Override
    protected String getZkTopoName() {
        return "opentsdb";
    }

    /**
     * main.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new OpenTsdbTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
