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

package org.openkilda.wfm.topology.applications;

import org.openkilda.wfm.topology.AbstractTopologyConfig;

public interface AppsTopologyConfig extends AbstractTopologyConfig {

    default String getKafkaApplicationsTopic() {
        return getKafkaTopics().getAppsTopic();
    }

    default String getKafkaAppsNotificationTopic() {
        return getKafkaTopics().getAppsNotificationTopic();
    }

    default String getKafkaAppsStatsTopic() {
        return getKafkaTopics().getAppsStatsTopic();
    }

    default String getKafkaApplicationsNbTopic() {
        return getKafkaTopics().getTopoAppsNbTopic();
    }

    default String getKafkaApplicationsFlTopic() {
        return getKafkaTopics().getTopoAppsFlTopic();
    }

    default String getKafkaNorthboundTopic() {
        return getKafkaTopics().getNorthboundTopic();
    }

    default String getKafkaSpeakerTopic() {
        return getKafkaTopics().getSpeakerTopic();
    }

    default String getKafkaStatsTopic() {
        return getKafkaTopics().getStatsTopic();
    }
}
