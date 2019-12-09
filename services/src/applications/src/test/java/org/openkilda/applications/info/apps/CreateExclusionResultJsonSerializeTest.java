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

package org.openkilda.applications.info.apps;

import static org.junit.Assert.assertEquals;

import org.openkilda.applications.model.Exclusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class CreateExclusionResultJsonSerializeTest {

    @Test
    public void shouldSerializeToJson() throws Exception {
        CreateExclusionResult createExclusionResult = CreateExclusionResult.builder()
                .flowId("flow_id")
                .application("app")
                .exclusion(Exclusion.builder()
                        .srcIp("127.0.0.2")
                        .srcPort(1)
                        .dstIp("127.0.0.3")
                        .dstPort(3)
                        .proto("UDP")
                        .ethType("IPv4")
                        .build())
                .success(true)
                .build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(createExclusionResult);

        CreateExclusionResult createExclusionResultFromJson = mapper.readValue(json, CreateExclusionResult.class);

        assertEquals(createExclusionResult, createExclusionResultFromJson);
    }
}
