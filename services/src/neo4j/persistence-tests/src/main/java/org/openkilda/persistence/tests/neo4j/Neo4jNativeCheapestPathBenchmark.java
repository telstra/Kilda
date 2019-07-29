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

import static java.lang.String.format;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.impl.Neo4jSessionFactory;
import org.openkilda.persistence.tests.TopologyForPceBuilder.Island;
import org.openkilda.persistence.tests.TopologyForPceBuilder.Region;

import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
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

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Neo4jNativeCheapestPathBenchmark extends BaseNeo4jOgmBenchmark {
    private static final String CHEAPEST_PATH_QUERY = "MATCH (start:switch{name:'%s'}), (end:switch{name:'%s'})" +
            " CALL algo.kShortestPaths.stream(start, end, 10, 'cost'," +
            " {nodeQuery:'MATCH(n:switch {status:\"active\"}) RETURN id(n) as id'," +
            " relationshipQuery:'MATCH (n:switch)-[r:isl {status:\"active\"}]->(m:switch) RETURN id(n) as source, id(m) as target, r.cost * 100 as weight'," +
            " direction:'OUT', defaultValue:0.0," +
            " maxDepth:%d, path:true})" +
            " YIELD path, costs" +
            " WITH path, reduce(acc = 0.0, cost in costs | acc + cost) AS totalCost" +
            " WHERE totalCost < %d" +
            " RETURN path, totalCost" +
            " ORDER BY totalCost DESC" +
            " LIMIT 1";

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    @Threads(5)
    public void concurrent5CheapestPathOnCircleTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                                 MediumCircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void concurrent30CheapestPathOnCircleTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                                  MediumCircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void concurrent50CheapestPathOnCircleTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                                  MediumCircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void cheapestPathOnCircleTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                      CircleTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void cheapestPathOnMeshTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                    MeshTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void cheapestPathOnTreeTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                    TreeTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void cheapestPathOnStarTopologyBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                    StarTopologyResources topologyResources, Blackhole blackhole) {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        neo4jPersistenceResources.persistenceManager.getTransactionManager().doInTransaction(() -> {
            cheapestPath(neo4jPersistenceResources.persistenceManager,
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
    public void crossIslandCheapestPathBenchmark(Neo4jPersistenceResources neo4jPersistenceResources,
                                                 SmallCircleTopologyResources topologyResources, Blackhole blackhole) {
        Region startRegion = topologyResources.islands.get(0).getRegions().get(0);
        Region endRegion = topologyResources.islands.get(1).getRegions().get(0);
        SwitchId startSwitch = startRegion.getSwitches().get(0).getSwitchId();
        SwitchId endSwitch = endRegion.getSwitches().get(0).getSwitchId();
        int maxHops = topologyResources.maxHops;

        TransactionManager transactionManager = neo4jPersistenceResources.persistenceManager.getTransactionManager();
        transactionManager.doInTransaction(() -> {
            Session session = ((Neo4jSessionFactory) transactionManager).getSession();
            Result result = session.query(format(
                    CHEAPEST_PATH_QUERY, startSwitch.toString(), endSwitch.toString(), maxHops, Math.max(100, maxHops)),
                    Collections.emptyMap());
            Iterator<Map<String, Object>> iterator = result.iterator();
            if (iterator.hasNext()) {
                throw new IllegalStateException("Found unexpected path: " + iterator.next());
            }
            blackhole.consume(result);
        });
    }

    private void cheapestPath(PersistenceManager persistenceManager, SwitchId startSwitch, SwitchId endSwitch,
                              int maxHops, Blackhole blackhole) {
        Session session = ((Neo4jSessionFactory) persistenceManager.getTransactionManager()).getSession();
        Result result = session.query(format(
                CHEAPEST_PATH_QUERY, startSwitch.toString(), endSwitch.toString(), maxHops, Math.max(100, maxHops)),
                Collections.emptyMap());
        Iterator<Map<String, Object>> iterator = result.iterator();
        if (!iterator.hasNext()) {
            System.out.println("Expected path was not found");
        } else {
            Map<String, Object> results = iterator.next();
            System.out.println("Expected path found: c=" + results.get("totalCost") + ", p=" + results.get("path"));
        }
        blackhole.consume(result);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(Neo4jNativeCheapestPathBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
