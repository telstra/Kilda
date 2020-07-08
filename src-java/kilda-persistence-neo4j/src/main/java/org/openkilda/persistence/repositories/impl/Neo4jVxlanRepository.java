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

import org.openkilda.model.PathId;
import org.openkilda.model.Vxlan;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.VxlanRepository;

import com.google.common.collect.ImmutableMap;
import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Neo4j OGM implementation of {@link VxlanRepository}.
 */
public class Neo4jVxlanRepository extends Neo4jGenericRepository<Vxlan> implements VxlanRepository {
    static final String PATH_ID_PROPERTY_NAME = "path_id";
    static final String VNI_PROPERTY_NAME = "vni";

    public Neo4jVxlanRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public Collection<Vxlan> findByPathId(PathId pathId, PathId oppositePathId) {
        Filter pathIdFilter = new Filter(PATH_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, pathId);
        Collection<Vxlan> result = loadAll(pathIdFilter);
        if (result.isEmpty() && oppositePathId != null) {
            pathIdFilter = new Filter(PATH_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, oppositePathId);
            result = loadAll(pathIdFilter);
        }
        return result;
    }

    @Override
    public Optional<Vxlan> findByVni(int vni) {
        Filter vniFilter = new Filter(VNI_PROPERTY_NAME, ComparisonOperator.EQUALS, vni);
        Collection<Vxlan> vxlans = loadAll(vniFilter);

        if (vxlans.size() > 1) {
            throw new PersistenceException(format("Found more than 1 VXLAN entity by vni '%s'", vni));
        }
        if (vxlans.size() == 1) {
            return Optional.of(vxlans.iterator().next());
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Integer> findUnassignedVxlan(int minVni, int maxVni) {
        Map<String, Object> parameters = ImmutableMap.of(
                "min_vni", minVni,
                "max_vni", maxVni);

        // The query returns the min_vni if it's not used in any vxlan,
        // otherwise locates a gap between / after the values used in vxlan entities.

        String query = "UNWIND [$min_vni] AS vni "
                + "OPTIONAL MATCH (n:vxlan) "
                + "WHERE vni = n.vni "
                + "WITH vni, n "
                + "WHERE n IS NULL "
                + "RETURN vni "
                + "UNION ALL "
                + "MATCH (n1:vxlan) "
                + "WHERE n1.vni >= $min_vni AND n1.vni < $max_vni "
                + "OPTIONAL MATCH (n2:vxlan) "
                + "WHERE (n1.vni + 1) = n2.vni "
                + "WITH n1, n2 "
                + "WHERE n2 IS NULL "
                + "RETURN n1.vni + 1 AS vni "
                + "ORDER BY vni "
                + "LIMIT 1";

        return queryForLong(query, parameters, "vni").map(Long::intValue);
    }

    @Override
    protected Class<Vxlan> getEntityType() {
        return Vxlan.class;
    }
}
