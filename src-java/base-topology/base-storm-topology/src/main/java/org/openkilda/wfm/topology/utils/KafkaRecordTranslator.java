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

package org.openkilda.wfm.topology.utils;

import static org.openkilda.messaging.Utils.MESSAGE_VERSION_HEADER;

import org.openkilda.wfm.CommandContext;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.storm.kafka.spout.RecordTranslator;
import org.apache.storm.tuple.Values;

import java.util.List;

@Slf4j
public abstract class KafkaRecordTranslator<K, V, D> implements RecordTranslator<K, V> {
    private static final long serialVersionUID = 1L;

    public static final String FIELD_ID_KEY = "key";
    public static final String FIELD_ID_PAYLOAD = "message";
    private final String version;
    // FIXME(surabujin): keep payload at index 0 because some code grab it in following way: `tuple.getString(0)`
    // public static final Fields FIELDS = new Fields(FIELD_ID_PAYLOAD, FIELD_ID_KEY);


    public KafkaRecordTranslator(String version) {
        this.version = version;
    }

    @Override
    public List<Object> apply(ConsumerRecord<K, V> record) {
        List<Header> headers = Lists.newArrayList(record.headers().headers(MESSAGE_VERSION_HEADER));

        if (headers.isEmpty()) {
            log.error(String.format("Missed %s header for record %s", MESSAGE_VERSION_HEADER, record));
            // TODO replace 'return null' with some soft handling (maybe return record without header).
            // Currently such hard constraints are needed to test versioning massaging
            return null;
        }

        if (headers.size() > 1) {
            log.error(String.format("Fount more than one %s headers for record %s", MESSAGE_VERSION_HEADER, record));
            // TODO replace 'return null' with some soft handling.
            // Currently such hard constraints are needed to test versioning massaging
            return null;
        }

        if (!version.equals(new String(headers.get(0).value()))) {
            if (log.isDebugEnabled()) {
                log.debug("Skip record {} with version {}. Target version is {}",
                        record, new String(headers.get(0).value()), version);
            }
            return null;
        }
        D payload = decodePayload(record.value());
        CommandContext context = makeContext(record, payload);
        return makeTuple(record, payload, context);
    }

    @Override
    public List<String> streams() {
        return DEFAULT_STREAM;
    }

    protected abstract D decodePayload(V payload);

    protected abstract CommandContext makeContext(ConsumerRecord<?, ?> record, D payload);

    protected abstract Values makeTuple(ConsumerRecord<K, V> record, D payload, CommandContext context);
}
