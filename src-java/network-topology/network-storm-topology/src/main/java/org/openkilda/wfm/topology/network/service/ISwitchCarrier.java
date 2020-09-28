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

package org.openkilda.wfm.topology.network.service;

import org.openkilda.model.Isl;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.model.OnlineStatus;

public interface ISwitchCarrier {
    void setupPortHandler(Endpoint endpoint, Isl history);

    void removePortHandler(Endpoint endpoint);

    void setOnlineMode(Endpoint endpoint, OnlineStatus onlineStatus);

    void setPortLinkMode(Endpoint endpoint, LinkStatus linkStatus);

    void sendBfdPortAdd(Endpoint endpoint, int physicalPortNumber);

    void sendBfdPortDelete(Endpoint logicalEndpoint);

    void sendBfdLinkStatusUpdate(Endpoint logicalEndpoint, LinkStatus linkStatus);

    void sendBfdSwitchStatusUpdate(Endpoint endpoint, boolean isOnline);

    void sendSwitchSynchronizeRequest(String key, SwitchId switchId);

    void sendAffectedFlowRerouteRequest(SwitchId switchId);

    void sendSwitchStateChanged(SwitchId switchId, SwitchStatus status);
}
