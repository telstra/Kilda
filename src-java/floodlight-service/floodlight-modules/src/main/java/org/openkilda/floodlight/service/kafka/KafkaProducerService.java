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

package org.openkilda.floodlight.service.kafka;

import static org.openkilda.floodlight.service.zookeeper.ZooKeeperService.ZK_COMPONENT_NAME;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.floodlight.service.zookeeper.ZooKeeperEventObserver;
import org.openkilda.floodlight.service.zookeeper.ZooKeeperService;
import org.openkilda.messaging.AbstractMessage;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.IslInfoData;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class KafkaProducerService implements IKafkaProducerService, ZooKeeperEventObserver {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);
    private static final Logger discoLogger = LoggerFactory.getLogger(
            String.format("%s.DISCO", KafkaProducerService.class.getName()));

    private int failedSendMessageCounter;
    private Producer<String, String> producer;
    private final ObjectMapper jsonObjectMapper = new ObjectMapper();

    private ZooKeeperService zkService;
    private final AtomicBoolean active = new AtomicBoolean(false);

    @Override
    public void setup(FloodlightModuleContext moduleContext) {
        producer = moduleContext.getServiceImpl(KafkaUtilityService.class).makeProducer();
        zkService = moduleContext.getServiceImpl(ZooKeeperService.class);
        zkService.subscribe(this);
        zkService.initZookeeper();
    }

    @Override
    public void sendMessageAndTrack(String topic, Message message) {
        produce(encode(topic, message), new SendStatusCallback(this, topic, message));
    }

    @Override
    public void sendMessageAndTrack(String topic, String key, Message message) {
        produce(encode(topic, key, message), new SendStatusCallback(this, topic, message));
    }

    @Override
    public void sendMessageAndTrack(String topic, String key, AbstractMessage message) {
        produce(encode(topic, key, message), new SendStatusCallback(this, topic,
                message.getMessageContext().getCorrelationId()));
    }

    @Override
    public void sendMessageAndTrackWithZk(String topic, Message message) {
        if (active.get()) {
            produce(encode(topic, message), new SendStatusCallback(this, topic, message));
        } else {
            logger.debug("ZooKeeper signal is not START");
        }
    }

    @Override
    public void sendMessageAndTrackWithZk(String topic, String key, Message message) {
        if (active.get()) {
            produce(encode(topic, key, message), new SendStatusCallback(this, topic, message));
        } else {
            logger.debug("ZooKeeper signal is not START");
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
            this.zkService.getZooKeeperStateTracker().processLifecycleEvent(event);
        } else if (Signal.SHUTDOWN.equals(event.getSignal())) {
            if (!active.get()) {
                logger.info("Component is already in inactive state, skipping SHUTDOWN signal");
                return;
            }
            active.set(false);
            this.zkService.getZooKeeperStateTracker().processLifecycleEvent(event);
        } else {
            logger.error("Unsupported signal received: {}", event.getSignal());
        }
    }

    /**
     * Push message into kafka-broker and do not control operation result.
     *
     * <p>Caller can check operation result by himself using returned {@link SendStatusCallback} object.
     */
    public SendStatus sendMessage(String topic, Message message) {
        SendStatus sendStatus = produce(encode(topic, message), null);
        return sendStatus;
    }

    protected SendStatus produce(ProducerRecord<String, String> record, Callback callback) {
        logger.debug("Send kafka message: {} <== key:{} value:{}", record.topic(), record.key(), record.value());
        return new SendStatus(producer.send(record, callback));
    }

    private ProducerRecord<String, String> encode(String topic, Message payload) {
        return encode(topic, null, payload);
    }

    private ProducerRecord<String, String> encode(String topic, String key, Object payload) {
        return new ProducerRecord<>(topic, key, encodeValue(payload));
    }

    private String encodeValue(Object message) {
        String encoded;
        try {
            encoded = jsonObjectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(String.format("Can not serialize message: %s", e.toString()), e);
        }

        return encoded;
    }

    /**
     * get failed sent messages count since last run.
     */
    public int getFailedSendMessageCounter() {
        return failedSendMessageCounter;
    }

    private static class SendStatusCallback implements Callback {
        private final KafkaProducerService service;
        private final String topic;
        private final String correlationId;
        private final Message message;

        SendStatusCallback(KafkaProducerService service, String topic, String correlationId) {
            this.service = service;
            this.topic = topic;
            this.correlationId = correlationId;
            this.message = null;
        }

        SendStatusCallback(KafkaProducerService service, String topic, Message message) {
            this.service = service;
            this.topic = topic;
            this.correlationId = message.getCorrelationId();
            this.message = message;
        }

        @Override
        public void onCompletion(RecordMetadata metadata, Exception exception) {
            String error = exception == null ? null : exception.toString();
            logger.debug("{}: {}, {}", this.getClass().getCanonicalName(), metadata, error);

            if (exception == null) {
                service.failedSendMessageCounter = 0;
                if (message instanceof InfoMessage) {
                    InfoData infoData = ((InfoMessage) message).getData();
                    if (infoData instanceof IslInfoData) {
                        IslInfoData isl = (IslInfoData) infoData;
                        discoLogger.debug("Isl discovery response was successfully sent: {}", isl.getPacketId());
                    }
                }
                return;
            }
            service.failedSendMessageCounter++;
            logger.error(
                    "Fail to send message(correlationId=\"{}\") in kafka topic={}: {}",
                    correlationId, topic, exception);
        }
    }
}
