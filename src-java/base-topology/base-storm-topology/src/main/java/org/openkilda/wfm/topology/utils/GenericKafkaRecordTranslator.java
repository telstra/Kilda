/* Copyright 2019 Telstra Open Source
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

import static org.openkilda.wfm.AbstractBolt.FIELD_ID_CONTEXT;

import org.openkilda.wfm.CommandContext;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

abstract class GenericKafkaRecordTranslator<D> extends KafkaRecordTranslator<String, D, D> {
    // use FIELD_ID_KEY instead
    @Deprecated
    public static final String KEY_FIELD = FIELD_ID_KEY;
    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_KEY, FIELD_ID_PAYLOAD, FIELD_ID_CONTEXT);

    public GenericKafkaRecordTranslator(String version) {
        super(version);
    }

    @Override
    public Fields getFieldsFor(String stream) {
        return STREAM_FIELDS;
    }

    @Override
    protected D decodePayload(D payload) {
        return payload;
    }

    @Override
    protected Values makeTuple(ConsumerRecord<String, D> record, D payload, CommandContext context) {
        return new Values(record.key(), payload, context);
    }
}
