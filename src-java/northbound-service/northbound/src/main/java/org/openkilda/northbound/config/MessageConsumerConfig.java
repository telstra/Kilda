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

package org.openkilda.northbound.config;

import static org.openkilda.messaging.Utils.COMMON_COMPONENT_NAME;
import static org.openkilda.messaging.Utils.COMMON_COMPONENT_RUN_ID;
import static org.openkilda.messaging.Utils.CONSUMER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.kafka.versioning.VersioningConsumerInterceptor;
import org.openkilda.northbound.messaging.MessagingChannel;
import org.openkilda.northbound.messaging.kafka.KafkaMessageListener;
import org.openkilda.northbound.messaging.kafka.KafkaMessagingChannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * The Kafka message consumer configuration.
 */
@Configuration
@EnableKafka
@PropertySource("classpath:northbound.properties")
public class MessageConsumerConfig {
    /**
     * Kafka queue poll timeout.
     */
    private static final int POLL_TIMEOUT = 3000;

    /**
     * Kafka bootstrap servers.
     */
    @Value("${kafka.hosts}")
    private String kafkaHosts;

    /**
     * ZooKeeper hosts.
     */
    @Value("${zookeeper.hosts}")
    private String zookeeperHosts;

    /**
     * Kafka group id.
     */
    @Value("#{kafkaGroupConfig.getGroupId()}")
    private String groupId;

    /**
     * Kafka group id.
     */
    @Value("${northbound.kafka.listener.threads}")
    private int kafkaListeners;

    /**
     * Kafka group id.
     */
    @Value("${northbound.kafka.session.timeout}")
    private int kafkaSessionTimeout;

    /**
     * Kafka consumer configuration bean. This {@link Map} is used by {@link MessageConsumerConfig#consumerFactory}.
     *
     * @return kafka properties
     */
    private Map<String, Object> consumerConfigs() {
        return ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHosts)
                .put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true)
                .put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, kafkaSessionTimeout)
                .put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningConsumerInterceptor.class.getName())
                .put(CONSUMER_COMPONENT_NAME_PROPERTY, COMMON_COMPONENT_NAME)
                .put(CONSUMER_RUN_ID_PROPERTY, COMMON_COMPONENT_RUN_ID)
                .put(CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, zookeeperHosts)
                .build();
    }

    /**
     * Kafka consumer factory bean. The strategy to produce a {@link org.apache.kafka.clients.consumer.Consumer}
     * instance with {@link MessageConsumerConfig#consumerConfigs} on each
     * {@link org.springframework.kafka.core.DefaultKafkaConsumerFactory#createConsumer}
     * invocation.
     *
     * @return kafka consumer factory
     */
    @Bean
    public ConsumerFactory<String, Message> consumerFactory(ObjectMapper objectMapper) {
        return new DefaultKafkaConsumerFactory<>(consumerConfigs(),
                new StringDeserializer(), new JsonDeserializer<>(Message.class, objectMapper));
    }

    /**
     * Kafka listener container factory bean. Returned instance builds
     * {@link org.springframework.kafka.listener.ConcurrentMessageListenerContainer}
     * using the {@link org.apache.kafka.clients.consumer.Consumer}.
     *
     * @return kafka listener container factory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Message> kafkaListenerContainerFactory(
            ConsumerFactory<String, Message> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Message> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setPollTimeout(POLL_TIMEOUT);
        factory.setConcurrency(kafkaListeners);
        return factory;
    }

    @Bean
    public KafkaMessageListener kafkaMessageListener() {
        return new KafkaMessageListener();
    }

    @Bean
    public MessagingChannel messagingChannel() {
        return new KafkaMessagingChannel();
    }

}
