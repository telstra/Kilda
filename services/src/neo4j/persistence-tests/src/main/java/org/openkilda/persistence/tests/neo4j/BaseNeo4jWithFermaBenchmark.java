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

import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.ferma.Neo4jWithFermaPersistenceManager;
import org.openkilda.persistence.ferma.Neo4jWithFermaTransactionManager;
import org.openkilda.persistence.tests.TopologyBuilder;
import org.openkilda.persistence.tests.TopologyBuilder.Island;

import com.syncleus.ferma.FramedGraph;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.List;

public abstract class BaseNeo4jWithFermaBenchmark {
    @State(Scope.Benchmark)
    public static class SharedNeo4jPersistence {
        protected RemoteNeo4jWithFermaPersistence persistence;

        @Setup
        public void setUp() {
            persistence = new RemoteNeo4jWithFermaPersistence(
                    System.getProperty("uri", "bolt://localhost"),
                    System.getProperty("login", "neo4j"),
                    System.getProperty("password", "root"),
                    false);
        }

        @TearDown
        public void tearDown() {
            persistence.close();
        }
    }

    @State(Scope.Thread)
    public static class Neo4jWithFermaPersistenceResources {
        protected Neo4jWithFermaPersistenceManager persistenceManager;

        @Setup(Level.Iteration)
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            persistenceManager = sharedNeo4jPersistence.persistence.createPersistenceManager();
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
        protected int maxHops;

        protected List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (Neo4jWithFermaPersistenceManager persistenceManager =
                         sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                Neo4jWithFermaTransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
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
        protected int maxHops;

        protected List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (Neo4jWithFermaPersistenceManager persistenceManager =
                         sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                Neo4jWithFermaTransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
                    return topologyBuilder.buildCircles();
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class CircleTopologyResources {
        @Param({"3", "10"/*, "30"*/})
        protected int topologySize;

        @Param({"50", "300", "1000"})
        protected int maxHops;

        protected List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (Neo4jWithFermaPersistenceManager persistenceManager =
                         sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                Neo4jWithFermaTransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
                    return topologyBuilder.buildCircles();
                });
            }
        }
    }

    @State(Scope.Benchmark)
    public static class MeshTopologyResources {
        @Param({"3", "10"/*, "30"*/})
        protected int topologySize;

        @Param({"50", "300", "1000"})
        protected int maxHops;

        protected List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (Neo4jWithFermaPersistenceManager persistenceManager =
                         sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                Neo4jWithFermaTransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
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
        protected int maxHops;

        protected List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (Neo4jWithFermaPersistenceManager persistenceManager =
                         sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                Neo4jWithFermaTransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
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
        protected int maxHops;

        protected List<Island> islands;

        @Setup
        public void setUp(SharedNeo4jPersistence sharedNeo4jPersistence) {
            try (Neo4jWithFermaPersistenceManager persistenceManager =
                         sharedNeo4jPersistence.persistence.createPersistenceManager()) {
                Neo4jWithFermaTransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
                    return topologyBuilder.buildStars(5);
                });
            }
        }
    }
}
