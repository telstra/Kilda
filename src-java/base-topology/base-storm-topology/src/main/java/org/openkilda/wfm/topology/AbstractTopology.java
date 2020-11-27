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
import static org.openkilda.messaging.Utils.COMMON_COMPONENT_NAME;
import static org.openkilda.messaging.Utils.COMMON_COMPONENT_RUN_ID;
import static org.openkilda.messaging.Utils.CONSUMER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;

import org.openkilda.config.KafkaConfig;
import org.openkilda.config.ZookeeperConfig;
import org.openkilda.config.naming.KafkaNamingStrategy;
import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.kafka.versioning.VersioningConsumerInterceptor;
import org.openkilda.messaging.kafka.versioning.VersioningProducerInterceptor;
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
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.kafka.bolt.selector.DefaultTopicSelector;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.spout.KafkaSpoutConfig;
import org.apache.storm.spout.SleepSpoutWaitStrategy;
import org.apache.storm.thrift.TException;
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

    protected final T topologyConfig;
    private final KafkaConfig kafkaConfig;



    private final ZookeeperConfig zookeeperConfig;

    protected AbstractTopology(LaunchEnvironment env, Class<T> topologyConfigClass) {
        kafkaNamingStrategy = env.getKafkaNamingStrategy();
        topoNamingStrategy = env.getTopologyNamingStrategy();

        String defaultTopologyName = getDefaultTopologyName();
        // Use the default topology name with naming strategy applied only if no specific name provided via CLI.
        topologyName = Optional.ofNullable(env.getTopologyName())
                .orElse(topoNamingStrategy.stormTopologyName(defaultTopologyName));

        configurationProvider = env.getConfigurationProvider(topologyName,
                TOPOLOGY_PROPERTIES_DEFAULTS_PREFIX + defaultTopologyName);

        topologyConfig = configurationProvider.getConfiguration(topologyConfigClass);
        kafkaConfig = configurationProvider.getConfiguration(KafkaConfig.class);
        zookeeperConfig = configurationProvider.getConfiguration(ZookeeperConfig.class);
        logger.debug("Topology built {}: kafka={}, parallelism={}, workers={}",
                topologyName, kafkaConfig.getHosts(), topologyConfig.getParallelism(),
                topologyConfig.getWorkers());
        logger.info("Starting topology {} in {} mode", topologyName, topologyConfig.getBlueGreenMode());
    }

    protected String getDefaultTopologyName() {
        return getClass().getSimpleName().toLowerCase();
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

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use getKafkaProducerProperties with parameters (component name, run id)
     */
    @Deprecated
    private Properties getKafkaProducerProperties() {
        return getKafkaProducerProperties(COMMON_COMPONENT_NAME, COMMON_COMPONENT_RUN_ID);
    }

    private Properties getKafkaProducerProperties(String componentName, String runId) {
        Properties kafka = new Properties();

        kafka.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafka.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafka.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getHosts());
        kafka.setProperty(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningProducerInterceptor.class.getName());
        kafka.put(PRODUCER_COMPONENT_NAME_PROPERTY, componentName);
        kafka.put(PRODUCER_RUN_ID_PROPERTY, runId);
        kafka.put(PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, getZookeeperConfig().getHosts());

        return kafka;
    }

    protected Config makeStormConfig() {
        Config stormConfig = new Config();

        stormConfig.setNumWorkers(topologyConfig.getWorkers());
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
            stormConfig.setMaxTaskParallelism(topologyConfig.getParallelism());
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

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use buildKafkaSpout with parameters (component name, run id)
     */
    protected KafkaSpout<String, Message> buildKafkaSpout(String topic, String spoutId) {
        return buildKafkaSpout(Collections.singletonList(topic), spoutId);
    }

    /**
     * Creates Kafka spout. Transforms received value to {@link Message}.
     *
     * @param topic Kafka topic
     * @return {@link KafkaSpout}
     */
    protected KafkaSpout<String, Message> buildKafkaSpout(
            String topic, String spoutId, String componentName, String runId) {
        return buildKafkaSpout(Collections.singletonList(topic), spoutId, componentName, runId);
    }

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use buildKafkaSpout with parameters (component name, run id)
     */
    protected KafkaSpout<String, Message> buildKafkaSpout(List<String> topics, String spoutId) {
        return buildKafkaSpout(topics, spoutId, COMMON_COMPONENT_NAME, COMMON_COMPONENT_RUN_ID);
    }

    /**
     * Creates Kafka spout with list of topics. Transforms received value to {@link Message}.
     *
     * @param topics Kafka topic
     * @return {@link KafkaSpout}
     */
    protected KafkaSpout<String, Message> buildKafkaSpout(
            List<String> topics, String spoutId, String componentName, String runId) {
        KafkaSpoutConfig<String, Message> config = getKafkaSpoutConfigBuilder(
                topics, spoutId, componentName, runId).build();
        logger.info("Setup kafka spout: id={}, group={}, subscriptions={}",
                spoutId, config.getConsumerGroupId(), config.getSubscription().getTopicsString());
        return new KafkaSpout<>(config);
    }

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use buildKafkaSpoutForAbstractMessage with parameters (component name, run id)
     */
    @Deprecated
    protected KafkaSpout<String, AbstractMessage> buildKafkaSpoutForAbstractMessage(String topic, String spoutId) {
        return buildKafkaSpoutForAbstractMessage(Collections.singletonList(topic), spoutId,
                COMMON_COMPONENT_NAME, COMMON_COMPONENT_RUN_ID);
    }

    /**
     * Creates Kafka spout. Transforms received value to {@link AbstractMessage}.
     *
     * @param topic Kafka topic
     * @return {@link KafkaSpout}
     */
    protected KafkaSpout<String, AbstractMessage> buildKafkaSpoutForAbstractMessage(
            String topic, String spoutId, String componentName, String runId) {
        return buildKafkaSpoutForAbstractMessage(Collections.singletonList(topic), spoutId, componentName, runId);
    }

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use buildKafkaSpoutForAbstractMessage with parameters (component name, run id)
     */
    @Deprecated
    protected KafkaSpout<String, AbstractMessage> buildKafkaSpoutForAbstractMessage(
            List<String> topics, String spoutId) {
        return new KafkaSpout<>(
                makeKafkaSpoutConfig(topics, spoutId, AbstractMessageDeserializer.class,
                        COMMON_COMPONENT_NAME, COMMON_COMPONENT_RUN_ID)
                .setRecordTranslator(new AbstractMessageTranslator())
                .build());
    }

    /**
     * Creates Kafka spout with list of topics. Transforms received value to {@link AbstractMessage}.
     *
     * @param topics Kafka topics
     * @return {@link KafkaSpout}
     */
    protected KafkaSpout<String, AbstractMessage> buildKafkaSpoutForAbstractMessage(
            List<String> topics, String spoutId, String componentName, String runId) {
        return new KafkaSpout<>(
                makeKafkaSpoutConfig(topics, spoutId, AbstractMessageDeserializer.class,
                        componentName, runId)
                .setRecordTranslator(new AbstractMessageTranslator())
                .build());
    }

    /**
     * Creates Kafka bolt.
     *
     * @param topic Kafka topic
     * @return {@link KafkaBolt}
     * @deprecated replaced by {@link AbstractTopology#buildKafkaBolt(String)}
     */
    @Deprecated
    protected KafkaBolt<String, String> createKafkaBolt(final String topic) {
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
     * @deprecated replaced by {@link AbstractTopology#makeKafkaBolt}
     */
    @Deprecated
    protected KafkaBolt<String, Message> buildKafkaBolt(final String topic) {
        return makeKafkaBolt(topic, MessageSerializer.class);
    }

    /**
     * Creates Kafka bolt, that uses {@link MessageSerializer} in order to serialize an object.
     *
     * @param topic Kafka topic
     * @return {@link KafkaBolt}
     */
    protected KafkaBolt<String, Message> buildKafkaBolt(
            final String topic, String componentName, String runId) {
        return makeKafkaBolt(topic, MessageSerializer.class, componentName, runId);
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

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use getKafkaSpoutConfigBuilder with parameters (component name, run id)
     */
    @Deprecated
    protected KafkaSpoutConfig.Builder<String, Message> getKafkaSpoutConfigBuilder(String topic, String spoutId) {
        return getKafkaSpoutConfigBuilder(Collections.singletonList(topic), spoutId, COMMON_COMPONENT_NAME,
                COMMON_COMPONENT_RUN_ID);
    }

    private KafkaSpoutConfig.Builder<String, Message> getKafkaSpoutConfigBuilder(
            List<String> topics, String spoutId, String componentName, String runId) {
        return makeKafkaSpoutConfig(topics, spoutId, MessageDeserializer.class, componentName, runId)
                .setRecordTranslator(new MessageKafkaTranslator());
    }

    protected <V> KafkaSpoutConfig.Builder<String, V> makeKafkaSpoutConfig(
            List<String> topics, String spoutId, Class<? extends Deserializer<V>> valueDecoder, String componentName,
            String runId) {
        KafkaSpoutConfig.Builder<String, V> config = new KafkaSpoutConfig.Builder<>(
                kafkaConfig.getHosts(), new CustomNamedSubscription(topics));

        config.setProp(ConsumerConfig.GROUP_ID_CONFIG, makeKafkaGroupName(spoutId))
                .setFirstPollOffsetStrategy(KafkaSpoutConfig.FirstPollOffsetStrategy.LATEST)
                .setProp(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true)
                .setProp(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .setProp(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDecoder)
                .setProp(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningConsumerInterceptor.class.getName())
                .setProp(CONSUMER_COMPONENT_NAME_PROPERTY, componentName)
                .setProp(CONSUMER_RUN_ID_PROPERTY, runId)
                .setProp(CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, getZookeeperConfig().getHosts())
                .setTupleTrackingEnforced(true);

        return config;
    }

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use makeKafkaBolt with parameters (component name, run id)
     */
    @Deprecated
    protected <V> KafkaBolt<String, V> makeKafkaBolt(String topic, Class<? extends Serializer<V>> valueEncoder) {
        return makeKafkaBolt(valueEncoder)
                .withTopicSelector(topic);
    }

    protected <V> KafkaBolt<String, V> makeKafkaBolt(
            String topic, Class<? extends Serializer<V>> valueEncoder, String componentName, String runId) {
        return makeKafkaBolt(valueEncoder, componentName, runId)
                .withTopicSelector(topic);
    }

    /**
     * //TODO(zero_down_time) Remove when zero down time feature will be completed.
     *
     * @deprecated use makeKafkaBolt with parameters (component name, run id)
     */
    @Deprecated
    protected <V> KafkaBolt<String, V> makeKafkaBolt(Class<? extends Serializer<V>> valueEncoder) {
        return makeKafkaBolt(valueEncoder, COMMON_COMPONENT_NAME, COMMON_COMPONENT_RUN_ID);
    }

    protected <V> KafkaBolt<String, V> makeKafkaBolt(
            Class<? extends Serializer<V>> valueEncoder, String componentName, String runId) {
        Properties properties = getKafkaProducerProperties(componentName, runId);
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueEncoder.getName());

        return new KafkaBolt<String, V>()
                .withProducerProperties(properties)
                .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper<>());
    }

    private String makeKafkaGroupName(String spoutId) {
        return kafkaNamingStrategy.kafkaConsumerGroupName(format("%s__%s", topologyName, spoutId));
    }

    protected ZookeeperConfig getZookeeperConfig() {
        return zookeeperConfig;
    }

    protected String getZkTopoName() {
        return getClass().getSimpleName();
    }
}
