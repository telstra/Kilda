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

package org.openkilda.floodlight.kafka;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.openkilda.floodlight.service.zookeeper.ZooKeeperService.ZK_COMPONENT_NAME;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.floodlight.kafka.RecordHandler.Factory;
import org.openkilda.floodlight.service.kafka.KafkaConsumerSetup;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.service.zookeeper.ZooKeeperEventObserver;
import org.openkilda.floodlight.service.zookeeper.ZooKeeperService;
import org.openkilda.floodlight.switchmanager.ISwitchManager;

import com.google.common.annotations.VisibleForTesting;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class Consumer implements Runnable, ZooKeeperEventObserver {
    private static final Logger logger = LoggerFactory.getLogger(Consumer.class);

    private final ExecutorService handlersPool;
    private final RecordHandler.Factory handlerFactory;
    private final KafkaConsumerSetup kafkaSetup;
    private final long commitInterval;
    private final long pollTimeout;

    private final KafkaUtilityService kafkaUtilityService;
    private final ISwitchManager switchManager; // HACK alert.. adding to facilitate safeSwitchTick()

    private final Set<Future<?>> tasks = new HashSet<>();

    private final ZooKeeperService zkService;
    private LifecycleEvent deferedShutdownEvent;
    private final AtomicBoolean active = new AtomicBoolean(false);

    public Consumer(FloodlightModuleContext moduleContext, ExecutorService handlersPool,
                    KafkaConsumerSetup kafkaSetup, Factory handlerFactory,
                    long commitInterval, long pollTimeout) {
        this.handlersPool = requireNonNull(handlersPool);
        this.handlerFactory = requireNonNull(handlerFactory);
        this.kafkaSetup = kafkaSetup;

        checkArgument(commitInterval > 0, "commitInterval must be positive");
        this.commitInterval = commitInterval;
        checkArgument(pollTimeout > 0, "pollTimeout must be positive");
        this.pollTimeout = pollTimeout;

        kafkaUtilityService = moduleContext.getServiceImpl(KafkaUtilityService.class);
        switchManager = moduleContext.getServiceImpl(ISwitchManager.class);

        zkService = moduleContext.getServiceImpl(ZooKeeperService.class);
        zkService.subscribe(this);
        zkService.initZookeeper();
    }

    @Override
    public void run() {

        while (!Thread.currentThread().isInterrupted()) {
            /*
             * Ensure we try to keep processing messages. It is possible that the consumer needs
             * to be re-created, either due to internal error, or if it fails to poll within the
             * max.poll.interval.ms seconds.
             *
             * From the Kafka source code, here are the default values for the following fields:
             *  - max.poll.interval.ms = 300000 (ie 300 seconds)
             *  - max.poll.records = 500 (must be able to process about 2 records per second
             */

            try (org.apache.kafka.clients.consumer.Consumer<String, String> consumer =
                         kafkaUtilityService.makeConsumer(kafkaSetup)) {
                logger.info("Kafka consumer: start. Topics: {}", kafkaSetup.getTopics());

                KafkaOffsetRegistry offsetRegistry = new KafkaOffsetRegistry(consumer, commitInterval);

                while (true) {
                    try {
                        if (!tasks.isEmpty()) {
                            Set<Future<?>> toRemove = new HashSet<>();
                            for (Future<?> task : tasks) {
                                if (task.get() == null) {
                                    toRemove.add(task);
                                }
                            }
                            tasks.removeAll(toRemove);
                        } else if (deferedShutdownEvent != null && !active.get()) {
                            zkService.getZooKeeperStateTracker().processLifecycleEvent(deferedShutdownEvent);
                            deferedShutdownEvent = null;
                        }

                        ConsumerRecords<String, String> batch = consumer.poll(pollTimeout);
                        if (!batch.isEmpty()) {
                            handle(batch, offsetRegistry);
                        }
                        tick();
                    } finally {
                        // force to commit after each completed batch or in a case of an exception / error.
                        offsetRegistry.commitOffsets();
                    }

                    switchManager.safeModeTick(); // HACK alert .. should go in its own timer loop
                }
            } catch (InterruptException ex) {
                // Gracefully finish loop on thread interruption.
                logger.warn("Kafka consumer loop has been interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Just log the exception, and start processing again with a new consumer.
                logger.error(format("Exception received during main kafka consumer loop: %s", e.getMessage()), e);
            }
        }
    }

    @Override
    public void handleLifecycleEvent(LifecycleEvent event) {
        logger.info("Component {} with id {} got lifecycle event {}", ZK_COMPONENT_NAME, zkService.getRegion(), event);
        if (Signal.START.equals(event.getSignal())) {
            if (active.get()) {
                logger.info("Component is already in active state, skipping START signal");
                return;
            }
            active.set(true);
            zkService.getZooKeeperStateTracker().processLifecycleEvent(event);
        } else if (Signal.SHUTDOWN.equals(event.getSignal())) {
            if (!active.get()) {
                logger.info("Component is already in inactive state, skipping SHUTDOWN signal");
                return;
            }
            deferedShutdownEvent = event;
            active.set(false);
        } else {
            logger.error("Unsupported signal received: {}", event.getSignal());
        }
    }

    private void handle(ConsumerRecords<String, String> recordsBatch, KafkaOffsetRegistry offsetRegistry) {
        logger.debug("Received records batch contain {} messages", recordsBatch.count());
        for (ConsumerRecord<String, String> record : recordsBatch) {
            handle(record);
            offsetRegistry.addAndCommit(record);
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        if (!active.get()) {
            return;
        }
        logger.trace("received message: {} - key:{}, value:{}", record.offset(), record.key(), record.value());
        tasks.add(handlersPool.submit(handlerFactory.produce(record)));
    }

    private void tick() {
        handlersPool.execute(new TickHandler(handlerFactory.getContext()));
    }

    /**
     * Holds offsets for Kafka partitions and performs sync commits of them.
     * <p/>
     * Note: the implementation is not thread-safe.
     */
    @VisibleForTesting
    static class KafkaOffsetRegistry {
        private final org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
        private final long autoCommitInterval;

        private final Map<TopicPartition, Long> partitionToUncommittedOffset = new HashMap<>();
        private long lastCommitTime;

        KafkaOffsetRegistry(org.apache.kafka.clients.consumer.Consumer<String, String> consumer,
                            long autoCommitInterval) {
            this.consumer = consumer;
            this.autoCommitInterval = autoCommitInterval;

            lastCommitTime = System.currentTimeMillis();
        }

        /**
         * Add the record's offset to the registry and perform a commit
         * if more than autoCommitInterval ms passed since the last commit.
         */
        void addAndCommit(ConsumerRecord<String, String> record) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());

            Long previousOffset = partitionToUncommittedOffset.get(partition);
            if (previousOffset != null && previousOffset > record.offset()) {
                throw new IllegalArgumentException(
                        format("The record has offset %d which less than the previously added %d.",
                                record.offset(), previousOffset));
            }

            partitionToUncommittedOffset.put(partition, record.offset());

            // commit offsets of processed messages
            if ((System.currentTimeMillis() - lastCommitTime) >= autoCommitInterval) {
                commitOffsets();
            }
        }

        /**
         * Commits the offsets added since the last commit.
         */
        void commitOffsets() {
            if (!partitionToUncommittedOffset.isEmpty()) {
                Map<TopicPartition, OffsetAndMetadata> partitionToMetadata = new HashMap<>();
                for (Entry<TopicPartition, Long> e : partitionToUncommittedOffset.entrySet()) {
                    partitionToMetadata.put(e.getKey(), new OffsetAndMetadata(e.getValue() + 1));
                }

                consumer.commitSync(partitionToMetadata);

                partitionToUncommittedOffset.clear();
            }

            lastCommitTime = System.currentTimeMillis();
        }
    }
}
