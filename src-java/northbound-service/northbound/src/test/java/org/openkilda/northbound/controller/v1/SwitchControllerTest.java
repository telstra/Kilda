/* Copyright 2018 Telstra Open Source
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

package org.openkilda.northbound.controller.v1;

import static org.junit.Assert.assertEquals;
import static org.openkilda.messaging.Utils.CORRELATION_ID;
import static org.openkilda.messaging.Utils.EXTRA_AUTH;
import static org.openkilda.messaging.Utils.MAPPER;
import static org.openkilda.northbound.controller.v1.TestMessageMock.TEST_SWITCH_ID;
import static org.openkilda.northbound.controller.v1.TestMessageMock.TEST_SWITCH_RULE_COOKIE;
import static org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.openkilda.northbound.controller.TestConfig;
import org.openkilda.northbound.utils.RequestCorrelationFilter;
import org.openkilda.northbound.utils.RequestCorrelationInspectorFilter;
import org.openkilda.persistence.InMemoryGraphBasedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = TestConfig.class)
public class SwitchControllerTest extends InMemoryGraphBasedTest {

    private static final String USERNAME = "kilda";
    private static final String PASSWORD = "kilda";
    private static final String ROLE = "ADMIN";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Before
    public void setUp() throws Exception {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(new RequestCorrelationInspectorFilter(), new RequestCorrelationFilter())
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = USERNAME, password = PASSWORD, roles = ROLE)
    public void shouldDeleteSwitchRules() throws Exception {
        // given TestMessageMock as kafka topic mocks
        // when
        MvcResult mvcResult = mockMvc.perform(delete("/v1/switches/{switch-id}/rules", TEST_SWITCH_ID)
                .header(CORRELATION_ID, testCorrelationId())
                .header(EXTRA_AUTH, System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(119))
                .contentType(APPLICATION_JSON_VALUE))
                .andReturn();

        // then
        MvcResult result = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(APPLICATION_JSON_UTF8_VALUE))
                .andReturn();
        Long[] response = MAPPER.readValue(result.getResponse().getContentAsString(), Long[].class);
        assertEquals(TEST_SWITCH_RULE_COOKIE, (long) response[0]);
    }

    @Test
    @WithMockUser(username = USERNAME, password = PASSWORD, roles = ROLE)
    public void shouldFailDeleteSwitchRulesWithoutExtraAuth() throws Exception {
        // given TestMessageMock as kafka topic mocks
        // when
        mockMvc.perform(delete("/v1/switches/{switch-id}/rules", TEST_SWITCH_ID)
                .header(CORRELATION_ID, testCorrelationId())
                .contentType(APPLICATION_JSON_VALUE))
                // then
                .andExpect(status().isUnauthorized());
    }

    private static String testCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
