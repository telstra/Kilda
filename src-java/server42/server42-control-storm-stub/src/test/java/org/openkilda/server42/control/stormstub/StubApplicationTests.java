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

package org.openkilda.server42.control.stormstub;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.openkilda.server42.control.stormstub.api.AddFlowPayload;
import org.openkilda.server42.control.stormstub.api.ListFlowsPayload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Slf4j
class StubApplicationTests {
    private final AddFlowPayload testFlowVlan1001 = AddFlowPayload.builder()
            .flowId("TestFlowVlan1001").tunnelId(1001L).build();
    private final AddFlowPayload testFlowVxlan2002 = AddFlowPayload.builder()
            .flowId("TestFlowVxlan2002").tunnelId(2002L).build();
    private final AddFlowPayload testFlowVxlan3003 = AddFlowPayload.builder()
            .flowId("testFlowVxlan3003").tunnelId(3003L).build();

    @Value("${openkilda.server42.control.switch}")
    private String switchId;
    @Autowired
    private MockMvc mockMvc;
    private ObjectWriter objectWriter;

    public StubApplicationTests() {
        ObjectMapper mapper = new ObjectMapper();
        objectWriter = mapper.writer().withDefaultPrettyPrinter();
    }

    @BeforeEach
    public void clearFlows() throws Exception {
        mockMvc.perform(delete("/kafka/flow/").param("switchId", switchId)).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(
                get("/kafka/flow/").param("switchId", switchId)).andReturn();
        ListFlowsPayload emptyPayload = new ListFlowsPayload();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().json(objectWriter.writeValueAsString(emptyPayload)));
    }

    @Test
    public void addFlows() throws Exception {
        pushFlow(testFlowVlan1001);
        pushFlow(testFlowVxlan2002);

        ListFlowsPayload listFlowsPayload = new ListFlowsPayload();
        listFlowsPayload.getFlowIds().add(testFlowVlan1001.getFlowId());
        listFlowsPayload.getFlowIds().add(testFlowVxlan2002.getFlowId());

        MvcResult result = mockMvc.perform(
                get("/kafka/flow/").param("switchId", switchId)).andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().json(objectWriter.writeValueAsString(listFlowsPayload)));
    }

    private void pushFlow(AddFlowPayload flowPayload) throws Exception {
        String payload = objectWriter.writeValueAsString(flowPayload);
        mockMvc.perform(
                post("/kafka/flow")
                        .contentType(APPLICATION_JSON_VALUE)
                        .param("switchId", switchId)
                        .content(payload)).andExpect(status().isOk());
    }

    @Test
    public void deleteFlow() throws Exception {
        pushFlow(testFlowVlan1001);
        pushFlow(testFlowVxlan2002);
        pushFlow(testFlowVxlan3003);

        mockMvc.perform(delete("/kafka/flow/{id}", testFlowVxlan2002.getFlowId())
                .param("switchId", switchId)).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(get("/kafka/flow/")
                .param("switchId", switchId)).andReturn();
        ListFlowsPayload listFlowsPayload = new ListFlowsPayload();
        listFlowsPayload.getFlowIds().add(testFlowVlan1001.getFlowId());
        listFlowsPayload.getFlowIds().add(testFlowVxlan3003.getFlowId());
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().json(objectWriter.writeValueAsString(listFlowsPayload)));
    }
}
