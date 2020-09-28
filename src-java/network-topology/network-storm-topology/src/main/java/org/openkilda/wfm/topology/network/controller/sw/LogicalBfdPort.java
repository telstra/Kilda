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

package org.openkilda.wfm.topology.network.controller.sw;

import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.model.OnlineStatus;
import org.openkilda.wfm.topology.network.service.ISwitchCarrier;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LogicalBfdPort extends AbstractPort {
    private final int physicalPortNumber;

    LogicalBfdPort(Endpoint logicalEndpoint, int physicalPortNumber) {
        super(logicalEndpoint);
        this.physicalPortNumber = physicalPortNumber;
    }

    @Override
    public void portAdd(ISwitchCarrier carrier) {
        carrier.sendBfdPortAdd(getEndpoint(), physicalPortNumber);
    }

    @Override
    public void portDel(ISwitchCarrier carrier) {
        carrier.sendBfdPortDelete(getEndpoint());
    }

    @Override
    public void updateOnlineStatus(ISwitchCarrier carrier, OnlineStatus onlineStatus) {
        carrier.sendBfdSwitchStatusUpdate(getEndpoint(), onlineStatus.isOnline());
    }

    @Override
    public void updatePortLinkMode(ISwitchCarrier carrier) {
        carrier.sendBfdLinkStatusUpdate(getEndpoint(), getLinkStatus());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String makeDashboardPortLabel(NetworkTopologyDashboardLogger dashboardLogger) {
        return dashboardLogger.makePortLabel(this);
    }
}
