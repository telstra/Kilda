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

package org.openkilda.wfm.topology;

import static org.openkilda.messaging.Utils.MESSAGE_VERSION_CONSUMER_PROPERTY;
import static org.openkilda.messaging.Utils.MESSAGE_VERSION_HEADER;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SkipKafkaConsumer<K, V>  extends KafkaConsumer<K, V> {
    private final String version;

    public SkipKafkaConsumer(Properties properties) {
        super(properties);
        if (properties.getProperty(MESSAGE_VERSION_CONSUMER_PROPERTY) == null) {
            throw new IllegalArgumentException(String.format("Missed property %s", MESSAGE_VERSION_CONSUMER_PROPERTY));
        } else {
            version = properties.getProperty(MESSAGE_VERSION_CONSUMER_PROPERTY);
        }
    }

    @Override
    public ConsumerRecords<K, V> poll(long timeoutMs) {
        ConsumerRecords<K, V> records = super.poll(timeoutMs);
        return filterConsumerRecords(records);
    }

    @Override
    public ConsumerRecords<K, V> poll(Duration timeout) {
        ConsumerRecords<K, V> records = super.poll(timeout);
        return filterConsumerRecords(records);
    }

    private ConsumerRecords<K, V> filterConsumerRecords(ConsumerRecords<K, V> records) {
        Map<TopicPartition, List<ConsumerRecord<K, V>>> filteredRecords = new HashMap<>();


        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<K, V>> filteredRecordList = new ArrayList<>();

            for (ConsumerRecord<K, V> record : records.records(partition)) {
                if (checkVersion(record.headers(), version)) {
                    filteredRecordList.add(record);
                }
            }
            filteredRecords.put(partition, filteredRecordList);
        }
        return new ConsumerRecords<>(filteredRecords);
    }

    private boolean checkVersion(Headers headers, String version) {
        List<Header> list = new ArrayList<>();
        for (Header header : headers.headers(MESSAGE_VERSION_HEADER)) {
            list.add(header);
        }

        if (list.size() > 1) {
            throw new RuntimeException("to much");
        }

        for (Header header : headers.headers(MESSAGE_VERSION_HEADER)) {
            String v = new String(header.value());
            return version.equals(v);
        }
        return false;
    }
}
