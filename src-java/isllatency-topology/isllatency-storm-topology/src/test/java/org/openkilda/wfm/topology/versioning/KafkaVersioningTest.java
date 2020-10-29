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

package org.openkilda.wfm.topology.versioning;

import static java.lang.Thread.sleep;
import static org.openkilda.messaging.Utils.MESSAGE_VERSION_CONSUMER_PROPERTY;
import static org.openkilda.messaging.Utils.MESSAGE_VERSION_HEADER;

import org.openkilda.messaging.payload.SimpleMessage;
import org.openkilda.wfm.AbstractStormTest;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.TestKafkaConsumer;
import org.openkilda.wfm.topology.versioning.config.BaseVersionTopologyConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.storm.Config;
import org.apache.storm.generated.StormTopology;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

public class KafkaVersioningTest extends AbstractStormTest {

    private static final int POLL_TIMEOUT = 1000;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static BaseVersionTopologyConfig baseVersionTopologyConfig;
    private static TestKafkaConsumer mainSpoutConsumer;
    private static TestKafkaConsumer outSpoutConsumerVerOne;
    private static TestKafkaConsumer outSpoutConsumerVerTwo;

    @BeforeClass
    public static void setupOnce() throws Exception {
        AbstractStormTest.startZooKafkaAndStorm();

        Properties configOverlayOne = new Properties();
        configOverlayOne.setProperty("--name", "version_topology_1");
        LaunchEnvironment launchEnvironmentOne = makeLaunchEnvironment(configOverlayOne);

        Properties configOverlayTwo = new Properties();
        configOverlayTwo.setProperty("--name", "version_topology_2");
        LaunchEnvironment launchEnvironmentTwo = makeLaunchEnvironment(configOverlayTwo);

        VersioningTopology versioningTopologyOne = new VersioningTopology(launchEnvironmentOne, "1");
        VersioningTopology versioningTopologyTwo = new VersioningTopology(launchEnvironmentTwo, "2");
        baseVersionTopologyConfig = versioningTopologyOne.getConfig();

        StormTopology stormTopologyOne = versioningTopologyOne.createTopology();
        StormTopology stormTopologyTwo = versioningTopologyTwo.createTopology();
        Config config = stormConfig();

        cluster.submitTopology("topology_1", config, stormTopologyOne);
        cluster.submitTopology("topology_2", config, stormTopologyTwo);

        Properties propertiesOne = kafkaConsumerProperties(UUID.randomUUID().toString());
        propertiesOne.put(MESSAGE_VERSION_CONSUMER_PROPERTY, "1");

        mainSpoutConsumer = new TestKafkaConsumer(baseVersionTopologyConfig.getMainTopic(), propertiesOne);
        mainSpoutConsumer.start();
        outSpoutConsumerVerOne = new TestKafkaConsumer(baseVersionTopologyConfig.getOutTopic(), propertiesOne);
        outSpoutConsumerVerOne.start();

        Properties propertiesTwo = kafkaConsumerProperties(UUID.randomUUID().toString());
        propertiesTwo.put(MESSAGE_VERSION_CONSUMER_PROPERTY, "2");

        outSpoutConsumerVerTwo = new TestKafkaConsumer(baseVersionTopologyConfig.getOutTopic(), propertiesTwo);
        outSpoutConsumerVerTwo.start();

        sleep(10000);
    }

    @AfterClass
    public static void teardownOnce() throws Exception {
        mainSpoutConsumer.wakeup();
        mainSpoutConsumer.join();

        outSpoutConsumerVerOne.wakeup();
        outSpoutConsumerVerOne.join();
        outSpoutConsumerVerTwo.wakeup();
        outSpoutConsumerVerTwo.join();

        AbstractStormTest.stopZooKafkaAndStorm();
    }

    @Before
    public void setup() {
        mainSpoutConsumer.clear();
        outSpoutConsumerVerOne.clear();
        outSpoutConsumerVerTwo.clear();
    }

    @Test
    public void versionReceive() throws JsonProcessingException, InterruptedException {
        ObjectMapper mapper = new ObjectMapper();

        for (int i = 0; i < 10; i++) {
            String version = Integer.toString((i % 2) + 1);
            RecordHeader header = new RecordHeader(MESSAGE_VERSION_HEADER, version.getBytes());

            SimpleMessage message = new SimpleMessage("Message " + i + " version " + version);
            String json = mapper.writeValueAsString(message);
            kProducer.pushMessage(baseVersionTopologyConfig.getMainTopic(), json, Collections.singleton(header));
        }

        for (int i = 0; i < 15; i++) {
            SimpleMessage message = pollMessage(mainSpoutConsumer);
            if (message == null) {
                System.out.println("FIN pooling. Pooled " + i);
                break;
            }
            System.out.println("Poll message " + message.message);
        }
    }

    @Test
    public void versionSent() throws JsonProcessingException, InterruptedException {
        Thread.sleep(5000);

        for (int i = 0; i < 10; i++) {
            SimpleMessage message = pollMessage(outSpoutConsumerVerOne);
            if (message == null) {
                System.out.println("FIN pooling. Pooled " + i);
                break;
            }
            System.out.println("Poll message for version 1 " + message.message);
        }

        for (int i = 0; i < 10; i++) {
            SimpleMessage message = pollMessage(outSpoutConsumerVerTwo);
            if (message == null) {
                System.out.println("FIN pooling. Pooled " + i);
                break;
            }
            System.out.println("Poll message for version 2 " + message.message);
        }
    }


    private SimpleMessage pollMessage(TestKafkaConsumer consumer) {
        ConsumerRecord<String, String> record = null;
        try {
            record = consumer.pollMessage(POLL_TIMEOUT);
            if (record == null) {
                return null;
            }
            return objectMapper.readValue(record.value(), SimpleMessage.class);
        } catch (InterruptedException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
