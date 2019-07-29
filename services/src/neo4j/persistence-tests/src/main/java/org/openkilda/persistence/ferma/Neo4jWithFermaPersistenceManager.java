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

package org.openkilda.persistence.ferma;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.ferma.repositories.FermaRepositoryFactory;

import com.google.common.annotations.VisibleForTesting;
import com.steelbridgelabs.oss.neo4j.structure.Neo4JGraph;

/**
 * Neo4j with Ferma implementation of {@link PersistenceManager}.
 */
public class Neo4jWithFermaPersistenceManager implements AutoCloseable {
    @VisibleForTesting
    public final Neo4JGraph neo4JGraph;

    private transient volatile Neo4jWithFermaTransactionManager transactionManager;

    public Neo4jWithFermaPersistenceManager(Neo4JGraph neo4JGraph) {
        this.neo4JGraph = neo4JGraph;
    }

    public Neo4jWithFermaTransactionManager getTransactionManager() {
        return getNeo4jWithFermaTransactionManager();
    }

    public FermaRepositoryFactory getRepositoryFactory() {
        return new FermaRepositoryFactory(getNeo4jWithFermaTransactionManager(), getTransactionManager());
    }

    private Neo4jWithFermaTransactionManager getNeo4jWithFermaTransactionManager() {
        if (transactionManager == null) {
            synchronized (this) {
                if (transactionManager == null) {
                    transactionManager = new Neo4jWithFermaTransactionManager(neo4JGraph);
                }
            }
        }

        return transactionManager;
    }

    @Override
    public void close() {
        if (transactionManager != null) {
            synchronized (this) {
                if (transactionManager != null) {
                    neo4JGraph.close();
                    transactionManager = null;
                }
            }
        }
    }
}
