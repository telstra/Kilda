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

package org.openkilda.wfm.topology;

import org.openkilda.config.KafkaTopicsConfig;
import org.openkilda.wfm.config.SecondsToMilisConverter;

import com.sabre.oss.conf4j.annotation.AbstractConfiguration;
import com.sabre.oss.conf4j.annotation.Converter;
import com.sabre.oss.conf4j.annotation.Default;
import com.sabre.oss.conf4j.annotation.IgnoreKey;
import com.sabre.oss.conf4j.annotation.Key;

@AbstractConfiguration
public interface AbstractTopologyConfig {
    @Key("cli.local")
    boolean getUseLocalCluster();

    @Key("local.execution.time")
    @Converter(SecondsToMilisConverter.class)
    int getLocalExecutionTime();

    @Key("flow.hs.parallelism")
    @Default("4")
    int getFlowHsParallelism();

    @Key("parallelism")
    int getParallelism();

    @Key("parallelism.new")
    int getNewParallelism();

    @Key("isl.latency.parallelism")
    int getIslLatencyParallelism();

    @Key("workers")
    int getWorkers();

    @Key("disruptor.wait.timeout")
    Integer getDisruptorWaitTimeout();

    @Key("disruptor.batch.timeout")
    Integer getDisruptorBatchTimeout();

    @Key("spout.wait.sleep.time")
    Integer getSpoutWaitSleepTime();

    @IgnoreKey
    KafkaTopicsConfig getKafkaTopics();

    default String getKafkaCtrlTopic() {
        return getKafkaTopics().getCtrlTopic();
    }

    @Key("blue.green.mode")
    @Default("blue")
    String getBlueGreenMode();
}
