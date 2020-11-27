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

package org.openkilda.floodlight;

import static org.openkilda.messaging.Utils.COMMON_COMPONENT_NAME;
import static org.openkilda.messaging.Utils.COMMON_COMPONENT_RUN_ID;
import static org.openkilda.messaging.Utils.CONSUMER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;

import org.openkilda.config.KafkaConsumerGroupConfig;
import org.openkilda.config.mapping.Mapping;
import org.openkilda.messaging.kafka.versioning.VersioningConsumerInterceptor;
import org.openkilda.messaging.kafka.versioning.VersioningProducerInterceptor;

import com.sabre.oss.conf4j.annotation.Default;
import com.sabre.oss.conf4j.annotation.Key;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.Properties;
import javax.validation.constraints.Min;

public interface KafkaChannelConfig extends KafkaConsumerGroupConfig {
    @Key("kafka-groupid")
    @Default("floodlight")
    @Mapping(target = KAFKA_CONSUMER_GROUP_MAPPING)
    String getGroupId();

    @Key("bootstrap-servers")
    String getBootstrapServers();

    @Key("zookeeper-hosts")
    String getZooKeeperHosts();

    @Key("heart-beat-interval")
    @Default("1")
    @Min(1)
    float getHeartBeatInterval();

    @Key("floodlight-region")
    String getFloodlightRegion();

    /**
     * Returns Kafka properties built with the configuration data for Consumer.
     */
    default Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", getBootstrapServers());

        properties.put("group.id", getGroupId());
        properties.put("session.timeout.ms", "30000");
        properties.put("enable.auto.commit", "false");

        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

        properties.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, VersioningConsumerInterceptor.class.getName());
        properties.put(CONSUMER_COMPONENT_NAME_PROPERTY, COMMON_COMPONENT_NAME);
        properties.put(CONSUMER_RUN_ID_PROPERTY, COMMON_COMPONENT_RUN_ID);
        properties.put(CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, getZooKeeperHosts());

        return properties;
    }

    /**
     * Returns Kafka properties built with the configuration data for Producer.
     */
    default Properties producerProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", getBootstrapServers());

        properties.put("acks", "all");
        properties.put("retries", 0);
        properties.put("batch.size", 4);
        properties.put("buffer.memory", 33554432);
        properties.put("linger.ms", 10);

        properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        properties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                VersioningProducerInterceptor.class.getName());
        properties.put(PRODUCER_COMPONENT_NAME_PROPERTY, COMMON_COMPONENT_NAME);
        properties.put(PRODUCER_RUN_ID_PROPERTY, COMMON_COMPONENT_RUN_ID);
        properties.put(PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, getZooKeeperHosts());

        return properties;
    }
}
