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
import static org.openkilda.messaging.Utils.PRODUCER_CONFIG_VERSION_PROPERTY;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.Map;

@Slf4j
public class VersioningProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {
    private String version;

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (record.headers().headers(MESSAGE_VERSION_HEADER).iterator().hasNext()) {
            log.warn("Kafka record {} already has header {}: {}",
                    record, MESSAGE_VERSION_HEADER,
                    new String(record.headers().headers(MESSAGE_VERSION_HEADER).iterator().next().value()));
            // TODO remove?
            record.headers().remove(MESSAGE_VERSION_HEADER);
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
        if (configs.containsKey(PRODUCER_CONFIG_VERSION_PROPERTY)) {
            version = (String) configs.get(PRODUCER_CONFIG_VERSION_PROPERTY);
        } else {
            throw new IllegalArgumentException(String.format("Missed config option %s in configs %s",
                    PRODUCER_CONFIG_VERSION_PROPERTY, configs));
        }
    }
}
