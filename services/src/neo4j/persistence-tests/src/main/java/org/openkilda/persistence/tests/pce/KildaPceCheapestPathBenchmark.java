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

package org.openkilda.persistence.tests.pce;

import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.Path;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathComputerFactory;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.persistence.tests.TopologyForPceBuilder.Island;
import org.openkilda.persistence.tests.TopologyForPceBuilder.Region;
import org.openkilda.persistence.tests.neo4j.BaseNeo4jOgmBenchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class KildaPceCheapestPathBenchmark extends BaseNeo4jOgmBenchmark {
    @State(Scope.Thread)
    public static class PathComputerPersistence {
        AtomicInteger maxAllowedDepth = new AtomicInteger();
        PathComputerFactory pathComputerFactory;

        @Setup(Level.Iteration)
        public void setUp(Neo4jPersistenceResources neo4jPersistenceResources) {
            PropertiesBasedConfigurationProvider configurationProvider = new PropertiesBasedConfigurationProvider();
            PathComputerConfig defaultPathComputerConfig =
                    configurationProvider.getConfiguration(PathComputerConfig.class);

            PathComputerConfig pathComputerConfig = new PathComputerConfig() {
                @Override
                public int getMaxAllowedDepth() {
                    return maxAllowedDepth.get();
                }

                @Override
                public int getDefaultIslCost() {
                    return defaultPathComputerConfig.getDefaultIslCost();
                }

                @Override
                public int getDiversityIslCost() {
                    return defaultPathComputerConfig.getDiversityIslCost();
                }

                @Override
                public int getDiversitySwitchCost() {
                    return defaultPathComputerConfig.getDiversitySwitchCost();
                }

                @Override
                public String getNetworkStrategy() {
                    return defaultPathComputerConfig.getNetworkStrategy();
                }

                @Override
                public int getDiversityPopIslCost() {
                    return defaultPathComputerConfig.getDiversityPopIslCost();
                }

                @Override
                public int getUnstableCostRaise() {
                    return defaultPathComputerConfig.getUnstableCostRaise();
                }

                @Override
                public int getUnderMaintenanceCostRaise() {
                    return defaultPathComputerConfig.getUnderMaintenanceCostRaise();
                }

                @Override
                public long getDefaultIslLatency() {
                    return defaultPathComputerConfig.getDefaultIslLatency();
                }

                @Override
                public long getDiversityIslLatency() {
                    return defaultPathComputerConfig.getDiversityIslLatency();
                }

                @Override
                public long getDiversitySwitchLatency() {
                    return defaultPathComputerConfig.getDiversitySwitchLatency();
                }

                @Override
                public long getUnstableLatencyRaise() {
                    return defaultPathComputerConfig.getUnstableLatencyRaise();
                }

                @Override
                public long getUnderMaintenanceLatencyRaise() {
                    return defaultPathComputerConfig.getUnderMaintenanceLatencyRaise();
                }
            };

            AvailableNetworkFactory availableNetworkFactory =
                    new AvailableNetworkFactory(pathComputerConfig, neo4jPersistenceResources.repositoryFactory);
            pathComputerFactory = new PathComputerFactory(pathComputerConfig, availableNetworkFactory);
        }

        PathComputer getPathComputer(int maxHops) {
            maxAllowedDepth.set(maxHops);
            return pathComputerFactory.getPathComputer();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    @Threads(5)
    public void concurrent5CheapestPathOnCircleTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                                 MediumCircleTopologyResources topologyResources, Blackhole blackhole)
            throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    @Threads(30)
    public void concurrent30CheapestPathOnCircleTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                                  MediumCircleTopologyResources topologyResources, Blackhole blackhole)
            throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    @Threads(50)
    public void concurrent50CheapestPathOnCircleTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                                  MediumCircleTopologyResources topologyResources, Blackhole blackhole)
            throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    public void cheapestPathOnCircleTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                      CircleTopologyResources topologyResources, Blackhole blackhole) throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    public void cheapestPathOnMeshTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                    MeshTopologyResources topologyResources, Blackhole blackhole) throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    public void cheapestPathOnTreeTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                    TreeTopologyResources topologyResources, Blackhole blackhole) throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    public void cheapestPathOnStarTopologyBenchmark(PathComputerPersistence pathComputerPersistence,
                                                    StarTopologyResources topologyResources, Blackhole blackhole) throws RecoverableException {
        Island island = topologyResources.islands.get(0);
        Region startRegion = island.getRegions().get(0);
        Region endRegion = island.getRegions().get(island.getRegions().size() / 2);

        cheapestPath(pathComputerPersistence,
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
    public void crossIslandCheapestPathBenchmark(PathComputerPersistence pathComputerPersistence,
                                                 SmallCircleTopologyResources topologyResources, Blackhole blackhole) throws RecoverableException {
        Region startRegion = topologyResources.islands.get(0).getRegions().get(0);
        Region endRegion = topologyResources.islands.get(1).getRegions().get(0);

        try {
            List<Path> paths = pathComputerPersistence.getPathComputer(topologyResources.maxHops).getNPaths(
                    startRegion.getSwitches().get(0).getSwitchId(),
                    endRegion.getSwitches().get(0).getSwitchId(), 10, FlowEncapsulationType.TRANSIT_VLAN,
                    PathComputationStrategy.COST);
            if (!paths.isEmpty() && !paths.get(0).getSegments().isEmpty()) {
                throw new IllegalStateException("Found unexpected path: " + paths.get(0));
            }
            blackhole.consume(paths);
        } catch (UnroutableFlowException ex) {
            System.out.println("Path was not found (as expected): " + ex.toString());
        }
    }

    private void cheapestPath(PathComputerPersistence pathComputerPersistence, SwitchId startSwitch, SwitchId endSwitch,
                              int maxHops, Blackhole blackhole) throws RecoverableException {
        try {
            List<Path> paths = pathComputerPersistence.getPathComputer(maxHops)
                    .getNPaths(startSwitch, endSwitch, 10, FlowEncapsulationType.TRANSIT_VLAN,
                            PathComputationStrategy.COST);
            if (paths.isEmpty() || paths.get(0).getSegments().isEmpty()) {
                System.out.println("Expected path was not found");
            }
            blackhole.consume(paths);
        } catch (UnroutableFlowException ex) {
            throw new IllegalStateException("Expected path was not found: " + ex.toString());
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(KildaPceCheapestPathBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
