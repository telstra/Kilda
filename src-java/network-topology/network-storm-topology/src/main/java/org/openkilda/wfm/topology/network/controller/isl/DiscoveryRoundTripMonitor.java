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
import org.openkilda.wfm.topology.network.model.IslEndpointRoundTripStatus;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class DiscoveryRoundTripMonitor extends DiscoveryMonitor<IslEndpointRoundTripStatus> {
    private final Clock clock;
    private final Duration roundTripExpirationTime;

    public DiscoveryRoundTripMonitor(IslReference reference, Clock clock, NetworkOptions options) {
        super(reference);
        this.clock = clock;
        roundTripExpirationTime = Duration.ofNanos(options.getDiscoveryTimeout());
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        boolean isActive = discoveryData.stream()
                .filter(Objects::nonNull)
                .map(IslEndpointRoundTripStatus::getStatus)
                .anyMatch(entry -> entry == IslStatus.ACTIVE);
        if (isActive) {
            return Optional.of(IslStatus.ACTIVE);
        }
        return Optional.empty();
    }

    @Override
    public IslDownReason getDownReason() {
        return null;  // this monitor can't produce down state
    }

    @Override
    public String getName() {
        return "round-trip";
    }

    @Override
    public void actualUpdate(IslFsmEvent event, IslFsmContext context) {
        if (event == IslFsmEvent.ROUND_TRIP_STATUS) {
            Instant expireAt = evaluateExpireAtTime(context.getRoundTripStatus());
            updateEndpointStatus(context.getEndpoint(), expireAt);
        }
    }

    @Override
    public void actualFlush(Endpoint endpoint, Isl persistentView) {
        IslEndpointRoundTripStatus roundTripStatus = discoveryData.get(endpoint);
        if (roundTripStatus != null) {
            persistentView.setRoundTripStatus(roundTripStatus.getStatus());
        }
    }

    private void updateEndpointStatus(Endpoint endpoint, Instant expireAt) {
        if (expireAt != null) {
            IslStatus status = clock.instant().isBefore(expireAt)
                    ? IslStatus.ACTIVE : IslStatus.INACTIVE;
            discoveryData.put(endpoint, new IslEndpointRoundTripStatus(expireAt, status));
        } else if (discoveryData.get(endpoint) != null) {
            discoveryData.put(endpoint, new IslEndpointRoundTripStatus(null, IslStatus.INACTIVE));
        }
    }

    private Instant evaluateExpireAtTime(RoundTripStatus status) {
        Duration foreignObsolescence = Duration.between(status.getLastSeen(), status.getNow());
        Duration timeLeft = roundTripExpirationTime.minus(foreignObsolescence);
        if (timeLeft.isNegative()) {
            return null;
        }

        return clock.instant().plus(timeLeft);
    }
}
