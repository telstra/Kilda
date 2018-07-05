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

import org.openkilda.messaging.Message;
import org.openkilda.northbound.messaging.HealthCheckMessageConsumer;
import org.openkilda.northbound.messaging.MessageConsumer;
import org.openkilda.northbound.messaging.MessagingChannel;
import org.openkilda.northbound.messaging.kafka.KafkaHealthCheckMessageConsumer;
import org.openkilda.northbound.messaging.kafka.KafkaMessageConsumer;
import org.openkilda.northbound.messaging.kafka.KafkaMessageListener;
import org.openkilda.northbound.messaging.kafka.KafkaMessagingChannel;

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

import java.util.HashMap;
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
     * Kafka group id.
     */
    @Value("#{kafkaGroupConfig.getGroupId()}")
    private String groupId;

    /**
     * Kafka consumer configuration bean. This {@link Map} is used by {@link MessageConsumerConfig#consumerFactory}.
     *
     * @return kafka properties
     */
    @Bean
    public Map<String, Object> consumerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHosts);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        //props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        //props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "100");
        //props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
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
    public ConsumerFactory<String, Message> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfigs(),
                new StringDeserializer(), new JsonDeserializer<>(Message.class));
    }

    /**
     * Kafka listener container factory bean. Returned instance builds
     * {@link org.springframework.kafka.listener.ConcurrentMessageListenerContainer}
     * using the {@link org.apache.kafka.clients.consumer.Consumer}.
     *
     * @return kafka listener container factory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Message> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Message> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setPollTimeout(POLL_TIMEOUT);
        factory.setConcurrency(10);
        return factory;
    }

    /**
     * Kafka message consumer bean. Instance of {@link org.openkilda.northbound.messaging.kafka.KafkaMessageConsumer}
     * contains {@link org.springframework.kafka.annotation.KafkaListener} to be run in {@link
     * org.springframework.kafka.listener.ConcurrentMessageListenerContainer}.
     *
     * @return kafka message consumer
     */
    @Bean
    public MessageConsumer messageConsumer() {
        return new KafkaMessageConsumer();
    }

    /**
     * Kafka message consumer bean. Instance of {@link KafkaHealthCheckMessageConsumer} contains {@link
     * org.springframework.kafka.annotation.KafkaListener} to be run in
     * {@link org.springframework.kafka.listener.ConcurrentMessageListenerContainer}.
     *
     * @return kafka health-check message consumer
     */
    @Bean
    public HealthCheckMessageConsumer healthCheckMessageConsumer() {
        return new KafkaHealthCheckMessageConsumer();
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
