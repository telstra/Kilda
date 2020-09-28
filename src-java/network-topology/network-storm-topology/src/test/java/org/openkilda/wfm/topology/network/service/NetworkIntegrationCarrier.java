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

import org.openkilda.messaging.command.reroute.RerouteFlows;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.IslStatusUpdateNotification;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.PortProperties;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.wfm.share.history.model.PortHistoryEvent;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.model.OnlineStatus;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import lombok.Data;

import java.time.Instant;

@Data
public class NetworkIntegrationCarrier
        implements ISwitchCarrier, IPortCarrier, IBfdSessionCarrier, IUniIslCarrier, IIslCarrier {
    private final NetworkSwitchService switchService;

    private final NetworkPortService portService;

    private final NetworkBfdSessionService bfdPortService;

    private final NetworkUniIslService uniIslService;

    private final NetworkIslService islService;

    private ISwitchCarrier switchCarrier = this;
    private IPortCarrier portCarrier = this;
    private IBfdSessionCarrier bfdPortCarrier = this;
    private IUniIslCarrier uniIslCarrier = this;
    private IIslCarrier islCarrier = this;

    public NetworkIntegrationCarrier(NetworkSwitchService switchService,
                                     NetworkPortService portService,
                                     NetworkBfdSessionService bfdPortService,
                                     NetworkUniIslService uniIslService,
                                     NetworkIslService islService) {
        this.switchService = switchService;
        this.portService = portService;
        this.bfdPortService = bfdPortService;
        this.uniIslService = uniIslService;
        this.islService = islService;
    }

    @Override
    public void bfdPropertiesApplyRequest(Endpoint physicalEndpoint, IslReference reference, BfdProperties properties) {
        bfdPortService.enableUpdate(physicalEndpoint, reference, properties);
    }

    @Override
    public void bfdDisableRequest(Endpoint physicalEndpoint) {
        bfdPortService.disable(physicalEndpoint);
    }

    @Override
    public void setupUniIslHandler(Endpoint endpoint, Isl history) {
        uniIslService.uniIslSetup(endpoint, history);
    }

    @Override
    public void enableDiscoveryPoll(Endpoint endpoint) {
        // WatchList service is not covered by this test
    }

    @Override
    public void disableDiscoveryPoll(Endpoint endpoint) {
        // WatchList service is not covered by this test
    }

    @Override
    public void notifyPortDiscovered(Endpoint endpoint, IslInfoData speakerDiscoveryEvent) {
        uniIslService.uniIslDiscovery(endpoint, speakerDiscoveryEvent);
    }

    @Override
    public void notifyPortDiscoveryFailed(Endpoint endpoint) {
        uniIslService.uniIslFail(endpoint);
    }

    @Override
    public void notifyPortPhysicalDown(Endpoint endpoint) {
        uniIslService.uniIslPhysicalDown(endpoint);
    }

    @Override
    public void removeUniIslHandler(Endpoint endpoint) {
        uniIslService.uniIslRemove(endpoint);
    }

    @Override
    public void notifyPortPropertiesChanged(PortProperties portProperties) {
        // Northbound service is not covered by this test
    }

    @Override
    public void notifyPortRoundTripStatus(RoundTripStatus status) {
        uniIslService.roundTripStatusNotification(status);
    }

    @Override
    public String addBfdSession(NoviBfdSession bfdSession) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
        return "dummy";
    }

    @Override
    public String deleteBfdSession(NoviBfdSession bfdSession) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
        return "dummy";
    }

    @Override
    public void bfdUpNotification(Endpoint physicalEndpoint) {
        uniIslService.uniIslBfdStatusUpdate(physicalEndpoint, BfdStatusUpdate.UP);
    }

    @Override
    public void bfdDownNotification(Endpoint physicalEndpoint) {
        uniIslService.uniIslBfdStatusUpdate(physicalEndpoint, BfdStatusUpdate.DOWN);
    }

    @Override
    public void bfdKillNotification(Endpoint physicalEndpoint) {
        uniIslService.uniIslBfdStatusUpdate(physicalEndpoint, BfdStatusUpdate.KILL);
    }

    @Override
    public void bfdFailNotification(Endpoint physicalEndpoint) {
        uniIslService.uniIslBfdStatusUpdate(physicalEndpoint, BfdStatusUpdate.FAIL);
    }

    @Override
    public void setupPortHandler(Endpoint endpoint, Isl history) {
        portService.setup(endpoint, history);
    }

    @Override
    public void removePortHandler(Endpoint endpoint) {
        portService.remove(endpoint);
    }

    @Override
    public void setOnlineMode(Endpoint endpoint, OnlineStatus onlineStatus) {
        portService.updateOnlineMode(endpoint, onlineStatus);
    }

    @Override
    public void setPortLinkMode(Endpoint endpoint, LinkStatus linkStatus) {
        portService.updateLinkStatus(endpoint, linkStatus);
    }

    @Override
    public void setupBfdPortHandler(Endpoint endpoint, int physicalPortNumber) {
        bfdPortService.setup(endpoint, physicalPortNumber);
    }

    @Override
    public void removeBfdPortHandler(Endpoint logicalEndpoint) {
        bfdPortService.delete(logicalEndpoint);
    }

    @Override
    public void setBfdPortLinkMode(Endpoint logicalEndpoint, LinkStatus linkStatus) {
        bfdPortService.updateLinkStatus(logicalEndpoint, linkStatus);
    }

    @Override
    public void setBfdPortOnlineMode(Endpoint endpoint, boolean mode) {
        bfdPortService.updateOnlineStatus(endpoint, mode);
    }

    @Override
    public void sendSwitchSynchronizeRequest(String key, SwitchId switchId) {
    }

    @Override
    public void sendAffectedFlowRerouteRequest(SwitchId switchId) {
    }

    @Override
    public void sendSwitchStateChanged(SwitchId switchId, SwitchStatus status) {

    }

    @Override
    public void setupIslFromHistory(Endpoint endpoint, IslReference islReference, Isl history) {
        islService.islSetupFromHistory(endpoint, islReference, history);
    }

    @Override
    public void notifyIslUp(Endpoint endpoint, IslReference reference, IslDataHolder islData) {
        islService.islUp(endpoint, reference, islData);
    }

    @Override
    public void notifyIslDown(Endpoint endpoint, IslReference reference, IslDownReason reason) {
        islService.islDown(endpoint, reference, reason);
    }

    @Override
    public void sendPortStateChangedHistory(Endpoint endpoint, PortHistoryEvent event, Instant time) {
    }

    @Override
    public void notifyIslMove(Endpoint endpoint, IslReference reference) {
        islService.islMove(endpoint, reference);
    }

    @Override
    public void notifyIslRoundTripStatus(IslReference reference, RoundTripStatus status) {
        islService.roundTripStatusNotification(reference, status);
    }

    @Override
    public void notifyBfdStatus(Endpoint endpoint, IslReference reference, BfdStatusUpdate status) {
        islService.bfdStatusUpdate(endpoint, reference, status);
    }

    @Override
    public void exhaustedPollModeUpdateRequest(Endpoint endpoint, boolean enableExhaustedPollMode) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }

    public void triggerReroute(RerouteFlows trigger) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }

    @Override
    public void islStatusUpdateNotification(IslStatusUpdateNotification trigger) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }

    @Override
    public void islDefaultRulesInstall(Endpoint source, Endpoint destination) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }

    @Override
    public void islDefaultRulesDelete(Endpoint source, Endpoint destination) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }

    @Override
    public void auxiliaryPollModeUpdateRequest(Endpoint endpoint, boolean enableAuxiliaryPollMode) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }

    @Override
    public void islRemovedNotification(Endpoint srcEndpoint, IslReference reference) {
        // Real implementation emit event into external component, i.e.it is outside scope of this integration test.
    }
}
