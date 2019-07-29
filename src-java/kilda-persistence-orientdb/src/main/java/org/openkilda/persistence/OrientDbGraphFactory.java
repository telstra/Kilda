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

import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.typeresolvers.UntypedTypeResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.orientdb.OrientGraph;
import org.apache.tinkerpop.gremlin.orientdb.OrientGraphFactory;

/**
 * A factory creates graph instances for interacting with Neo4j.
 */
@Slf4j
class OrientDbGraphFactory implements FramedGraphFactory {
    private final OrientGraphFactory factory;

    OrientDbGraphFactory(OrientDbConfig config) {
        log.debug("Opening a graph for {}", config);

        factory = new OrientGraphFactory(config.getUrl(), config.getUser(), config.getPassword());
        factory.setupPool(config.getPoolSize());
        log.debug("OrientGraphFactory instance has been created: {}", factory);
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
            log.debug("Opening a framed graph for {}", factory);
            OrientGraph orientGraph = factory.getTx();
            log.debug("OrientGraph instance has been created: {}", orientGraph);
            result = new DelegatingFramedGraph<>(orientGraph,
                    new AnnotationFrameFactoryWithConverterSupport(), new UntypedTypeResolver());
            ThreadLocalPersistenceContextHolder.INSTANCE.setCurrentGraph(result);
        }
        return result;
    }
}

