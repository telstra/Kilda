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

import static java.lang.String.format;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.tests.TopologyBuilder.Island;
import org.openkilda.persistence.tests.TopologyBuilder.Region;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

public class OrientDbNativeCheapestPathBenchmark extends BaseOrientDbBenchmark {
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

        cheapestPath(orientDbPersistenceResources,
                startRegion.getSwitches().get(0).getSwitchId(),
                endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                topologyResources.maxHops,
                blackhole);
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

        cheapestPath(orientDbPersistenceResources,
                startRegion.getSwitches().get(0).getSwitchId(),
                endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                topologyResources.maxHops,
                blackhole);
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

        cheapestPath(orientDbPersistenceResources,
                startRegion.getSwitches().get(0).getSwitchId(),
                endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                topologyResources.maxHops,
                blackhole);
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

        cheapestPath(orientDbPersistenceResources,
                startRegion.getSwitches().get(0).getSwitchId(),
                endRegion.getSwitches().get(endRegion.getSwitches().size() / 2).getSwitchId(),
                topologyResources.maxHops,
                blackhole);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void crossIslAndCheapestPathBenchmark(OrientDbPersistenceResources orientDbPersistenceResources,
                                                 SmallCircleTopologyResources topologyResources, Blackhole blackhole) {
        Region startRegion = topologyResources.islands.get(0).getRegions().get(0);
        Region endRegion = topologyResources.islands.get(1).getRegions().get(0);

        OrientDB orientDb = orientDbPersistenceResources.persistenceManager.orientDb;
        OrientDbPersistence persistence = orientDbPersistenceResources.persistence;
        try (ODatabaseSession session = orientDb.open(persistence.getDbName(), persistence.getDbUser(),
                persistence.getDbPassword())) {
            String query = format("SELECT expand(path) FROM (" +
                            " SELECT astar($from, $to, 'cost'," +
                            " {'direction': 'OUT', 'edgeTypeName': ['isl'], 'maxDepth': %d}) AS path FROM V" +
                            " LET $from = (SELECT FROM switch WHERE name='%s')," +
                            " $to = (SELECT FROM switch WHERE name='%s')" +
                            " UNWIND path)", topologyResources.maxHops,
                    startRegion.getSwitches().get(0).getSwitchId().toString(),
                    endRegion.getSwitches().get(0).getSwitchId().toString());
            OResultSet result = session.execute("sql", query);
            if (result.hasNext()) {
                throw new IllegalStateException("Found unexpected path: " + result.next());
            }
        }
    }

    private void cheapestPath(OrientDbPersistenceResources orientDbPersistenceResources, SwitchId startSwitch, SwitchId endSwitch,
                              int maxHops, Blackhole blackhole) {
        OrientDB orientDb = orientDbPersistenceResources.persistenceManager.orientDb;
        OrientDbPersistence persistence = orientDbPersistenceResources.persistence;
        try (ODatabaseSession session = orientDb.open(persistence.getDbName(), persistence.getDbUser(),
                persistence.getDbPassword())) {
            String query = format("SELECT expand(path) FROM (" +
                    " SELECT astar($from, $to, 'cost'," +
                    " {'direction': 'OUT', 'edgeTypeName': ['isl'], 'maxDepth': %d}) AS path FROM V" +
                    " LET $from = (SELECT FROM switch WHERE name='%s')," +
                    " $to = (SELECT FROM switch WHERE name='%s')" +
                    "  UNWIND path)", maxHops, startSwitch.toString(), endSwitch.toString());
            OResultSet result = session.execute("sql", query);
            if (!result.hasNext() || !result.next().isVertex()) {
                System.out.println("Expected path was not found");
            }

            blackhole.consume(result);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(OrientDbNativeCheapestPathBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
