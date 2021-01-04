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

package org.openkilda.wfm.topology;

import static java.lang.String.format;
import static org.openkilda.bluegreen.kafka.Utils.CONSUMER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.bluegreen.kafka.Utils.CONSUMER_RUN_ID_PROPERTY;
import static org.openkilda.bluegreen.kafka.Utils.CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;
import static org.openkilda.bluegreen.kafka.Utils.PRODUCER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.bluegreen.kafka.Utils.PRODUCER_RUN_ID_PROPERTY;
import static org.openkilda.bluegreen.kafka.Utils.PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;

import org.openkilda.bluegreen.kafka.interceptors.VersioningConsumerInterceptor;
import org.openkilda.bluegreen.kafka.interceptors.VersioningProducerInterceptor;
import org.openkilda.config.KafkaConfig;
import org.openkilda.config.ZookeeperConfig;
import org.openkilda.config.naming.KafkaNamingStrategy;
import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.config.naming.TopologyNamingStrategy;
import org.openkilda.wfm.config.provider.MultiPrefixConfigurationProvider;
import org.openkilda.wfm.error.ConfigurationException;
import org.openkilda.wfm.error.NameCollisionException;
import org.openkilda.wfm.kafka.AbstractMessageDeserializer;
import org.openkilda.wfm.kafka.CustomNamedSubscription;
import org.openkilda.wfm.kafka.MessageDeserializer;
import org.openkilda.wfm.kafka.MessageSerializer;
import org.openkilda.wfm.kafka.ObjectSerializer;
import org.openkilda.wfm.topology.utils.AbstractMessageTranslator;
import org.openkilda.wfm.topology.utils.MessageKafkaTranslator;

import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.StormSubmitter;
import org.apache.storm.flux.model.BoltDef;
import org.apache.storm.flux.model.PropertyDef;
import org.apache.storm.flux.model.SpoutDef;
import org.apache.storm.flux.model.TopologyDef;
import org.apache.storm.flux.parser.FluxParser;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.kafka.bolt.selector.DefaultTopicSelector;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.spout.SleepSpoutWaitStrategy;
import org.apache.storm.thrift.TException;
import org.apache.storm.topology.BoltDeclarer;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.IRichSpout;
import org.apache.storm.topology.SpoutDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.kohsuke.args4j.CmdLineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Represents abstract topology.
 */
public abstract class AbstractTopology<T extends AbstractTopologyConfig> implements Topology {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    public static final String BOLT_ID_CTRL_ROUTE = "ctrl.route";

    public static final String KEY_FIELD = "key";
    public static final String MESSAGE_FIELD = "message";
    public static final Fields fieldMessage = new Fields(MESSAGE_FIELD);
    public static final Fields FIELDS_KEY = new Fields(KEY_FIELD);

    protected final String topologyName;

    protected final KafkaNamingStrategy kafkaNamingStrategy;
    protected final TopologyNamingStrategy topoNamingStrategy;
    protected final MultiPrefixConfigurationProvider configurationProvider;

    protected final TopologyDef topologyDef;
    protected final T topologyConfig;
    private final KafkaConfig kafkaConfig;



    private final ZookeeperConfig zookeeperConfig;

    protected AbstractTopology(LaunchEnvironment env, String topologyDefinitionName, Class<T> topologyConfigClass) {
        kafkaNamingStrategy = env.getKafkaNamingStrategy();
        topoNamingStrategy = env.getTopologyNamingStrategy();

        topologyDef = loadTopologyDef(topologyDefinitionName, env.getProperties()).orElse(null);

        String defaultTopologyName = getClass().getSimpleName().toLowerCase();
        // Use the default topology name with naming strategy applied only if no specific name provided via CLI.
        topologyName = Optional.ofNullable(env.getTopologyName())
                .orElseGet(() -> topoNamingStrategy.stormTopologyName(defaultTopologyName));

        configurationProvider = env.getConfigurationProvider(topologyName,
                TOPOLOGY_PROPERTIES_DEFAULTS_PREFIX + defaultTopologyName);

        topologyConfig = configurationProvider.getConfiguration(topologyConfigClass);
        kafkaConfig = configurationProvider.getConfiguration(KafkaConfig.class);
        zookeeperConfig = configurationProvider.getConfiguration(ZookeeperConfig.class);
        logger.debug("Topology built {}: kafka={}, parallelism={}, workers={}",
                topologyName, kafkaConfig.getHosts(), getTopologyParallelism(), getTopologyWorkers());
        logger.info("Starting topology {} in {} mode", topologyName, topologyConfig.getBlueGreenMode());
    }

    private Optional<TopologyDef> loadTopologyDef(String topologyDefinitionName, Properties properties) {
        try {
            // Check the definition in resources for existence.
            String yamlResource = format("/%s.yaml", topologyDefinitionName);
            if (FluxParser.class.getResourceAsStream(yamlResource) == null) {
                return Optional.empty();
            }

            return Optional.of(FluxParser.parseResource(yamlResource, false, true, properties, false));
        } catch (Exception e) {
            logger.info("Unable to load topology configuration (definition) file", e);
            return Optional.empty();
        }
    }

    private Optional<Integer> getTopologyParallelism() {
        if (topologyDef != null && topologyDef.getConfig() != null) {
            return Optional.of((Integer) topologyDef.getConfig().get("topology.parallelism"));
        }
        return Optional.empty();
    }

    private Optional<Integer> getTopologyWorkers() {
        if (topologyDef != null && topologyDef.getConfig() != null) {
            return Optional.of((Integer) topologyDef.getConfig().get("topology.workers"));
        }
        return Optional.empty();
    }

    protected void setup() throws TException, NameCollisionException {
        if (topologyConfig.getUseLocalCluster()) {
            setupLocal();
        } else {
            setupRemote();
        }
    }

    private void setupRemote() throws TException, NameCollisionException {
        Config config = makeStormConfig();
        config.setDebug(false);

        logger.info("Submit Topology: {}", topologyName);
        StormSubmitter.submitTopology(topologyName, config, createTopology());
    }

    private void setupLocal() throws NameCollisionException {
        Config config = makeStormConfig();
        config.setDebug(true);

        LocalCluster cluster = new LocalCluster();
        cluster.submitTopology(topologyName, config, createTopology());

        logger.info("Start Topology: {} (local)", topologyName);
        localExecutionMainLoop();

        cluster.shutdown();
    }

    protected static int handleLaunchException(Exception error) {
        final Logger log = LoggerFactory.getLogger(AbstractTopology.class);

        int errorCode;
        try {
            throw error;
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println("Allowed options and arguments:");
            e.getParser().printUsage(System.err);
            errorCode = 2;
        } catch (ConfigurationException e) {
            System.err.println(e.getMessage());
            errorCode = 3;
        } catch (TException e) {
            log.error("Unable to complete topology setup: {}", e.getMessage());
            errorCode = 4;
        } catch (Exception e) {
            log.error("Unhandled exception", e);
            errorCode = 1;
        }

        return errorCode;
    }

    private Properties getKafkaProducerProperties() {
        Properties kafka = new Properties();

        kafka.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafka.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafka.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getHosts());
        kafka.setProperty(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningProducerInterceptor.class.getName());
        kafka.setProperty(PRODUCER_COMPONENT_NAME_PROPERTY, getZkTopoName());
        kafka.setProperty(PRODUCER_RUN_ID_PROPERTY, topologyConfig.getBlueGreenMode());
        kafka.setProperty(PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, getZookeeperConfig().getConnectString());

        return kafka;
    }

    private Config makeStormConfig() {
        Config stormConfig = new Config();

        getTopologyWorkers().ifPresent(stormConfig::setNumWorkers);
        if (topologyConfig.getDisruptorWaitTimeout() != null) {
            stormConfig.put(Config.TOPOLOGY_DISRUPTOR_WAIT_TIMEOUT_MILLIS, topologyConfig.getDisruptorWaitTimeout());
        }
        if (topologyConfig.getDisruptorBatchTimeout() != null) {
            stormConfig.put(Config.TOPOLOGY_DISRUPTOR_BATCH_TIMEOUT_MILLIS, topologyConfig.getDisruptorBatchTimeout());
        }
        if (topologyConfig.getSpoutWaitSleepTime() != null) {
            stormConfig.put(Config.TOPOLOGY_SPOUT_WAIT_STRATEGY, SleepSpoutWaitStrategy.class.getName());
            stormConfig.put(Config.TOPOLOGY_SLEEP_SPOUT_WAIT_STRATEGY_TIME_MS, topologyConfig.getSpoutWaitSleepTime());
        }
        if (topologyConfig.getUseLocalCluster()) {
            getTopologyParallelism().ifPresent(stormConfig::setMaxTaskParallelism);
        }
        if (topologyDef != null && topologyDef.getConfig() != null) {
            stormConfig.putAll(topologyDef.getConfig());
        }

        return stormConfig;
    }

    protected void localExecutionMainLoop() {
        logger.info("Sleep while local topology is executing");
        try {
            Thread.sleep(topologyConfig.getLocalExecutionTime());
        } catch (InterruptedException e) {
            logger.warn("Execution process have been interrupted.");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public final String getTopologyName() {
        return topologyName;
    }

    @VisibleForTesting
    public final T getConfig() {
        return topologyConfig;
    }

    protected SpoutDeclarer declareKafkaSpout(TopologyBuilder builder, String topic, String spoutId) {
        return declareKafkaSpout(builder, Collections.singletonList(topic), spoutId);
    }

    protected SpoutDeclarer declareKafkaSpout(TopologyBuilder builder, List<String> topics, String spoutId) {
        KafkaSpoutConfig<String, Message> config = getKafkaSpoutConfigBuilder(topics, spoutId)
                .build();
        logger.info("Setup kafka spout: id={}, group={}, subscriptions={}",
                spoutId, config.getConsumerGroupId(), config.getSubscription().getTopicsString());
        return declareSpout(builder, new KafkaSpout<>(config), spoutId);
    }

    protected SpoutDeclarer declareSpout(TopologyBuilder builder, IRichSpout spout, String spoutId) {
        Integer spoutParallelism = null;
        Integer spoutNumTasks = null;
        Integer spoutMaxSpoutPending = null;
        if (topologyDef != null) {
            SpoutDef spoutDef = topologyDef.getSpoutDef(spoutId);
            if (spoutDef != null) {
                spoutParallelism = spoutDef.getParallelism();
                if (spoutDef.getNumTasks() > 0) {
                    spoutNumTasks = spoutDef.getNumTasks();
                }
                if (spoutDef.getProperties() != null) {
                    for (PropertyDef propertyDef : spoutDef.getProperties()) {
                        if (propertyDef.getName().equals("max.spout.pending")) {
                            spoutMaxSpoutPending = (Integer) propertyDef.getValue();
                            break;
                        }
                    }
                }
            }
        }
        if (spoutParallelism == null) {
            if (topologyDef != null && topologyDef.getConfig() != null) {
                spoutParallelism = (Integer) topologyDef.getConfig().get("topology.spouts.parallelism");
            }
            if (spoutParallelism == null) {
                spoutParallelism = getTopologyParallelism().orElse(null);
            }
        }
        SpoutDeclarer spoutDeclarer = builder.setSpout(spoutId, spout, spoutParallelism);
        if (spoutNumTasks != null) {
            spoutDeclarer.setNumTasks(spoutNumTasks);
        }
        if (spoutMaxSpoutPending != null) {
            spoutDeclarer.setMaxSpoutPending(spoutMaxSpoutPending);
        }
        return spoutDeclarer;
    }

    /**
     * Creates Kafka spout. Transforms received value to {@link AbstractMessage}.
     *
     * @param topic Kafka topic
     */
    protected SpoutDeclarer declareKafkaSpoutForAbstractMessage(TopologyBuilder builder, String topic, String spoutId) {
        return declareKafkaSpoutForAbstractMessage(
                builder, Collections.singletonList(topic), spoutId);
    }

    /**
     * Creates Kafka spout with list of topics. Transforms received value to {@link AbstractMessage}.
     *
     * @param topics Kafka topics
     */
    protected SpoutDeclarer declareKafkaSpoutForAbstractMessage(
            TopologyBuilder builder, List<String> topics, String spoutId) {
        KafkaSpout<?, ?> spout = new KafkaSpout<>(
                makeKafkaSpoutConfig(topics, spoutId, AbstractMessageDeserializer.class)
                        .setRecordTranslator(new AbstractMessageTranslator())
                        .build());
        return declareSpout(builder, spout, spoutId);
    }

    /**
     * Creates Kafka bolt.
     *
     * @param topic Kafka topic
     * @return {@link KafkaBolt}
     */
    protected KafkaBolt<String, String> createKafkaBolt(String topic) {
        return new KafkaBolt<String, String>()
                .withProducerProperties(getKafkaProducerProperties())
                .withTopicSelector(new DefaultTopicSelector(topic))
                .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper<>());
    }

    /**
     * Creates Kafka bolt, that uses {@link MessageSerializer} in order to serialize an object.
     *
     * @param topic Kafka topic
     * @return {@link KafkaBolt}
     */
    protected KafkaBolt<String, Message> buildKafkaBolt(final String topic) {
        return makeKafkaBolt(topic, MessageSerializer.class);
    }

    /**
     * Creates Kafka bolt, that uses {@link ObjectSerializer} in order to serialize an object.
     *
     * @param topic Kafka topic
     * @return {@link KafkaBolt}
     */
    protected KafkaBolt<String, Object> buildKafkaBoltWithRawObject(final String topic) {
        Properties properties = getKafkaProducerProperties();
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ObjectSerializer.class.getName());

        return new KafkaBolt<String, Object>()
                .withProducerProperties(properties)
                .withTopicSelector(new DefaultTopicSelector(topic))
                .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper<>());
    }

    protected KafkaSpoutConfig.Builder<String, Message> getKafkaSpoutConfigBuilder(String topic, String spoutId) {
        return getKafkaSpoutConfigBuilder(Collections.singletonList(topic), spoutId);
    }

    private KafkaSpoutConfig.Builder<String, Message> getKafkaSpoutConfigBuilder(List<String> topics, String spoutId) {
        return makeKafkaSpoutConfig(topics, spoutId, MessageDeserializer.class)
                .setRecordTranslator(new MessageKafkaTranslator());
    }

    protected <V> KafkaSpoutConfig.Builder<String, V> makeKafkaSpoutConfig(
            List<String> topics, String spoutId, Class<? extends Deserializer<V>> valueDecoder) {
        KafkaSpoutConfig.Builder<String, V> config = new KafkaSpoutConfig.Builder<>(
                kafkaConfig.getHosts(), new CustomNamedSubscription(topics));

        config.setProp(ConsumerConfig.GROUP_ID_CONFIG, makeKafkaGroupName(spoutId))
                .setFirstPollOffsetStrategy(KafkaSpoutConfig.FirstPollOffsetStrategy.LATEST)
                .setProp(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true)
                .setProp(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .setProp(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDecoder)
                .setProp(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningConsumerInterceptor.class.getName())
                .setProp(CONSUMER_COMPONENT_NAME_PROPERTY, getZkTopoName())
                .setProp(CONSUMER_RUN_ID_PROPERTY, topologyConfig.getBlueGreenMode())
                .setProp(CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, getZookeeperConfig().getConnectString())
                .setTupleTrackingEnforced(true);

        return config;
    }

    private String makeKafkaGroupName(String spoutId) {
        return kafkaNamingStrategy.kafkaConsumerGroupName(format("%s__%s", topologyName, spoutId));
    }

    protected <V> KafkaBolt<String, V> makeKafkaBolt(String topic, Class<? extends Serializer<V>> valueEncoder) {
        return makeKafkaBolt(valueEncoder)
                .withTopicSelector(topic);
    }

    protected <V> KafkaBolt<String, V> makeKafkaBolt(Class<? extends Serializer<V>> valueEncoder) {
        Properties properties = getKafkaProducerProperties();
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueEncoder.getName());

        return new KafkaBolt<String, V>()
                .withProducerProperties(properties)
                .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper<>());
    }

    protected BoltDeclarer declareBolt(TopologyBuilder builder, IRichBolt bolt, String boltId) {
        Integer boltParallelism = null;
        Integer boltNumTasks = null;
        if (topologyDef != null) {
            BoltDef boltDef = topologyDef.getBoltDef(boltId);
            if (boltDef != null) {
                boltParallelism = boltDef.getParallelism();
                if (boltDef.getNumTasks() > 0) {
                    boltNumTasks = boltDef.getNumTasks();
                }
            }
        }
        if (boltParallelism == null) {
            if (topologyDef != null && topologyDef.getConfig() != null) {
                boltParallelism = (Integer) topologyDef.getConfig().get("topology.bolts.parallelism");
            }
            if (boltParallelism == null) {
                boltParallelism = getTopologyParallelism().orElse(null);
            }
        }
        BoltDeclarer boltDeclarer = builder.setBolt(boltId, bolt, boltParallelism);
        if (boltNumTasks != null) {
            boltDeclarer.setNumTasks(boltNumTasks);
        }
        return boltDeclarer;
    }

    protected ZookeeperConfig getZookeeperConfig() {
        return zookeeperConfig;
    }

    protected abstract String getZkTopoName();
}
