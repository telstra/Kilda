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

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.messaging.Message;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

public interface FlowGenericCarrier {
    /**
     * Sends response to northbound component.
     */
    void sendNorthboundResponse(Message message);

    /**
     * Sends commands to speaker.
     * @param command command to be executed.
     */
    void sendSpeakerRequest(FlowSegmentRequest command);

    /**
     * Sends Db command to speaker.
     * @param command command to be executed.
     */
    void sendSpeakerDbCommand(DbCommand command);

    /**
     * Sends main events to history bolt.
     */
    void sendHistoryUpdate(FlowHistoryHolder historyHolder);

    /**
     * Sends update on periodic ping status for the flow.
     * @param flowId flow id
     * @param enabled flag
     */
    void sendPeriodicPingNotification(String flowId, boolean enabled);

    /**
     * Sends ActivateFlowMonitoringInfoData to server42-control topology.
     * @param flow requested flow
     */
    default void sendActivateFlowMonitoring(RequestedFlow flow) {}

    /**
     * Sends DeactivateFlowMonitoringInfoData to server42-control topology.
     * @param flow requested flow
     */
    default void sendDeactivateFlowMonitoring(String flow, SwitchId srcSwitchId, SwitchId dstSwitchId) {}
}
