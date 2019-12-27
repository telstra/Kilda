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

import org.openkilda.model.FlowGroup;
import org.openkilda.model.GroupId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowGroupRepository;

import com.google.common.collect.ImmutableMap;
import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public class Neo4jFlowGroupRepository extends Neo4jGenericRepository<FlowGroup> implements FlowGroupRepository {
    static final String PATH_ID_PROPERTY_NAME = "path_id";

    public Neo4jFlowGroupRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public Collection<FlowGroup> findByPathId(PathId pathId) {
        Filter pathIdFilter = new Filter(PATH_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, pathId);

        Collection<FlowGroup> groups = loadAll(pathIdFilter);
        if (groups.size() > 1) {
            throw new PersistenceException(format("Found more that 2 Group entity by path (%s). "
                    + " One path must have up to 1 group.", pathId));
        }
        return groups;
    }

    @Override
    public Optional<GroupId> findUnassignedGroupId(SwitchId switchId, GroupId defaultGroupId) {
        Map<String, Object> parameters = ImmutableMap.of(
                "default_group", defaultGroupId.getValue(),
                "switch_id", switchId.toString()
        );

        // The query returns the default_group if it's not used in any flow_group,
        // otherwise locates a gap between / after the values used in flow_group entities.

        String query = "UNWIND [$default_group] AS group "
                + "OPTIONAL MATCH (n:flow_group {switch_id: $switch_id}) "
                + "WHERE group = n.group_id "
                + "WITH group, n "
                + "WHERE n IS NULL "
                + "RETURN group "
                + "UNION ALL "
                + "MATCH (n1:flow_group {switch_id: $switch_id}) "
                + "WHERE n1.group_id >= $default_group "
                + "OPTIONAL MATCH (n2:flow_group {switch_id: $switch_id}) "
                + "WHERE (n1.group_id + 1) = n2.group_id "
                + "WITH n1, n2 "
                + "WHERE n2 IS NULL "
                + "RETURN n1.group_id + 1 AS group "
                + "ORDER BY group "
                + "LIMIT 1";

        return queryForLong(query, parameters, "group").map(GroupId::new);
    }

    @Override
    protected Class<FlowGroup> getEntityType() {
        return FlowGroup.class;
    }
}
