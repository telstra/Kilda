/* Copyright 2020 Telstra Open Source
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

package org.openkilda.messaging.kafka.versioning;

import static org.openkilda.messaging.Utils.CONSUMER_CONFIG_VERSION_PROPERTY;
import static org.openkilda.messaging.Utils.MESSAGE_VERSION_HEADER;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class VersioningConsumerInterceptor<K, V> implements ConsumerInterceptor<K, V> {
    private String version;

    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        Map<TopicPartition, List<ConsumerRecord<K, V>>> filteredRecordMap = new HashMap<>();

        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<K, V>> filteredRecords = new ArrayList<>();

            for (ConsumerRecord<K, V> record : records.records(partition)) {
                if (checkRecordVersion(record)) {
                    filteredRecords.add(record);
                }
            }

            filteredRecordMap.put(partition, filteredRecords);
        }
        return new ConsumerRecords<>(filteredRecordMap);
    }

    private boolean checkRecordVersion(ConsumerRecord<K, V> record) {
        List<Header> headers = Lists.newArrayList(record.headers().headers(MESSAGE_VERSION_HEADER));

        if (headers.isEmpty()) {
            log.error(String.format("Missed %s header for record %s", MESSAGE_VERSION_HEADER, record));
            return false;
        }

        if (headers.size() > 1) {
            log.error(String.format("Fount more than one %s headers for record %s", MESSAGE_VERSION_HEADER, record));
            // TODO maybe need to be replaced with some soft handling. Maybe check all versions in list
            // Currently such hard constraints are needed to test versioning massaging
            return false;
        }

        if (!version.equals(new String(headers.get(0).value()))) {
            if (log.isDebugEnabled()) {
                log.debug("Skip record {} with version {}. Target version is {}",
                        record, new String(headers.get(0).value()), version);
            }
            return false;
        }
        return true;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        if (configs.containsKey(CONSUMER_CONFIG_VERSION_PROPERTY)) {
            version = (String) configs.get(CONSUMER_CONFIG_VERSION_PROPERTY);
        } else {
            throw new IllegalArgumentException(String.format("Missed config option %s in configs %s",
                    CONSUMER_CONFIG_VERSION_PROPERTY, configs));
        }
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        // nothing to do here
    }

    @Override
    public void close() {
        // nothing to do here
    }
}
