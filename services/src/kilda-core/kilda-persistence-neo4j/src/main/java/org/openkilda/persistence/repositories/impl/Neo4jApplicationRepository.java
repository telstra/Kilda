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

import static org.neo4j.ogm.cypher.ComparisonOperator.EQUALS;

import org.openkilda.model.ApplicationRule;
import org.openkilda.model.Cookie;
import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.ApplicationRepository;

import org.neo4j.ogm.cypher.Filter;
import org.neo4j.ogm.cypher.Filters;

import java.util.Collection;
import java.util.Optional;

public class Neo4jApplicationRepository extends Neo4jGenericRepository<ApplicationRule>
        implements ApplicationRepository {
    private static final String SWITCH_ID_PROPERTY_NAME   = "switch_id";
    private static final String SRC_IP_PROPERTY_NAME   = "src_ip";
    private static final String SRC_PORT_PROPERTY_NAME   = "src_port";
    private static final String DST_IP_PROPERTY_NAME   = "dst_ip";
    private static final String DST_PORT_PROPERTY_NAME   = "dst_port";
    private static final String PROTO_PROPERTY_NAME   = "proto";
    private static final String ETH_TYPE_PROPERTY_NAME   = "eth_type";
    private static final String METADATA_PROPERTY_NAME   = "metadata";
    private static final String FLOW_ID_PROPERTY_NAME   = "flow_id";
    private static final String COOKIE_PROPERTY_NAME   = "cookie";

    public Neo4jApplicationRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    protected Class<ApplicationRule> getEntityType() {
        return ApplicationRule.class;
    }

    @Override
    public Optional<ApplicationRule> lookupRuleByMatchAndFlow(SwitchId switchId, String flowId, String srcIp,
                                                              Integer srcPort, String dstIp, Integer dstPort,
                                                              String proto, String ethType, Metadata metadata) {
        Filters filters = getBaseFilters(switchId, srcIp, srcPort, dstIp, dstPort, proto, ethType, metadata);
        filters.and(new Filter(FLOW_ID_PROPERTY_NAME, EQUALS, flowId));
        return processQuery(filters);
    }

    @Override
    public Optional<ApplicationRule> lookupRuleByMatchAndCookie(SwitchId switchId, Cookie cookie, String srcIp,
                                                                Integer srcPort, String dstIp, Integer dstPort,
                                                                String proto, String ethType, Metadata metadata) {
        Filters filters = getBaseFilters(switchId, srcIp, srcPort, dstIp, dstPort, proto, ethType, metadata);
        filters.and(new Filter(COOKIE_PROPERTY_NAME, EQUALS, cookie));
        return processQuery(filters);
    }

    @Override
    public Collection<ApplicationRule> findBySwitchId(SwitchId switchId) {
        return loadAll(new Filter(SWITCH_ID_PROPERTY_NAME, EQUALS, switchId));
    }

    @Override
    public Collection<ApplicationRule> findByFlowId(String flowId) {
        return loadAll(new Filter(FLOW_ID_PROPERTY_NAME, EQUALS, flowId));
    }

    private Filters getBaseFilters(SwitchId switchId, String srcIp, Integer srcPort, String dstIp,
                                               Integer dstPort, String proto, String ethType, Metadata metadata) {
        Filters filters = new Filters(new Filter(SWITCH_ID_PROPERTY_NAME, EQUALS, switchId));
        filters.and(new Filter(SRC_IP_PROPERTY_NAME, EQUALS, srcIp));
        filters.and(new Filter(SRC_PORT_PROPERTY_NAME, EQUALS, srcPort));
        filters.and(new Filter(DST_IP_PROPERTY_NAME, EQUALS, dstIp));
        filters.and(new Filter(DST_PORT_PROPERTY_NAME, EQUALS, dstPort));
        filters.and(new Filter(PROTO_PROPERTY_NAME, EQUALS, proto));
        filters.and(new Filter(ETH_TYPE_PROPERTY_NAME, EQUALS, ethType));
        filters.and(new Filter(METADATA_PROPERTY_NAME, EQUALS, metadata));

        return filters;
    }

    private Optional<ApplicationRule> processQuery(Filters filters) {
        Collection<ApplicationRule> results = loadAll(filters);

        if (results.size() > 1) {
            throw new PersistenceException("Found more that 1 Application Rule entity by criteria");
        }
        return results.isEmpty() ? Optional.empty() : Optional.of(results.iterator().next());
    }
}
