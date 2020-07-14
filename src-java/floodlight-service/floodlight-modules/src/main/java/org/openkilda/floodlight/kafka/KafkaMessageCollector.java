/* Copyright 2017 Telstra Open Source
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

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.config.provider.FloodlightModuleConfigurationProvider;
import org.openkilda.floodlight.pathverification.IPathVerificationService;
import org.openkilda.floodlight.service.CommandProcessorService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaConsumerSetup;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.service.session.SessionService;
import org.openkilda.floodlight.statistics.IStatisticsService;
import org.openkilda.floodlight.switchmanager.ISwitchManager;
import org.openkilda.floodlight.switchmanager.SwitchTrackingService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class KafkaMessageCollector implements IFloodlightModule {
    private static final Logger logger = LoggerFactory.getLogger(KafkaMessageCollector.class);

    /**
     * IFloodLightModule Methods.
     */
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableList.of();
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.of();
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return ImmutableList.of(
                IPathVerificationService.class,
                ISwitchManager.class,
                KafkaUtilityService.class,
                IKafkaProducerService.class,
                CommandProcessorService.class,
                SwitchTrackingService.class,
                SessionService.class,
                IStatisticsService.class);
    }

    @Override
    public void init(FloodlightModuleContext moduleContext) {
        // there is nothing to initialize here
    }

    @Override
    public void startUp(FloodlightModuleContext moduleContext) {
        logger.info("Starting {}", this.getClass().getCanonicalName());

        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(moduleContext, this);
        KafkaMessageCollectorConfig consumerConfig = provider.getConfiguration(KafkaMessageCollectorConfig.class);

        KafkaChannel kafkaChannel = moduleContext.getServiceImpl(KafkaUtilityService.class).getKafkaChannel();
        logger.info("region: {}", kafkaChannel.getRegion());
        ConsumerLauncher launcher = new ConsumerLauncher(moduleContext, consumerConfig);
        launchTopics(consumerConfig, kafkaChannel, launcher);
    }

    protected void launchTopics(KafkaMessageCollectorConfig consumerConfig,
                                KafkaChannel kafkaChannel,
                                ConsumerLauncher launcher) {
        ExecutorService generalExecutor = buildExecutorWithNoQueue(consumerConfig.getGeneralExecutorCount());
        logger.info("Kafka Consumer: general executor threads = {}", consumerConfig.getGeneralExecutorCount());
        launcher.launch(generalExecutor, new KafkaConsumerSetup(kafkaChannel.getSpeakerTopic()));
        launcher.launch(generalExecutor, new KafkaConsumerSetup(kafkaChannel.getSpeakerFlowTopic()));
        launcher.launch(generalExecutor, new KafkaConsumerSetup(kafkaChannel.getSpeakerFlowPingTopic()));

        ExecutorService discoCommandExecutor = buildExecutorWithNoQueue(consumerConfig.getDiscoExecutorCount());
        logger.info("Kafka Consumer: disco executor threads = {}", consumerConfig.getDiscoExecutorCount());

        KafkaConsumerSetup kafkaSetup = new KafkaConsumerSetup(kafkaChannel.getSpeakerDiscoTopic());
        kafkaSetup.offsetResetStrategy(OffsetResetStrategy.LATEST);
        launcher.launch(discoCommandExecutor, kafkaSetup);
    }

    protected ExecutorService buildExecutorWithNoQueue(int executorCount) {
        // A thread pool of fixed sized and no work queue.
        return new ThreadPoolExecutor(executorCount, executorCount, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new RetryableExecutionHandler());
    }

    protected static class ConsumerLauncher {
        private final FloodlightModuleContext moduleContext;
        private final KafkaMessageCollectorConfig consumerConfig;

        private final RecordHandler.Factory handlerFactory;

        ConsumerLauncher(FloodlightModuleContext moduleContext, KafkaMessageCollectorConfig consumerConfig) {
            this.moduleContext = moduleContext;
            this.consumerConfig = consumerConfig;

            ConsumerContext context = new ConsumerContext(moduleContext);
            this.handlerFactory = new RecordHandler.Factory(context);
        }

        protected void launch(ExecutorService handlerExecutor, KafkaConsumerSetup kafkaSetup) {
            Consumer consumer = new Consumer(moduleContext, handlerExecutor, kafkaSetup, handlerFactory,
                    consumerConfig.getAutoCommitInterval());
            Executors.newSingleThreadScheduledExecutor()
                    .scheduleWithFixedDelay(consumer, 0, 1, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Handler of rejected messages by ThreadPoolExecutor, in case of reject this handler will wait
     * until one of executors becomes available.
     */
    private static class RetryableExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                try {
                    executor.getQueue().put(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Couldn't retry to process message", e);
                }
            }
        }
    }
}
