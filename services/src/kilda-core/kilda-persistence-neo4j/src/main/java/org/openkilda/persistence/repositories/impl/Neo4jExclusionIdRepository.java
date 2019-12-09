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

import static java.lang.String.format;

import org.openkilda.model.ExclusionId;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.ExclusionIdRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;

import com.google.common.collect.ImmutableMap;
import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Neo4j OGM implementation of {@link FlowMeterRepository}.
 */
public class Neo4jExclusionIdRepository extends Neo4jGenericRepository<ExclusionId> implements ExclusionIdRepository {
    static final String FLOW_ID_PROPERTY_NAME = "flow_id";
    static final String EXCLUSION_ID_PROPERTY_NAME = "id";

    public Neo4jExclusionIdRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public Collection<ExclusionId> findByFlowId(String flowId) {
        Filter flowIdFilter = new Filter(FLOW_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, flowId);
        return loadAll(flowIdFilter);
    }

    @Override
    public Optional<ExclusionId> find(String flowId, int exclusionId) {
        Filter flowIdFilter = new Filter(FLOW_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, flowId);
        Filter exclusionIdFilter = new Filter(EXCLUSION_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, exclusionId);

        Collection<ExclusionId> exclusionIds = loadAll(flowIdFilter.and(exclusionIdFilter));
        if (exclusionIds.size() > 1) {
            throw new PersistenceException(
                    format("Found more that 1 exclusion id entity with flow id '%s' and exclusion id '%d'",
                    flowId, exclusionId));
        }
        return exclusionIds.isEmpty() ? Optional.empty() : Optional.of(exclusionIds.iterator().next());
    }

    @Override
    public Optional<Integer> findUnassignedExclusionId(String flowId, int defaultExclusionId) {
        Map<String, Object> parameters = ImmutableMap.of(
                "default_exclusion_id", defaultExclusionId,
                "flow_id", flowId
        );

        // The query returns the default_meter if it's not used in any flow_meter,
        // otherwise locates a gap between / after the values used in flow_meter entities.

        String query = "UNWIND [$default_exclusion_id] AS id "
                + "OPTIONAL MATCH (n:exclusion_id {flow_id: $flow_id}) "
                + "WHERE id = n.id "
                + "WITH id, n "
                + "WHERE n IS NULL "
                + "RETURN id "
                + "UNION ALL "
                + "MATCH (n1:exclusion_id {flow_id: $flow_id}) "
                + "WHERE n1.id >= $default_exclusion_id "
                + "OPTIONAL MATCH (n2:exclusion_id {flow_id: $flow_id}) "
                + "WHERE (n1.id + 1) = n2.id "
                + "WITH n1, n2 "
                + "WHERE n2 IS NULL "
                + "RETURN n1.id + 1 AS id "
                + "ORDER BY id "
                + "LIMIT 1";

        return queryForLong(query, parameters, "id").map(Long::intValue);
    }

    @Override
    protected Class<ExclusionId> getEntityType() {
        return ExclusionId.class;
    }
}
