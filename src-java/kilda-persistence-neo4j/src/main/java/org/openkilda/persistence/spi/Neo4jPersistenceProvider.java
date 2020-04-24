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

package org.openkilda.persistence.spi;

import org.openkilda.config.provider.ConfigurationProvider;
import org.openkilda.persistence.Neo4jConfig;
import org.openkilda.persistence.Neo4jPersistenceManager;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.ThreadLocalPersistenceContextHolder;
import org.openkilda.persistence.context.PersistenceContextManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Neo4j implementation of the provider for persistence manager(s).
 * Built on top of Tinkerpop / Ferma implementation.
 */
@Slf4j
public class Neo4jPersistenceProvider implements PersistenceProvider {
    @Override
    public PersistenceManager getPersistenceManager(ConfigurationProvider configurationProvider) {
        Neo4jConfig neo4jConfig = configurationProvider.getConfiguration(Neo4jConfig.class);
        NetworkConfig networkConfig = configurationProvider.getConfiguration(NetworkConfig.class);
        log.debug("Creating an instance of PersistenceManager for {}", neo4jConfig);
        return new Neo4jPersistenceManager(neo4jConfig, networkConfig);
    }

    @Override
    public PersistenceContextManager getPersistenceContextManager() {
        return ThreadLocalPersistenceContextHolder.INSTANCE;
    }
}
