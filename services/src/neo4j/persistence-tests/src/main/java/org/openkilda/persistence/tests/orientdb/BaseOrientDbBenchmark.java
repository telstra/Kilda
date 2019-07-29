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

package org.openkilda.persistence.tests.orientdb;

import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.ferma.OrientDbPersistenceManager;
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

public abstract class BaseOrientDbBenchmark {
    @State(Scope.Benchmark)
    public static class SharedOrientDdPersistence {
        protected OrientDbPersistence persistence;

        @Setup
        public void setUp() throws Exception {
            if (System.getProperty("host") != null) {
                persistence = new RemoteOrientDbPersistence(
                        System.getProperty("host"),
                        System.getProperty("user", "root"),
                        System.getProperty("password", "root"),
                        System.getProperty("database", "demodb"),
                        false);
            } else {
                persistence = new EmbeddedOrientDbPersistence(false);
            }
        }

        @TearDown
        public void tearDown() throws Exception {
            persistence.close();
        }
    }

    @State(Scope.Thread)
    public static class OrientDbPersistenceResources {
        protected OrientDbPersistence persistence;
        protected OrientDbPersistenceManager persistenceManager;

        @Setup(Level.Iteration)
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            persistence = sharedOrientDbPersistence.persistence;
            persistenceManager = persistence.createPersistenceManager();
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
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            try (OrientDbPersistenceManager persistenceManager =
                         sharedOrientDbPersistence.persistence.createPersistenceManager()) {
                TransactionManager transactionManager = persistenceManager.getTransactionManager();
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
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            try (OrientDbPersistenceManager persistenceManager =
                         sharedOrientDbPersistence.persistence.createPersistenceManager()) {
                TransactionManager transactionManager = persistenceManager.getTransactionManager();
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
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            try (OrientDbPersistenceManager persistenceManager =
                         sharedOrientDbPersistence.persistence.createPersistenceManager()) {
                TransactionManager transactionManager = persistenceManager.getTransactionManager();
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
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            try (OrientDbPersistenceManager persistenceManager =
                         sharedOrientDbPersistence.persistence.createPersistenceManager()) {
                TransactionManager transactionManager = persistenceManager.getTransactionManager();
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
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            try (OrientDbPersistenceManager persistenceManager =
                         sharedOrientDbPersistence.persistence.createPersistenceManager()) {
                TransactionManager transactionManager = persistenceManager.getTransactionManager();
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
        public void setUp(SharedOrientDdPersistence sharedOrientDbPersistence) {
            try (OrientDbPersistenceManager persistenceManager =
                         sharedOrientDbPersistence.persistence.createPersistenceManager()) {
                TransactionManager transactionManager = persistenceManager.getTransactionManager();
                islands = transactionManager.doInTransaction(() -> {
                    FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();

                    TopologyBuilder topologyBuilder = new TopologyBuilder(framedGraph, 10, topologySize, 30);
                    return topologyBuilder.buildStars(5);
                });
            }
        }
    }
}
