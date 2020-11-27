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

import static org.openkilda.messaging.Utils.CONSUMER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;
import static org.openkilda.messaging.Utils.MESSAGE_VERSION_HEADER;
import static org.openkilda.messaging.Utils.getValue;

import org.openkilda.bluegreen.BuildVersionObserver;
import org.openkilda.bluegreen.ZkWatchDog;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class VersioningConsumerInterceptor<K, V> extends VersioningInterceptorBase
        implements ConsumerInterceptor<K, V>, BuildVersionObserver {

    public VersioningConsumerInterceptor() {
        log.info("Initializing VersioningConsumerInterceptor");
    }

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

        if (version == null) {
            if (isVersionIsNotTimeoutPassed()) {
                // We will write this log every 60 seconds to do not spam in logs
                log.warn("Messaging version is not set for component {} with id {}. Skip record {}",
                        componentName, runId, record);
                versionIsNotSetTimestamp = Instant.now();
            }
            return false;
        }

        if (headers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Missed %s header for record %s", MESSAGE_VERSION_HEADER, record));
            }
            return false;
        }

        if (headers.size() > 1) {
            log.warn(String.format("Fount more than one %s headers for record %s", MESSAGE_VERSION_HEADER, record));
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
        String connectionString = getValue(configs, CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, String.class);
        runId = getValue(configs, CONSUMER_RUN_ID_PROPERTY, String.class);
        componentName = getValue(configs, CONSUMER_COMPONENT_NAME_PROPERTY, String.class);
        log.info("Configuring VersioningConsumerInterceptor for component {} with id {} and connection string {}",
                componentName, runId, connectionString);

        try {
            ZkWatchDog watchDog = ZkWatchDog.builder()
                    .id(runId)
                    .serviceName(componentName)
                    .connectionString(connectionString).build();

            watchDog.subscribe(this);
        } catch (IOException e) {
            log.error("Component {} with id {} can't connect to ZooKeeper with connection string: {}, received: {}",
                    componentName, runId, connectionString, e.getMessage());
            e.printStackTrace();
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

    @Override
    public void handle(String buildVersion) {
        log.info("Updating consumer kafka messaging version from {} to {} for component {} with id {}",
                version, buildVersion, componentName, runId);
        version = buildVersion;
    }
}
