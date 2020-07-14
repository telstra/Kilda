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

import com.sabre.oss.conf4j.annotation.Configuration;
import com.sabre.oss.conf4j.annotation.Default;
import com.sabre.oss.conf4j.annotation.Key;

import javax.validation.constraints.Min;

/**
 * WARNING! Do not use '.' in option's keys. FL will not collect such option from config.
 */
@Configuration
public interface KafkaMessageCollectorConfig {
    @Key("consumer-executors")
    @Default("10")
    @Min(1)
    int getGeneralExecutorCount();

    @Key("consumer-disco-executors")
    @Default("10")
    @Min(1)
    int getDiscoExecutorCount();

    @Key("consumer-auto-commit-interval")
    @Default("1000")
    @Min(1)
    long getAutoCommitInterval();
}
