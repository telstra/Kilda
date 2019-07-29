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

package org.openkilda.persistence.tests.neo4j;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.impl.Neo4jSessionFactory;
import org.openkilda.persistence.tests.TopologyForPceBuilder;
import org.openkilda.persistence.tests.TopologyForPceBuilder.Island;

import org.neo4j.ogm.session.Session;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.List;

public abstract class BaseNeo4jOgmBenchmark {
    @State(Scope.Benchmark)
    public static class SharedNeo4jPersistence {
        protected Neo4jOgmPersistence persistence;

        @Setup
        public void setUp() {
            if (System.getProperty("uri") != null) {
                persistence = new RemoteNeo4jOgmPersistence(
                        System.getProperty("uri"),
                        System.getProperty("login", "neo4j"),
                        System.getProperty("password", "root"),
                        false);
            } else {
                persistence = new EmbeddedNeo4jOgmPersistence(false);
            }
        }

        @TearDown
        public void tearDown() throws Exception {
            persistence.close();
        }
    }

    @State(Scope.Thread)
    public static class Neo4jPersistenceResources {
        protected PersistenceManager persistenceManager;
        protected TransactionManager transactionManager;
        public RepositoryFactory repositoryFactory;

        @Setup(Level.Iteration)
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager();
            transactionManager = persistenceManager.getTransactionManager();
            repositoryFactory = persistenceManager.getRepositoryFactory();
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            persistenceManager.close();
        }
    }

    @State(Scope.Benchmark)
    public static class SmallCircleTopologyResources {
        @Param({"3", "5", "10"})
        protected int topologySize;

        @Param({"50", "150", "300"})
        public int maxHops;

        public List<TopologyForPceBuilder.Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (PersistenceManager persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                islands = persistenceManager.getTransactionManager().doInTransaction(() -> {
                    Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
                    TopologyForPceBuilder topologyBuilder =
                            new TopologyForPceBuilder(session, 10, topologySize, 30);
                    return topologyBuilder.buildCircles();
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class MediumCircleTopologyResources {
        @Param({"10"})
        protected int topologySize;

        @Param({"300"})
        public int maxHops;

        public List<TopologyForPceBuilder.Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (PersistenceManager persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                islands = persistenceManager.getTransactionManager().doInTransaction(() -> {
                    Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
                    TopologyForPceBuilder topologyBuilder =
                            new TopologyForPceBuilder(session, 10, topologySize, 30);
                    return topologyBuilder.buildCircles();
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class CircleTopologyResources {
        @Param({"3", "10", "30"})
        protected int topologySize;

        @Param({"50", "300", "1000"})
        public int maxHops;

        public List<TopologyForPceBuilder.Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (PersistenceManager persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                islands = persistenceManager.getTransactionManager().doInTransaction(() -> {
                    Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
                    TopologyForPceBuilder topologyBuilder =
                            new TopologyForPceBuilder(session, 10, topologySize, 30);
                    return topologyBuilder.buildCircles();
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class MeshTopologyResources {
        @Param({"3", "10", "30"})
        protected int topologySize;

        @Param({"50", "300", "1000"})
        public int maxHops;

        public List<TopologyForPceBuilder.Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (PersistenceManager persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                islands = persistenceManager.getTransactionManager().doInTransaction(() -> {
                    Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
                    TopologyForPceBuilder topologyBuilder =
                            new TopologyForPceBuilder(session, 10, topologySize, 30);
                    return topologyBuilder.buildMeshes();
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class TreeTopologyResources {
        @Param({"3", "10", "30"})
        protected int topologySize;

        @Param({"50", "300", "1000"})
        public int maxHops;

        public List<TopologyForPceBuilder.Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (PersistenceManager persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                islands = persistenceManager.getTransactionManager().doInTransaction(() -> {
                    Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
                    TopologyForPceBuilder topologyBuilder =
                            new TopologyForPceBuilder(session, 10, topologySize, 30);
                    return topologyBuilder.buildTree(5);
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class StarTopologyResources {
        @Param({"3", "10", "30"})
        protected int topologySize;

        @Param({"50", "300", "1000"})
        public int maxHops;

        public List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (PersistenceManager persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                islands = persistenceManager.getTransactionManager().doInTransaction(() -> {
                    Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
                    TopologyForPceBuilder topologyBuilder =
                            new TopologyForPceBuilder(session, 10, topologySize, 30);
                    return topologyBuilder.buildStars(5);
                });
            }
        }
    }
}
