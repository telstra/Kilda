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

import org.openkilda.model.PathId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.Neo4jWithFermaPersistenceManager;
import org.openkilda.persistence.ferma.model.Flow;
import org.openkilda.persistence.ferma.model.FlowImpl;
import org.openkilda.persistence.ferma.model.FlowPath;
import org.openkilda.persistence.ferma.model.FlowPathImpl;
import org.openkilda.persistence.ferma.repositories.FermaRepositoryFactory;
import org.openkilda.persistence.ferma.repositories.FlowRepository;
import org.openkilda.persistence.ferma.repositories.frames.FlowFrame;
import org.openkilda.persistence.ferma.repositories.frames.FlowPathFrame;

import net.jodah.failsafe.RetryPolicy;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Neo4jWithFermaPersistenceBenchmark {
    @State(Scope.Benchmark)
    public static class SharedNeo4jWithFermaPersistence {
        RemoteNeo4jWithFermaPersistence persistence;

        @Setup
        public void setUp() {
            persistence = new RemoteNeo4jWithFermaPersistence(
                    System.getProperty("uri", "bolt://localhost"),
                    System.getProperty("login", "neo4j"),
                    System.getProperty("password", "root"), true);
        }

        @TearDown
        public void tearDown() {
            persistence.close();
        }
    }

    @State(Scope.Thread)
    public static class Neo4jWithFermaPersistenceResources {
        Neo4jWithFermaPersistenceManager persistenceManager;
        TransactionManager transactionManager;
        FermaRepositoryFactory repositoryFactory;
        List<String> flowIds;

        @Setup(Level.Iteration)
        public void setUp(SharedNeo4jWithFermaPersistence sharedNeo4jWithFermaPersistence) {
            persistenceManager = sharedNeo4jWithFermaPersistence.persistence.createPersistenceManager();
            transactionManager = persistenceManager.getTransactionManager();
            repositoryFactory = persistenceManager.getRepositoryFactory();

            flowIds = repositoryFactory.createFlowRepository().findAll().stream()
                    .map(Flow::getFlowId)
                    .collect(Collectors.toList());
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            persistenceManager.close();
        }
    }

    @State(Scope.Benchmark)
    public static class BenchmarkIndexes {
        final AtomicInteger iterationIndex = new AtomicInteger(0);
        final AtomicInteger benchmarkIndex = new AtomicInteger(0);

        @Setup(Level.Iteration)
        public void setUp() {
            iterationIndex.set(0);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    public void linearReadBenchmark(Neo4jWithFermaPersistenceResources persistence, Blackhole blackhole) {
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            IntStream.range(0, 9).mapToObj(i -> persistence.flowIds.get(i))
                    .forEach(flowId -> {
                        Flow flow = flowRepository.findById(flowId)
                                .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));
                        blackhole.consume(flow);
                        blackhole.consume(flow.getFlowId());
                    });
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    public void linearReadWithRelatedBenchmark(Neo4jWithFermaPersistenceResources persistence, Blackhole blackhole) {
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            IntStream.range(0, 9).mapToObj(i -> persistence.flowIds.get(i))
                    .forEach(flowId -> {
                        Flow flow = flowRepository.findById(flowId)
                                .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));
                        blackhole.consume(flow);
                        blackhole.consume(flow.getPaths());
                    });
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void createBenchmark(Neo4jWithFermaPersistenceResources persistence, BenchmarkIndexes threadIndex) {
        int benchmarkIndex = threadIndex.benchmarkIndex.getAndIncrement();
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            Flow flow = flowRepository.findById(persistence.flowIds.get(0))
                    .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));
            Flow cloned = FlowImpl.clone(flow).flowId(flow.getFlowId() + "_clone_" + benchmarkIndex).build();
            flow.getPaths().forEach(path -> cloned.addPaths(FlowPathImpl.clone(path).flow(cloned)
                    .pathId(new PathId(path.getPathId().toString() + "_clone_" + benchmarkIndex)).build()));
            flowRepository.create(cloned);
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void replaceRelatedBenchmark(Neo4jWithFermaPersistenceResources persistence, BenchmarkIndexes threadIndex) {
        int benchmarkIndex = threadIndex.benchmarkIndex.getAndIncrement();
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            Flow flow = flowRepository.findById(persistence.flowIds.get(0))
                    .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));

            ((FlowFrame) flow).setProperty("tx_lock", Instant.now().toString());

            Set<FlowPath> cloned = flow.getPaths().stream()
                    .peek(path -> ((FlowPathFrame) path).setProperty("tx_lock", Instant.now().toString()))
                    .map(path -> FlowPathImpl.clone(path)
                            .flow(flow).pathId(new PathId(path.getPathId().toString() + "_clone_" + benchmarkIndex)).build())
                    .collect(Collectors.toSet());

            flow.setForwardPath(null);
            flow.setReversePath(null);
            flow.setProtectedForwardPath(null);
            flow.setProtectedReversePath(null);
            flow.setPaths(cloned);
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void singleUpdateBenchmark(Neo4jWithFermaPersistenceResources persistence, BenchmarkIndexes threadIndex) {
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            Flow flow = flowRepository.findById(persistence.flowIds.get(threadIndex.iterationIndex.getAndIncrement()))
                    .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));

            ((FlowFrame) flow).setProperty("tx_lock", Instant.now().toString());

            flow.setTimeModify(Instant.now());
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    public void linearUpdateBenchmark(Neo4jWithFermaPersistenceResources persistence) {
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            IntStream.range(0, 9).mapToObj(i -> persistence.flowIds.get(i))
                    .forEach(flowId -> {
                        Flow flow = flowRepository.findById(flowId)
                                .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));

                        ((FlowFrame) flow).setProperty("tx_lock", Instant.now().toString());

                        flow.setTimeModify(Instant.now());
                    });
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    public void updateMultiplePropertiesBenchmark(Neo4jWithFermaPersistenceResources persistence) {
        persistence.transactionManager.doInTransaction(() -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            Flow flow = flowRepository.findById(persistence.flowIds.get(0))
                    .orElseThrow(() -> new IllegalStateException("Unable to find a flow"));

            ((FlowFrame) flow).setProperty("tx_lock", Instant.now().toString());

            flow.setBandwidth(flow.getBandwidth() + 1);
            flow.setDescription("another_" + flow.getDescription());
            flow.setPriority(Optional.ofNullable(flow.getPriority()).orElse(0) + 1);
            flow.setMaxLatency(Optional.ofNullable(flow.getMaxLatency()).orElse(0) + 1);
            flow.setGroupId("next_" + flow.getGroupId());
            flow.setTimeModify(Instant.now());
        });
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(iterations = 1)
    @Measurement(iterations = 10)
    @Threads(10)
    public void concurrentUpdateBenchmark(Neo4jWithFermaPersistenceResources persistence, BenchmarkIndexes threadIndex) {
        int iterationIndex = threadIndex.iterationIndex.getAndIncrement() * 5;
        RetryPolicy retry = new RetryPolicy()
                .retryOn(Exception.class)
                .withMaxRetries(5);
        persistence.transactionManager.doInTransaction(retry, () -> {
            FlowRepository flowRepository = persistence.repositoryFactory.createFlowRepository();
            List<Flow> flows = IntStream.range(iterationIndex, iterationIndex + 9)
                    .mapToObj(i -> flowRepository.findById(persistence.flowIds.get(i))
                            .orElseThrow(() -> new IllegalStateException("Unable to find a flow")))
                    .collect(Collectors.toList());
            for (Flow flow : flows) {
                ((FlowFrame) flow).setProperty("tx_lock", Instant.now().toString());
                flow.setTimeModify(Instant.now());
            }
        });
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(Neo4jWithFermaPersistenceBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
