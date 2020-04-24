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

package org.openkilda.persistence;

import org.openkilda.persistence.ferma.AnnotationFrameFactoryWithConverterSupport;
import org.openkilda.persistence.ferma.FramedGraphFactory;

import com.steelbridgelabs.oss.neo4j.structure.Neo4JElementIdProvider;
import com.steelbridgelabs.oss.neo4j.structure.Neo4JGraph;
import com.steelbridgelabs.oss.neo4j.structure.providers.DatabaseSequenceElementIdProvider;
import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.typeresolvers.UntypedTypeResolver;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.v1.AuthToken;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Config;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.types.Entity;

/**
 * A factory for framed graphs that are Tinkerpop / Ferma abstraction for interacting with Neo4j.
 */
@Slf4j
class Neo4jGraphFactory implements FramedGraphFactory<DelegatingFramedGraph<?>> {
    private final Neo4JGraph neo4jGraph;

    Neo4jGraphFactory(Neo4jConfig neo4jConfig) {
        log.debug("Opening a driver connection for {}", neo4jConfig);
        AuthToken authToken = AuthTokens.basic(neo4jConfig.getLogin(), neo4jConfig.getPassword());
        Config config = Config.build()
                .withMaxConnectionPoolSize(neo4jConfig.getConnectionPoolSize()).toConfig();
        Driver driver = GraphDatabase.driver(neo4jConfig.getUri(), authToken, config);
        log.debug("Neo4 driver instance has been created: {}", driver);

        Neo4JElementIdProvider<?> vertexIdProvider = new CustomDatabaseSequenceElementIdProvider(driver);
        Neo4JElementIdProvider<?> edgeIdProvider = new CustomDatabaseSequenceElementIdProvider(driver);
        neo4jGraph = new Neo4JGraph(driver, vertexIdProvider, edgeIdProvider);
        neo4jGraph.addCloseListener(g -> ThreadLocalPersistenceContextHolder.INSTANCE.removeCurrentGraph());
        log.debug("Neo4JGraph instance has been created: {}", neo4jGraph);
    }

    /**
     * Returns an instance of framed graph which is bound to the current persistence context.
     * Create a new one if there's no such.
     */
    @Override
    public DelegatingFramedGraph<?> getGraph() {
        if (!ThreadLocalPersistenceContextHolder.INSTANCE.isContextInitialized()) {
            throw new PersistenceException("Persistence context is not initialized");
        }

        DelegatingFramedGraph<?> result = ThreadLocalPersistenceContextHolder.INSTANCE.getCurrentGraph();
        if (result == null) {
            log.debug("Opening a framed graph for {}", neo4jGraph);
            result = new DelegatingFramedGraph<>(neo4jGraph,
                    new AnnotationFrameFactoryWithConverterSupport(), new UntypedTypeResolver());
            ThreadLocalPersistenceContextHolder.INSTANCE.setCurrentGraph(result);
        }

        return result;
    }

    static final class CustomDatabaseSequenceElementIdProvider extends DatabaseSequenceElementIdProvider {
        public CustomDatabaseSequenceElementIdProvider(Driver driver) {
            super(driver);
        }

        @Override
        public Long get(Entity entity) {
            // A workaround for internal entities, which has no custom ID field set.
            return entity.get(DefaultIdFieldName).isNull() ? entity.id() : super.get(entity);
        }
    }
}
