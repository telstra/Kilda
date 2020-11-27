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

package org.openkilda.wfm;

import static org.openkilda.messaging.Utils.COMMON_COMPONENT_NAME;
import static org.openkilda.messaging.Utils.COMMON_COMPONENT_RUN_ID;
import static org.openkilda.messaging.Utils.CONSUMER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;

import org.openkilda.config.KafkaConfig;
import org.openkilda.messaging.kafka.versioning.VersioningConsumerInterceptor;
import org.openkilda.messaging.kafka.versioning.VersioningProducerInterceptor;
import org.openkilda.wfm.config.ZookeeperConfig;
import org.openkilda.wfm.config.provider.MultiPrefixConfigurationProvider;
import org.openkilda.wfm.error.ConfigurationException;
import org.openkilda.wfm.topology.TestKafkaProducer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

@Slf4j
public abstract class AbstractStormTest {
    public static final int TOPOLOGY_START_TIMEOUT = 20000;
    public static final String STRING_SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer";
    public static final String STRING_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer";

    protected static String CONFIG_NAME = "class-level-overlay.properties";

    protected static TestKafkaProducer kProducer;
    protected static LocalCluster cluster;
    protected static TestUtils.KafkaTestFixture server;

    @ClassRule
    public static TemporaryFolder fsData = new TemporaryFolder();

    private static Properties commonKafkaProperties() throws ConfigurationException, CmdLineException {
        Properties properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, makeUnboundConfig(KafkaConfig.class).getHosts());
        properties.put("request.required.acks", "1");
        return properties;
    }

    protected static Properties kafkaProducerProperties() throws CmdLineException, ConfigurationException {
        Properties properties = commonKafkaProperties();
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, STRING_SERIALIZER);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, STRING_SERIALIZER);
        properties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningProducerInterceptor.class.getName());
        properties.put(PRODUCER_COMPONENT_NAME_PROPERTY, COMMON_COMPONENT_NAME);
        properties.put(PRODUCER_RUN_ID_PROPERTY, COMMON_COMPONENT_RUN_ID);
        properties.put(PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY,
                makeUnboundConfig(ZookeeperConfig.class).getHosts());
        return properties;
    }

    protected static Properties kafkaConsumerProperties(final String groupId)
            throws ConfigurationException, CmdLineException {
        Properties properties = commonKafkaProperties();
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, STRING_DESERIALIZER);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, STRING_DESERIALIZER);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningConsumerInterceptor.class.getName());
        properties.put(CONSUMER_COMPONENT_NAME_PROPERTY, COMMON_COMPONENT_NAME);
        properties.put(CONSUMER_RUN_ID_PROPERTY, COMMON_COMPONENT_RUN_ID);
        properties.put(CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY,
                makeUnboundConfig(ZookeeperConfig.class).getHosts());
        return properties;
    }

    protected static Config stormConfig() {
        Config config = new Config();
        config.setDebug(false);
        config.setMaxTaskParallelism(1);
        config.setNumWorkers(1);
        return config;
    }

    protected static void startZooKafkaAndStorm() throws Exception {
        log.info("Starting Zookeeper and Kafka...");

        makeConfigFile();

        server = new TestUtils.KafkaTestFixture(makeUnboundConfig(ZookeeperConfig.class));
        server.start();

        log.info("Zookeeper and Kafka started.");
        log.info("Starting local Storm cluster...");

        cluster = new LocalCluster();
        kProducer = new TestKafkaProducer(kafkaProducerProperties());

        log.info("Storm started.");
    }

    protected static void stopZooKafkaAndStorm() throws Exception {
        kProducer.close();

        log.info("Stopping local Storm cluster...");
        cluster.shutdown();
        log.info("Storm stopped.");
        log.info("Stopping Zookeeper and Kafka...");
        server.stop();
        log.info("Zookeeper and Kafka stopped.");
    }

    protected static <T> T makeUnboundConfig(Class<T> configurationType)
            throws ConfigurationException, CmdLineException {
        LaunchEnvironment env = makeLaunchEnvironment();
        MultiPrefixConfigurationProvider configurationProvider = env.getConfigurationProvider();
        return configurationProvider.getConfiguration(configurationType);
    }

    protected static LaunchEnvironment makeLaunchEnvironment() throws CmdLineException, ConfigurationException {
        String[] args = makeLaunchArgs();
        return new LaunchEnvironment(args);
    }

    protected static LaunchEnvironment makeLaunchEnvironment(Properties overlay)
            throws CmdLineException, ConfigurationException, IOException {
        String extra = fsData.newFile().getName();
        makeConfigFile(overlay, extra);

        String[] args = makeLaunchArgs(extra);
        return new LaunchEnvironment(args);
    }

    protected static String[] makeLaunchArgs(String... extraConfig) {
        String[] args = new String[extraConfig.length + 1];

        File root = fsData.getRoot();
        args[0] = new File(root, CONFIG_NAME).toString();
        for (int idx = 0; idx < extraConfig.length; idx += 1) {
            args[idx + 1] = new File(root, extraConfig[idx]).toString();
        }

        return args;
    }

    protected static void makeConfigFile() throws IOException {
        makeConfigFile(makeConfigOverlay(), CONFIG_NAME);
    }

    protected static void makeConfigFile(Properties overlay, String location) throws IOException {
        File path = new File(fsData.getRoot(), location);
        overlay.store(new FileWriter(path), null);
    }

    protected static Properties makeConfigOverlay() {
        return new Properties();
    }
}
