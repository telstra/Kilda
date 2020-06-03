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

package org.openkilda.floodlight.command;

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public abstract class SpeakerCommandRemoteReport extends SpeakerCommandReport {
    @Setter
    @Getter
    private Duration executionTime;

    public SpeakerCommandRemoteReport(SpeakerCommand<?> command, Exception error) {
        super(command, error);
    }

    public abstract void reply(
            KafkaChannel kafkaChannel, IKafkaProducerService kafkaProducerService, String requestKey);
}
