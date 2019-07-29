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

import static org.apache.tinkerpop.gremlin.process.traversal.Operator.sum;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.hasLabel;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.outE;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.ferma.repositories.frames.IslFrame;
import org.openkilda.persistence.ferma.repositories.frames.SwitchFrame;
import org.openkilda.persistence.tests.TopologyBuilder.Island;
import org.openkilda.persistence.tests.TopologyBuilder.Region;

import com.syncleus.ferma.FramedGraph;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OrientDbTinkerpopCheapestPathBenchmark extends BaseOrientDbBenchmark {
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    @Threads(5)
    public void concurrent5CheapestPathOnCircleTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                                 MediumCircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() -> {
            cheapestPath(transactionManager,
                    startRegion.getSwitches().get(0).getSwitchId(),
                    endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                    topologyResources.maxHops,
                    blackhole);
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    @Threads(30)
    public void concurrent30CheapestPathOnCircleTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                                  MediumCircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() -> {
            cheapestPath(transactionManager,
                    startRegion.getSwitches().get(0).getSwitchId(),
                    endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                    topologyResources.maxHops,
                    blackhole);
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    @Threads(50)
    public void concurrent50CheapestPathOnCircleTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                                  MediumCircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() -> {
            cheapestPath(transactionManager,
                    startRegion.getSwitches().get(0).getSwitchId(),
                    endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                    topologyResources.maxHops,
                    blackhole);
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void cheapestPathOnCircleTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                      CircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() ->
                cheapestPath(transactionManager,
                        startRegion.getSwitches().get(0).getSwitchId(),
                        endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                        topologyResources.maxHops,
                        blackhole));
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void cheapestPathOnMeshTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                    MeshTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() ->
                cheapestPath(transactionManager,
                        startRegion.getSwitches().get(0).getSwitchId(),
                        endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                        topologyResources.maxHops,
                        blackhole));
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void cheapestPathOnTreeTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                    TreeTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() ->
                cheapestPath(transactionManager,
                        startRegion.getSwitches().get(0).getSwitchId(),
                        endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                        topologyResources.maxHops,
                        blackhole));
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void cheapestPathOnStarTopologyBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                    StarTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() ->
                cheapestPath(transactionManager,
                        startRegion.getSwitches().get(0).getSwitchId(),
                        endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                        topologyResources.maxHops,
                        blackhole));
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void crossIslandCheapestPathBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                 SmallCircleTopologyResources topologyResources, Blackhole blackhole) {
        Region startRegion = topologyResources.islands.get(0).getRegions().get(0);
        Region endRegion = topologyResources.islands.get(1).getRegions().get(0);
        SwitchId startSwitch = startRegion.getSwitches().get(0).getSwitchId();
        SwitchId endSwitch = endRegion.getSwitches().get(0).getSwitchId();
        int maxHops = topologyResources.maxHops;

        TransactionManager transactionManager = orientDbPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() -> {
            FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();
            GraphTraversal<?, ?> rawTraversal = framedGraph.traverse(input -> input.withSack(0)
                    .V().hasLabel(SwitchFrame.FRAME_LABEL).has(SwitchFrame.SWITCH_ID_PROPERTY, startSwitch.toString())
                    .repeat(outE(IslFrame.FRAME_LABEL).has(IslFrame.STATUS_PROPERTY, "active")
                            .sack(sum).by("cost")
                            .inV().has(SwitchFrame.STATUS_PROPERTY, "active").simplePath())
                    .until(hasLabel(SwitchFrame.FRAME_LABEL).has(SwitchFrame.SWITCH_ID_PROPERTY, endSwitch.toString())
                            .or().loops().is(maxHops)
                            .or().sack().is(P.gte(Math.max(100, maxHops))))
                    .has(SwitchFrame.SWITCH_ID_PROPERTY, endSwitch.toString())
                    .limit(10)
                    .path()
                    .as("p")
                    .sack()
                    .as("c")
                    .order().by(Order.decr)
                    .select("p", "c")
                    .limit(1)
            ).getRawTraversal();
            if (rawTraversal.hasNext()) {
                throw new IllegalStateException("Found unexpected path: " + rawTraversal.next());
            }
        });
    }

    private void cheapestPath(TransactionManager transactionManager, SwitchId startSwitch, SwitchId endSwitch,
                              int maxHops, Blackhole blackhole) {
        FramedGraph framedGraph = ((FermaGraphFactory) transactionManager).getFramedGraph();
        GraphTraversal<?, ?> rawTraversal = framedGraph.traverse(input -> input.withSack(0)
                .V().hasLabel(SwitchFrame.FRAME_LABEL).has(SwitchFrame.SWITCH_ID_PROPERTY, startSwitch.toString())
                .repeat(outE(IslFrame.FRAME_LABEL).has(IslFrame.STATUS_PROPERTY, "active")
                        .sack(sum).by("cost")
                        .inV().has(SwitchFrame.STATUS_PROPERTY, "active").simplePath())
                .until(hasLabel(SwitchFrame.FRAME_LABEL).has(SwitchFrame.SWITCH_ID_PROPERTY, endSwitch.toString())
                        .or().loops().is(maxHops)
                        .or().sack().is(P.gte(Math.max(100, maxHops))))
                .has(SwitchFrame.SWITCH_ID_PROPERTY, endSwitch.toString())
                .limit(10)
                .path()
                .as("p")
                .sack()
                .as("c")
                .order().by(Order.decr)
                .select("p", "c")
                .limit(1)
        ).getRawTraversal();
        if (!rawTraversal.hasNext()) {
            System.out.println("Expected path was not found");
        } else {
            Map result = (Map) rawTraversal.next();
            System.out.println("Result c=" + result.get("c") + ", p=" + result.get("p"));
            for (Object hop : (Path) result.get("p")) {
                if (hop instanceof Vertex) {
                    blackhole.consume(((Vertex) hop).getProperty(SwitchFrame.SWITCH_ID_PROPERTY));
                } else if (hop instanceof Edge) {
                    blackhole.consume(((Edge) hop).getProperty(IslFrame.COST_PROPERTY));
                }
            }
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(OrientDbTinkerpopCheapestPathBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
