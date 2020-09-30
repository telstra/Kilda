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

package org.openkilda.persistence.repositories.impl;

import static java.lang.String.format;

import org.openkilda.model.BfdSession;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.BfdSessionRepository;

import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;
import org.neo4j.ogm.cypher.Filters;

import java.util.Collection;
import java.util.Optional;

/**
 * Neo4J OGM implementation of {@link BfdSessionRepository}.
 */
public class Neo4JBfdSessionRepository extends Neo4jGenericRepository<BfdSession> implements BfdSessionRepository {
    public Neo4JBfdSessionRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public boolean exists(SwitchId switchId, Integer port) {
        return getSession().count(getEntityType(), makeLogicalPortFilters(switchId, port)) > 0;
    }

    @Override
    public Optional<BfdSession> findBySwitchIdAndPort(SwitchId switchId, int port) {
        return findOneOrNone(makeLogicalPortFilters(switchId, port));
    }

    @Override
    public Optional<BfdSession> findBySwitchIdAndPhysicalPort(SwitchId switchId, int physicalPort) {
        return findOneOrNone(makePhysicalPortFilters(switchId, physicalPort));
    }

    private Optional<BfdSession> findOneOrNone(Filters filters) {
        Collection<BfdSession> ports = loadAll(filters);
        if (ports.size() > 1) {
            throw new PersistenceException(format("Found more that 1 BfdSession entity by %s", filters));
        }
        return ports.isEmpty() ? Optional.empty() : Optional.of(ports.iterator().next());
    }

    @Override
    protected Class<BfdSession> getEntityType() {
        return BfdSession.class;
    }

    private static Filters makeLogicalPortFilters(SwitchId switchId, int port) {
        Filter switchIdFilter = new Filter(BfdSession.SWITCH_PROPERTY_NAME, ComparisonOperator.EQUALS, switchId);
        return switchIdFilter.and(new Filter(BfdSession.PORT_PROPERTY_NAME, ComparisonOperator.EQUALS, port));
    }

    private static Filters makePhysicalPortFilters(SwitchId switchId, int port) {
        Filter switchIdFilter = new Filter(BfdSession.SWITCH_PROPERTY_NAME, ComparisonOperator.EQUALS, switchId);
        return switchIdFilter.and(new Filter(BfdSession.PHYSICAL_PORT_PROPERTY_NAME, ComparisonOperator.EQUALS, port));
    }
}
