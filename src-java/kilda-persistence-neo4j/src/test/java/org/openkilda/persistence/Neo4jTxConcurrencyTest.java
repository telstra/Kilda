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

package org.openkilda.persistence;

import static org.junit.Assert.assertNull;

import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.Isl;
import org.openkilda.model.IslConfig;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.impl.Neo4jFlowPathRepository;
import org.openkilda.persistence.repositories.impl.Neo4jFlowRepository;
import org.openkilda.persistence.repositories.impl.Neo4jIslRepository;
import org.openkilda.persistence.repositories.impl.Neo4jSwitchRepository;

import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Neo4jTxConcurrencyTest extends Neo4jBasedTest {
    static final String TEST_FLOW_1_ID = "test_flow_1";
    static final String TEST_FLOW_2_ID = "test_flow_2";
    static final String TEST_FLOW_3_ID = "test_flow_3";
    static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);
    static final SwitchId TEST_SWITCH_C_ID = new SwitchId(3);

    static SwitchRepository switchRepository;
    static FlowRepository flowRepository;
    static FlowPathRepository flowPathRepository;
    static IslRepository islRepository;

    @BeforeClass
    public static void setUp() {
        switchRepository = new Neo4jSwitchRepository(neo4jSessionFactory, txManager);
        flowRepository = new Neo4jFlowRepository(neo4jSessionFactory, txManager);
        flowPathRepository = new Neo4jFlowPathRepository(neo4jSessionFactory, txManager);
        islRepository = new Neo4jIslRepository(neo4jSessionFactory, txManager, IslConfig.builder().build());
    }

    @Test
    public void shouldHandleConcurrentRequestsOnSameEntity() throws ExecutionException, InterruptedException {
        // given
        Switch srcSwitch = Switch.builder().switchId(TEST_SWITCH_A_ID).build();
        switchRepository.createOrUpdate(srcSwitch);
        Switch dstSwitch = Switch.builder().switchId(TEST_SWITCH_B_ID).build();
        switchRepository.createOrUpdate(dstSwitch);

        Flow flow = Flow.builder().flowId(TEST_FLOW_1_ID)
                .srcSwitch(srcSwitch).srcPort(1).destSwitch(dstSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow);

        // when
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Exception>> results = IntStream.range(1, 50).<Callable<Exception>>mapToObj(index -> () -> {
            Instant timestamp = Instant.now().plus(index, ChronoUnit.DAYS);

            try {
                txManager.doInTransaction(() -> {
                    Flow flowToUpdate = flowRepository.findById(TEST_FLOW_1_ID).get();
                    flowToUpdate.setTimeModify(timestamp);
                    txManager.getSession().save(flowToUpdate);

                    Flow updated = flowRepository.findById(TEST_FLOW_1_ID).get();
                    if (!updated.getTimeModify().equals(timestamp)) {
                        throw new RuntimeException("Failed to update timeModify: " + updated);
                    }
                });
            } catch (Exception ex) {
                return ex;
            }

            return null;
        }).map(executor::submit).collect(Collectors.toList());

        // then
        for (Future<Exception> future : results) {
            assertNull(future.get());
        }
    }

    @Test
    public void shouldHandleConcurrentRequestsOnIntersectEntities() throws ExecutionException, InterruptedException {
        // given
        Switch srcSwitch = Switch.builder().switchId(TEST_SWITCH_A_ID).build();
        switchRepository.createOrUpdate(srcSwitch);
        Switch dstSwitch = Switch.builder().switchId(TEST_SWITCH_B_ID).build();
        switchRepository.createOrUpdate(dstSwitch);
        Switch dst2Switch = Switch.builder().switchId(TEST_SWITCH_C_ID).build();
        switchRepository.createOrUpdate(dst2Switch);

        Flow flow1 = Flow.builder().flowId(TEST_FLOW_1_ID)
                .srcSwitch(srcSwitch).srcPort(1).destSwitch(dstSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow1);
        Flow flow2 = Flow.builder().flowId(TEST_FLOW_2_ID)
                .srcSwitch(dstSwitch).srcPort(1).destSwitch(dst2Switch).destPort(2).build();
        flowRepository.createOrUpdate(flow2);
        Flow flow3 = Flow.builder().flowId(TEST_FLOW_3_ID)
                .srcSwitch(dst2Switch).srcPort(1).destSwitch(srcSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow3);

        // when
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Exception>> results = IntStream.range(1, 50).<Callable<Exception>>mapToObj(index -> () -> {
            String flowId = Arrays.asList(TEST_FLOW_1_ID, TEST_FLOW_2_ID, TEST_FLOW_3_ID).get(index % 3);
            Instant timestamp = Instant.now().plus(index, ChronoUnit.DAYS);

            try {
                txManager.doInTransaction(() -> {
                    Flow flowToUpdate = flowRepository.findById(flowId).get();
                    flowToUpdate.setTimeModify(timestamp);
                    txManager.getSession().save(flowToUpdate);

                    Flow updated = flowRepository.findById(flowId).get();
                    if (!updated.getTimeModify().equals(timestamp)) {
                        throw new RuntimeException("Failed to update timeModify: " + updated);
                    }
                });
            } catch (Exception ex) {
                return ex;
            }

            return null;
        }).map(executor::submit).collect(Collectors.toList());

        // then
        for (Future<Exception> future : results) {
            assertNull(future.get());
        }
    }

    @Test
    public void shouldHandleConcurrentRequestsOnPairedEntities() throws ExecutionException, InterruptedException {
        // given
        Switch srcSwitch = Switch.builder().switchId(TEST_SWITCH_A_ID).build();
        switchRepository.createOrUpdate(srcSwitch);
        Switch dstSwitch = Switch.builder().switchId(TEST_SWITCH_B_ID).build();
        switchRepository.createOrUpdate(dstSwitch);
        Switch dst2Switch = Switch.builder().switchId(TEST_SWITCH_C_ID).build();
        switchRepository.createOrUpdate(dst2Switch);

        Flow flow1 = Flow.builder().flowId(TEST_FLOW_1_ID)
                .srcSwitch(srcSwitch).srcPort(1).destSwitch(dstSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow1);
        Flow flow2 = Flow.builder().flowId(TEST_FLOW_2_ID)
                .srcSwitch(dstSwitch).srcPort(1).destSwitch(dst2Switch).destPort(2).build();
        flowRepository.createOrUpdate(flow2);
        Flow flow3 = Flow.builder().flowId(TEST_FLOW_3_ID)
                .srcSwitch(dst2Switch).srcPort(1).destSwitch(srcSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow3);

        // when
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Exception>> results = IntStream.range(1, 50).<Callable<Exception>>mapToObj(index -> () -> {
            String flowId = Arrays.asList(TEST_FLOW_1_ID, TEST_FLOW_2_ID, TEST_FLOW_3_ID).get(index % 3);
            Instant timestamp = Instant.now().plus(index, ChronoUnit.DAYS);

            try {
                txManager.doInTransaction(() -> {
                    Flow flowToUpdate = flowRepository.findById(flowId).get();
                    flowToUpdate.setTimeModify(timestamp);
                    txManager.getSession().save(flowToUpdate);

                    Flow updated = flowRepository.findById(flowId).get();
                    if (!updated.getTimeModify().equals(timestamp)) {
                        throw new RuntimeException("Failed to update timeModify: " + updated);
                    }
                });
            } catch (Exception ex) {
                return ex;
            }

            return null;
        }).map(executor::submit).collect(Collectors.toList());

        // then
        for (Future<Exception> future : results) {
            assertNull(future.get());
        }
    }

    @Test
    public void shouldHandleConcurrentRequestsOnAdjacentEntities() throws ExecutionException, InterruptedException {
        // given
        Switch srcSwitch = Switch.builder().switchId(TEST_SWITCH_A_ID).build();
        switchRepository.createOrUpdate(srcSwitch);
        Switch dstSwitch = Switch.builder().switchId(TEST_SWITCH_B_ID).build();
        switchRepository.createOrUpdate(dstSwitch);

        Flow flow1 = Flow.builder().flowId(TEST_FLOW_1_ID)
                .srcSwitch(srcSwitch).srcPort(1).destSwitch(dstSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow1);
        Flow flow2 = Flow.builder().flowId(TEST_FLOW_2_ID)
                .srcSwitch(dstSwitch).srcPort(1).destSwitch(srcSwitch).destPort(2).build();
        flowRepository.createOrUpdate(flow2);

        Flow flow3 = Flow.builder().flowId(TEST_FLOW_3_ID)
                .srcSwitch(srcSwitch).srcPort(1).destSwitch(dstSwitch).destPort(2).build();
        FlowPath flowPath1 = FlowPath.builder().pathId(new PathId(UUID.randomUUID().toString()))
                .flow(flow3)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(3L, FlowPathDirection.FORWARD))
                .srcSwitch(srcSwitch).destSwitch(dstSwitch).build();
        flow3.setForwardPath(flowPath1);
        FlowPath flowPath2 = FlowPath.builder().pathId(new PathId(UUID.randomUUID().toString()))
                .flow(flow3)
                .cookie(FlowSegmentCookieSchema.INSTANCE.make(3L, FlowPathDirection.REVERSE))
                .srcSwitch(dstSwitch).destSwitch(srcSwitch).build();
        flow3.setReversePath(flowPath2);
        flowRepository.createOrUpdate(flow3);

        // when
        Random random = new Random();
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Exception>> results = IntStream.range(1, 50).<Callable<Exception>>mapToObj(index -> () -> {
            try {
                txManager.doInTransaction(() -> {
                    if (index % 3 == 0) {
                        String flowId = Arrays.asList(TEST_FLOW_1_ID, TEST_FLOW_2_ID).get(index % 2);
                        Flow flowToUpdate = flowRepository.findById(flowId).get();
                        Instant timestamp = Instant.now().plus(index, ChronoUnit.DAYS);
                        flowToUpdate.setTimeModify(timestamp);
                        txManager.getSession().save(flowToUpdate);

                        Flow updated = flowRepository.findById(flowId).get();
                        if (!updated.getTimeModify().equals(timestamp)) {
                            throw new RuntimeException("Failed to update timeModify: " + updated);
                        }

                    } else {
                        Cookie cookie  = FlowSegmentCookieSchema.INSTANCE.make(
                                3, index % 2 == 0 ? FlowPathDirection.FORWARD : FlowPathDirection.REVERSE);

                        FlowPath pathToUpdate =
                                flowPathRepository.findByFlowIdAndCookie(TEST_FLOW_3_ID, cookie).get();
                        long latency = Math.abs(random.nextLong());
                        pathToUpdate.setLatency(latency);
                        flowPathRepository.createOrUpdate(pathToUpdate);

                        FlowPath updated = flowPathRepository.findByFlowIdAndCookie(TEST_FLOW_3_ID, cookie).get();
                        if (updated.getLatency() != latency) {
                            throw new RuntimeException("Failed to update latency: " + updated);
                        }
                    }
                });
            } catch (Exception ex) {
                return ex;
            }

            return null;
        }).map(executor::submit).collect(Collectors.toList());

        // then
        for (Future<Exception> future : results) {
            assertNull(future.get());
        }
    }

    @Test
    public void shouldHandleConcurrentCreationOnAdjacentEntities() throws ExecutionException, InterruptedException {
        // given
        Switch srcSwitch = Switch.builder().switchId(TEST_SWITCH_A_ID).build();
        switchRepository.createOrUpdate(srcSwitch);
        Switch dstSwitch = Switch.builder().switchId(TEST_SWITCH_B_ID).build();
        switchRepository.createOrUpdate(dstSwitch);

        // when
        Random random = new Random();
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<Exception>> results = IntStream.range(1, 50).<Callable<Exception>>mapToObj(index -> () -> {
            int srcPort = index * 2;
            int destPort = srcPort + 1;

            try {
                txManager.doInTransaction(() -> {
                    Isl isl = Isl.builder().srcSwitch(srcSwitch).srcPort(srcPort)
                            .destSwitch(dstSwitch).destPort(destPort).build();
                    islRepository.createOrUpdate(isl);
                });
            } catch (Exception ex) {
                return ex;
            }

            int latency = Math.abs(random.nextInt());

            try {
                txManager.doInTransaction(() -> {
                    Isl islToUpdate = islRepository.findByEndpoints(srcSwitch.getSwitchId(), srcPort,
                            dstSwitch.getSwitchId(), destPort).get();
                    islToUpdate.setLatency(latency);
                    islRepository.createOrUpdate(islToUpdate);
                });
            } catch (Exception ex) {
                return ex;
            }

            try {
                Isl updated = islRepository.findByEndpoints(srcSwitch.getSwitchId(), srcPort,
                        dstSwitch.getSwitchId(), destPort).get();
                if (updated.getLatency() != latency) {
                    throw new RuntimeException("Failed to update latency: " + updated);
                }
                return null;
            } catch (Exception ex) {
                return ex;
            }
        }).map(executor::submit).collect(Collectors.toList());

        // then
        for (Future<Exception> future : results) {
            assertNull(future.get());
        }
    }
}

