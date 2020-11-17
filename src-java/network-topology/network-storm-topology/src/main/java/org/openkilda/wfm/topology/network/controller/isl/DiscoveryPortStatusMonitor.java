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

package org.openkilda.wfm.topology.network.controller.isl;

import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.model.IslEndpointPortStatus;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class DiscoveryPortStatusMonitor extends DiscoveryMonitor<IslEndpointPortStatus> {
    // To avoid a race condition between port-down and round-trip updates we enforce to wait for at least
    // MIN_UPDATE_COUNT updates before resetting reset DOWN state on both endpoints.
    private static final int MIN_UPDATE_COUNT = 2;

    public DiscoveryPortStatusMonitor(IslReference reference) {
        super(reference);

        IslEndpointPortStatus dummy = new IslEndpointPortStatus();
        discoveryData.putBoth(dummy);
        cache.putBoth(dummy);
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        boolean isDown = discoveryData.stream()
                .anyMatch(IslEndpointPortStatus::isDown);
        if (isDown) {
            return Optional.of(IslStatus.INACTIVE);
        }
        return Optional.empty();
    }

    @Override
    public IslDownReason getDownReason() {
        return IslDownReason.PORT_DOWN;
    }

    @Override
    public String getName() {
        return "port-down";
    }

    @Override
    public void actualUpdate(IslFsmEvent event, IslFsmContext context) {
        switch (event) {
            case ISL_DOWN:
                if (context.getDownReason() == IslDownReason.PORT_DOWN) {
                    becomeDown(context.getEndpoint());
                }
                break;
            case ISL_UP:
                // As a protection from race conditions, mark as active only destination side (which one actually
                // receive packet)
                becomeUp(reference.getOpposite(context.getEndpoint()));
                break;

            case ROUND_TRIP_STATUS:
                updateRoundTrip(context.getRoundTripStatus());
                break;

            default:
                // nothing to do here
        }
    }

    @Override
    public void actualFlush(Endpoint endpoint, Isl persistentView) {
        if (evaluateStatus().orElse(IslStatus.ACTIVE) == IslStatus.INACTIVE) {
            log.info("Set ISL {} ===> {} unstable time due to physical port down",
                    Endpoint.of(persistentView.getSrcSwitchId(), persistentView.getSrcPort()),
                    Endpoint.of(persistentView.getDestSwitchId(), persistentView.getDestPort()));
            persistentView.setTimeUnstable(persistentView.getTimeModify());
        }
    }

    private void becomeDown(Endpoint endpoint) {
        IslEndpointPortStatus current = discoveryData.get(endpoint);
        discoveryData.put(
                endpoint, new IslEndpointPortStatus(true, MIN_UPDATE_COUNT, current.getLastRoundTripReceivedAt()));
    }

    private void becomeUp(Endpoint endpoint) {
        IslEndpointPortStatus current = discoveryData.get(endpoint);
        discoveryData.put(endpoint, new IslEndpointPortStatus(current.getLastRoundTripReceivedAt()));
    }

    private void updateRoundTrip(RoundTripStatus roundTrip) {
        IslEndpointPortStatus current = discoveryData.get(roundTrip.getEndpoint());
        if (current.getLastRoundTripReceivedAt().isBefore(roundTrip.getLastSeen())) {
            int resetCounter = Math.max(current.getResetCounter() - 1, 0);
            IslEndpointPortStatus update = new IslEndpointPortStatus(
                    0 < resetCounter, resetCounter, roundTrip.getLastSeen());
            discoveryData.put(roundTrip.getEndpoint(), update);

            updateRoundTrip(reference.getOpposite(roundTrip.getEndpoint()));
        }
    }

    private void updateRoundTrip(Endpoint endpoint) {
        IslEndpointPortStatus current = discoveryData.get(endpoint);
        int resetCounter = Math.max(current.getResetCounter() - 1, 0);
        discoveryData.put(
                endpoint, new IslEndpointPortStatus(
                        0 < resetCounter, resetCounter, current.getLastRoundTripReceivedAt()));
    }
}
