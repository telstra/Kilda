/* Copyright 2020 Telstra Open Source
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

package org.openkilda.wfm.topology.flowhs;

import org.openkilda.wfm.topology.AbstractTopologyConfig;

import com.sabre.oss.conf4j.annotation.Default;
import com.sabre.oss.conf4j.annotation.Key;

public interface FlowHsTopologyConfig extends AbstractTopologyConfig {
    default String getKafkaFlowHsTopic() {
        return getKafkaTopics().getFlowHsTopic();
    }

    default String getKafkaSpeakerFlowTopic() {
        return getKafkaTopics().getSpeakerFlowHsTopic();
    }

    default String getKafkaNorthboundTopic() {
        return getKafkaTopics().getNorthboundTopic();
    }

    default String getKafkaRerouteTopic() {
        return getKafkaTopics().getTopoRerouteTopic();
    }

    default String getKafkaFlowSpeakerWorkerTopic() {
        return getKafkaTopics().getFlowHsSpeakerTopic();
    }

    default String getKafkaPingTopic() {
        return getKafkaTopics().getPingTopic();
    }

    default String getKafkaFlowHsServer42StormNotifyTopic() {
        return getKafkaTopics().getFlowHsServer42StormNotifyTopic();
    }


    @Key("flow.path.allocation.retries")
    @Default("10")
    int getPathAllocationRetriesLimit();

    @Key("flow.path.allocation.retry.delay")
    @Default("50")
    int getPathAllocationRetryDelay();

    @Key("flow.create.hub.timeout.seconds")
    @Default("180")
    int getCreateHubTimeoutSeconds();

    @Key("flow.create.speaker.timeout.seconds")
    @Default("10")
    int getCreateSpeakerTimeoutSeconds();

    @Key("flow.create.db.timeout.seconds")
    @Default("10")
    int getCreateDbTimeoutSeconds();

    @Key("flow.create.speaker.command.retries")
    @Default("3")
    int getCreateSpeakerCommandRetries();

    @Key("flow.create.hub.retries")
    @Default("3")
    int getCreateHubRetries();

    @Key("flow.update.hub.timeout.seconds")
    @Default("30")
    int getUpdateHubTimeoutSeconds();

    @Key("flow.swap.endpoints.hub.timeout.seconds")
    @Default("30")
    int getSwapEndpointsHubTimeoutSeconds();

    @Key("flow.update.speaker.timeout.seconds")
    @Default("10")
    int getUpdateSpeakerTimeoutSeconds();

    @Key("flow.update.speaker.command.retries")
    @Default("3")
    int getUpdateSpeakerCommandRetries();

    @Key("flow.reroute.hub.timeout.seconds")
    @Default("180")
    int getRerouteHubTimeoutSeconds();

    @Key("flow.reroute.speaker.timeout.seconds")
    @Default("10")
    int getRerouteSpeakerTimeoutSeconds();

    @Key("flow.reroute.speaker.command.retries")
    @Default("3")
    int getRerouteSpeakerCommandRetries();

    @Key("flow.delete.hub.timeout.seconds")
    @Default("30")
    int getDeleteHubTimeoutSeconds();

    @Key("flow.delete.speaker.timeout.seconds")
    @Default("10")
    int getDeleteSpeakerTimeoutSeconds();

    @Key("flow.delete.speaker.command.retries")
    @Default("3")
    int getDeleteSpeakerCommandRetries();

    @Key("flow.pathswap.hub.timeout.seconds")
    @Default("30")
    int getPathSwapHubTimeoutSeconds();

    @Key("flow.pathswap.speaker.timeout.seconds")
    @Default("10")
    int getPathSwapSpeakerTimeoutSeconds();

    @Key("flow.pathswap.speaker.command.retries")
    @Default("3")
    int getPathSwapSpeakerCommandRetries();
}
