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

package org.openkilda.persistence;

import org.openkilda.persistence.converters.BfdSessionStatusConverter;
import org.openkilda.persistence.converters.ConnectedDeviceTypeConverter;
import org.openkilda.persistence.converters.CookieConverter;
import org.openkilda.persistence.converters.DurationConverter;
import org.openkilda.persistence.converters.ExclusionCookieConverter;
import org.openkilda.persistence.converters.FlowEncapsulationTypeConverter;
import org.openkilda.persistence.converters.FlowPathStatusConverter;
import org.openkilda.persistence.converters.FlowStatusConverter;
import org.openkilda.persistence.converters.GroupIdConverter;
import org.openkilda.persistence.converters.IslDownReasonConverter;
import org.openkilda.persistence.converters.IslStatusConverter;
import org.openkilda.persistence.converters.MacAddressConverter;
import org.openkilda.persistence.converters.MeterIdConverter;
import org.openkilda.persistence.converters.MirrorDirectionConverter;
import org.openkilda.persistence.converters.MirrorGroupTypeConverter;
import org.openkilda.persistence.converters.PathComputationStrategyConverter;
import org.openkilda.persistence.converters.PathIdConverter;
import org.openkilda.persistence.converters.PortStatusConverter;
import org.openkilda.persistence.converters.SwitchIdConverter;
import org.openkilda.persistence.converters.SwitchStatusConverter;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.impl.Neo4jRepositoryFactory;

import org.neo4j.ogm.config.Configuration.Builder;
import org.neo4j.ogm.session.SessionFactory;

import java.util.Arrays;

/**
 * Neo4j OGM implementation of {@link PersistenceManager}.
 */
public class Neo4jPersistenceManager implements PersistenceManager {
    private final Neo4jConfig neo4jConfig;
    private final NetworkConfig networkConfig;

    private transient volatile Neo4jTransactionManager neo4jTransactionManager;

    public Neo4jPersistenceManager(Neo4jConfig neo4jConfig, NetworkConfig networkConfig) {
        this.neo4jConfig = neo4jConfig;
        this.networkConfig = networkConfig;
    }

    @Override
    public TransactionManager getTransactionManager() {
        return getNeo4jTransactionManager();
    }

    @Override
    public RepositoryFactory getRepositoryFactory() {
        return new Neo4jRepositoryFactory(getNeo4jTransactionManager(), getTransactionManager(), networkConfig);
    }

    private Neo4jTransactionManager getNeo4jTransactionManager() {
        if (neo4jTransactionManager == null) {
            synchronized (this) {
                if (neo4jTransactionManager == null) {
                    Builder configBuilder = new Builder()
                            .uri(neo4jConfig.getUri())
                            .credentials(neo4jConfig.getLogin(), neo4jConfig.getPassword());
                    if (neo4jConfig.getConnectionPoolSize() > 0) {
                        configBuilder.connectionPoolSize(neo4jConfig.getConnectionPoolSize());
                    }

                    if (neo4jConfig.getIndexesAuto() != null) {
                        configBuilder.autoIndex(neo4jConfig.getIndexesAuto());
                    }

                    SessionFactory sessionFactory =
                            new SessionFactory(configBuilder.build(), "org.openkilda.model");
                    sessionFactory.metaData().registerConversionCallback(
                            new SimpleConversionCallback(Arrays.asList(
                                    ConnectedDeviceTypeConverter.class,
                                    CookieConverter.class,
                                    FlowEncapsulationTypeConverter.class,
                                    FlowPathStatusConverter.class,
                                    FlowStatusConverter.class,
                                    IslDownReasonConverter.class,
                                    IslStatusConverter.class,
                                    BfdSessionStatusConverter.class,
                                    MeterIdConverter.class,
                                    MirrorDirectionConverter.class,
                                    MirrorGroupTypeConverter.class,
                                    GroupIdConverter.class,
                                    PathComputationStrategyConverter.class,
                                    PathIdConverter.class,
                                    PortStatusConverter.class,
                                    SwitchIdConverter.class,
                                    SwitchStatusConverter.class,
                                    MacAddressConverter.class,
                                    ExclusionCookieConverter.class,
                                    DurationConverter.class)));

                    neo4jTransactionManager = new Neo4jTransactionManager(sessionFactory);
                }
            }
        }

        return neo4jTransactionManager;
    }
}
