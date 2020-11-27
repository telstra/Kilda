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

import static org.openkilda.messaging.Utils.MESSAGE_VERSION_HEADER;
import static org.openkilda.messaging.Utils.PRODUCER_COMPONENT_NAME_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_RUN_ID_PROPERTY;
import static org.openkilda.messaging.Utils.PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY;
import static org.openkilda.messaging.Utils.getValue;

import org.openkilda.bluegreen.BuildVersionObserver;
import org.openkilda.bluegreen.ZkWatchDog;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Slf4j
public class VersioningProducerInterceptor<K, V> extends VersioningInterceptorBase
        implements ProducerInterceptor<K, V>, BuildVersionObserver {

    public VersioningProducerInterceptor() {
        log.info("Initializing VersioningProducerInterceptor");
    }

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (record.headers().headers(MESSAGE_VERSION_HEADER).iterator().hasNext()) {
            log.info("Kafka record {} already has header {}: {}",
                    record, MESSAGE_VERSION_HEADER,
                    new String(record.headers().headers(MESSAGE_VERSION_HEADER).iterator().next().value()));
            // TODO remove?
            record.headers().remove(MESSAGE_VERSION_HEADER);
        }

        if (version == null) {
            if (isVersionIsNotTimeoutPassed()) {
                // We will write this log every 60 seconds to do not spam in logs
                log.warn("Messaging version is not set for component {} with id {}. Skip record {}",
                        componentName, runId, record);
                versionIsNotSetTimestamp = Instant.now();
            }
            // We can't return null record. We will return record without version.
            // Such records wouldn't be read by Kafka Consumer with VersioningConsumerInterceptor
            return record;
        }

        record.headers().add(MESSAGE_VERSION_HEADER, version.getBytes());
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // nothing to do
    }

    public void close() {
        // nothing to do
    }

    @Override
    public void configure(Map<String, ?> configs) {
        String connectionString = getValue(configs, PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY, String.class);
        runId = getValue(configs, PRODUCER_RUN_ID_PROPERTY, String.class);
        componentName = getValue(configs, PRODUCER_COMPONENT_NAME_PROPERTY, String.class);
        log.info("Configuring VersioningProducerInterceptor for component {} with id {} and connection string {}",
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
    public void handle(String buildVersion) {
        log.info("Updating producer kafka messaging version from {} to {} for component {} with id {}",
                version, buildVersion, componentName, runId);
        version = buildVersion;
    }
}
