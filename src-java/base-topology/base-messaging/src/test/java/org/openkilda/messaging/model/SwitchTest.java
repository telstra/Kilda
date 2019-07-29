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

package org.openkilda.messaging.model;

import org.openkilda.messaging.StringSerializer;
import org.openkilda.messaging.Utils;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;

public class SwitchTest {
    StringSerializer serializer = new StringSerializer() {
        @Override
        public Object deserialize() throws IOException {
            return Utils.MAPPER.readValue(strings.poll(), Switch.class);
        }

        @Override
        public void serialize(Object subject) throws IOException {
            strings.add(Utils.MAPPER.writeValueAsString(subject));
        }
    };

    @Test
    public void serializeLoop() throws Exception {
        Switch origin = Switch.builder()
                .switchId(new SwitchId(1))
                .controller("test_controller")
                .description("test_description")
                .hostname("test_hostname")
                .pop("test_pop")
                .status(SwitchStatus.ACTIVE)
                .underMaintenance(true)
                .socketAddress(new InetSocketAddress(1))
                .features(Collections.singleton(SwitchFeature.GROUP_PACKET_OUT_CONTROLLER))
                .build();
        serializer.serialize(origin);

        Switch reconstructed = (Switch) serializer.deserialize();
        Assert.assertEquals(origin, reconstructed);
    }
}
