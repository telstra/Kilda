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

package org.openkilda.persistence.repositories.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.ApplicationRule;
import org.openkilda.model.Cookie;
import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.ApplicationRepository;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Instant;
import java.util.Collection;

public class Neo4jApplicationRepositoryTest extends Neo4jBasedTest {
    private static final String TEST_FLOW_ID = "test_flow_a";
    private static final SwitchId TEST_SWITCH_ID = new SwitchId(1);

    private static ApplicationRepository applicationRepository;

    @BeforeClass
    public static void setUp() {
        applicationRepository = new Neo4jApplicationRepository(neo4jSessionFactory, txManager);
    }

    @Before
    public void init() {
        applicationRepository.createOrUpdate(buildRuleA());
        applicationRepository.createOrUpdate(buildRuleB());
        applicationRepository.createOrUpdate(buildRuleC());
    }

    @Test
    public void shouldLookupRuleByMatchAndFlow() {
        ApplicationRule ruleA = buildRuleA();
        ApplicationRule foundRule = applicationRepository.lookupRuleByMatchAndFlow(ruleA.getSwitchId(),
                ruleA.getFlowId(), ruleA.getSrcIp(), ruleA.getSrcPort(), ruleA.getDstIp(), ruleA.getDstPort(),
                ruleA.getProto(), ruleA.getEthType(), ruleA.getMetadata()).get();

        assertEquals(ruleA, foundRule);
    }

    @Test
    public void shouldLookupRuleByMatchAndCookie() {
        ApplicationRule ruleA = buildRuleA();
        ApplicationRule foundRule = applicationRepository.lookupRuleByMatchAndCookie(ruleA.getSwitchId(),
                ruleA.getCookie(), ruleA.getSrcIp(), ruleA.getSrcPort(), ruleA.getDstIp(), ruleA.getDstPort(),
                ruleA.getProto(), ruleA.getEthType(), ruleA.getMetadata()).get();

        assertEquals(ruleA, foundRule);
    }

    @Test
    public void shouldFindBySwitchId() {
        Collection<ApplicationRule> foundRules = applicationRepository.findBySwitchId(TEST_SWITCH_ID);

        assertEquals(2, foundRules.size());
        assertTrue(foundRules.contains(buildRuleA()));
        assertTrue(foundRules.contains(buildRuleC()));
    }

    @Test
    public void shouldFindByFlowId() {
        Collection<ApplicationRule> foundRules = applicationRepository.findByFlowId(TEST_FLOW_ID);

        assertEquals(2, foundRules.size());
        assertTrue(foundRules.contains(buildRuleA()));
        assertTrue(foundRules.contains(buildRuleB()));
    }

    private ApplicationRule buildRuleA() {
        return ApplicationRule.builder()
                .flowId(TEST_FLOW_ID)
                .switchId(TEST_SWITCH_ID)
                .srcIp("127.0.1.2")
                .srcPort(2)
                .dstIp("127.0.1.3")
                .dstPort(3)
                .proto("UDP")
                .ethType("IPv4")
                .cookie(Cookie.buildExclusionCookie(4L, 5, true))
                .metadata(Metadata.builder().encapsulationId(6).forward(true).build())
                .expirationTimeout(7)
                .timeCreate(Instant.now())
                .build();
    }

    private ApplicationRule buildRuleB() {
        return ApplicationRule.builder()
                .flowId(TEST_FLOW_ID)
                .switchId(new SwitchId(8))
                .srcIp("127.0.1.4")
                .srcPort(9)
                .dstIp("127.0.1.5")
                .dstPort(10)
                .proto("TCP")
                .ethType("IPv4")
                .cookie(Cookie.buildExclusionCookie(11L, 12, true))
                .metadata(Metadata.builder().encapsulationId(6).forward(true).build())
                .expirationTimeout(14)
                .timeCreate(Instant.now())
                .build();
    }

    private ApplicationRule buildRuleC() {
        return ApplicationRule.builder()
                .flowId("test_flow_b")
                .switchId(TEST_SWITCH_ID)
                .srcIp("127.0.1.6")
                .srcPort(15)
                .dstIp("127.0.1.7")
                .dstPort(16)
                .proto("TCP")
                .ethType("IPv4")
                .cookie(Cookie.buildExclusionCookie(17L, 18, true))
                .metadata(Metadata.builder().encapsulationId(19).forward(true).build())
                .expirationTimeout(20)
                .timeCreate(Instant.now())
                .build();
    }
}
