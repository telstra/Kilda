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
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class VersioningProducerInterceptorTest {

    public static final String TOPIC = "topic";
    public static final String VALUE = "value";
    public static final String VERSION_1 = "version1";
    public static final String VERSION_2 = "version2";

    @Test
    public void nullVersionTest() {
        VersioningProducerInterceptor<String, String> interceptor = new VersioningProducerInterceptor<>();
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, VALUE);
        record.headers().add(MESSAGE_VERSION_HEADER, VERSION_1.getBytes());
        ProducerRecord<String, String> result = interceptor.onSend(record);

        // version inside interceptor is not set. Interceptor must not add version header and remove old version headers
        Assert.assertFalse(result.headers().headers(MESSAGE_VERSION_HEADER).iterator().hasNext());
    }

    @Test
    public void validVersionTest() {
        VersioningProducerInterceptor<String, String> interceptor = new VersioningProducerInterceptor<>();
        interceptor.handle(VERSION_1);

        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, VALUE);
        ProducerRecord<String, String> result = interceptor.onSend(record);

        List<Header> versionHeaders = Lists.newArrayList(result.headers().headers(MESSAGE_VERSION_HEADER));
        Assert.assertEquals(1, versionHeaders.size());
        Assert.assertEquals(VERSION_1, new String(versionHeaders.get(0).value()));
    }

    @Test
    public void overrideVersionTest() {
        VersioningProducerInterceptor<String, String> interceptor = new VersioningProducerInterceptor<>();
        interceptor.handle(VERSION_2);

        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, VALUE);
        record.headers().add(MESSAGE_VERSION_HEADER, VERSION_1.getBytes());

        ProducerRecord<String, String> result = interceptor.onSend(record);

        // record had version1 header, but it was replaced with version2
        List<Header> versionHeaders = Lists.newArrayList(result.headers().headers(MESSAGE_VERSION_HEADER));
        Assert.assertEquals(1, versionHeaders.size());
        Assert.assertEquals(VERSION_2, new String(versionHeaders.get(0).value()));
    }
}
