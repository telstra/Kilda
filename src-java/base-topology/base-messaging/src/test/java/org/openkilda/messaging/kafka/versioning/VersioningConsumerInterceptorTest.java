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

import com.google.common.collect.Lists;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VersioningConsumerInterceptorTest {

    public static final int PARTITION_1 = 1;
    public static final int PARTITION_2 = 2;
    public static final String TOPIC = "topic";
    public static final String KEY_1 = "key1";
    public static final String KEY_2 = "key2";
    public static final String VALUE_1 = "value1";
    public static final String VALUE_2 = "value2";
    public static final String VALUE_3 = "value3";
    public static final String VALUE_4 = "value4";
    public static final String VERSION_1 = "version1";
    public static final String VERSION_2 = "version2";

    @Test
    public void unsetVersionTest() {
        VersioningConsumerInterceptor<String, String> interceptor = new VersioningConsumerInterceptor<>();
        ConsumerRecords<String, String> records = getConsumerRecords(VERSION_1, VERSION_1, null, VERSION_2);
        ConsumerRecords<String, String> result = interceptor.onConsume(records);

        // interceptor with unset version skips all records
        Assert.assertEquals(0, result.count());
    }

    @Test
    public void differentVersionTest() {
        VersioningConsumerInterceptor<String, String> interceptor = new VersioningConsumerInterceptor<>();
        interceptor.handle(VERSION_1);
        ConsumerRecords<String, String> records = getConsumerRecords(null, VERSION_1, VERSION_1, VERSION_2);
        ConsumerRecords<String, String> result = interceptor.onConsume(records);

        // interceptor will remove record with unset version and record with wrong version
        Assert.assertEquals(2, result.count());
        Set<String> values = getRecordValues(result);
        Assert.assertTrue(values.contains(VALUE_2));
        Assert.assertTrue(values.contains(VALUE_3));
    }

    @Test
    public void severalHeadersTest() {
        VersioningConsumerInterceptor<String, String> interceptor = new VersioningConsumerInterceptor<>();
        interceptor.handle(VERSION_1);

        // record with 2 same headers
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, PARTITION_1, 0L, KEY_1, VALUE_1);
        addVersion(record, VERSION_1);
        addVersion(record, VERSION_1);

        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
        recordMap.put(new TopicPartition(TOPIC, PARTITION_1), Lists.newArrayList(record));
        ConsumerRecords<String, String> records = new ConsumerRecords<>(recordMap);

        ConsumerRecords<String, String> result = interceptor.onConsume(records);

        // interceptor will skip record because it has 2 headers, but must has one
        Assert.assertEquals(0, result.count());
    }

    private ConsumerRecords<String, String> getConsumerRecords(String record1Version, String record2Version,
                                                               String record3Version, String record4Version) {
        TopicPartition topicPartition1 = new TopicPartition(TOPIC, PARTITION_1);
        TopicPartition topicPartition2 = new TopicPartition(TOPIC, PARTITION_2);

        ConsumerRecord<String, String> record1 = new ConsumerRecord<>(TOPIC, PARTITION_1, 0L, KEY_1, VALUE_1);
        ConsumerRecord<String, String> record2 = new ConsumerRecord<>(TOPIC, PARTITION_1, 1L, KEY_1, VALUE_2);
        ConsumerRecord<String, String> record3 = new ConsumerRecord<>(TOPIC, PARTITION_2, 2L, KEY_2, VALUE_3);
        ConsumerRecord<String, String> record4 = new ConsumerRecord<>(TOPIC, PARTITION_2, 3L, KEY_2, VALUE_4);

        addVersion(record1, record1Version);
        addVersion(record2, record2Version);
        addVersion(record3, record3Version);
        addVersion(record4, record4Version);

        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
        recordMap.put(topicPartition1, Lists.newArrayList(record1, record2));
        recordMap.put(topicPartition2, Lists.newArrayList(record3, record4));

        return new ConsumerRecords<>(recordMap);
    }

    private void addVersion(ConsumerRecord<String, String> record, String versionHeader) {
        if (versionHeader != null) {
            record.headers().add(MESSAGE_VERSION_HEADER, versionHeader.getBytes());
        }
    }

    private Set<String> getRecordValues(ConsumerRecords<String, String> records) {
        Set<String> values = new HashSet<>();
        for (ConsumerRecord<String, String> record : records) {
            values.add(record.value());
        }
        return values;
    }
}
