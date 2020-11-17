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

import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.model.BfdStatusUpdate;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class NetworkUniIslService {
    private final Map<Endpoint, IslReference> endpointData = new HashMap<>();

    private final IUniIslCarrier carrier;

    public NetworkUniIslService(IUniIslCarrier carrier) {
        this.carrier = carrier;
    }

    /**
     * .
     */
    public void uniIslSetup(Endpoint endpoint, Isl history) {
        log.info("Uni-ISL service receive SETUP request for {}", endpoint);

        IslReference reference;
        if (history != null) {
            reference = IslReference.of(history);
            carrier.setupIslFromHistory(endpoint, reference, history);
        } else {
            reference = IslReference.of(endpoint);
        }
        endpointData.put(endpoint, reference);
    }

    /**
     * .
     */
    public void uniIslDiscovery(Endpoint endpoint, IslInfoData speakerDiscoveryEvent) {
        log.debug("Uni-ISL service receive DISCOVERED notification for {}", endpoint);

        IslReference reference = lookupEndpointData(endpoint);
        IslReference effectiveReference = IslReference.of(speakerDiscoveryEvent);
        IslDataHolder islData = new IslDataHolder(speakerDiscoveryEvent);
        if (isIslReferenceUsable(reference)) {
            if (reference.equals(effectiveReference)) {
                carrier.notifyIslUp(endpoint, reference, islData);
                return;
            }

            carrier.notifyIslMove(endpoint, reference);
        }

        if (!effectiveReference.isSelfLoop()) {
            carrier.notifyIslUp(endpoint, effectiveReference, islData);
            carrier.exhaustedPollModeUpdateRequest(endpoint, false);
        } else {
            log.error("Self looped ISL discovery received: {}", effectiveReference);
        }
        endpointData.put(endpoint, effectiveReference);
    }

    /**
     * .
     */
    public void uniIslFail(Endpoint endpoint) {
        log.debug("Uni-ISL service receive FAILED notification for {}", endpoint);
        handleDiscoveryFail(endpoint, IslDownReason.POLL_TIMEOUT);
    }

    /**
     * .
     */
    public void uniIslPhysicalDown(Endpoint endpoint) {
        log.debug("Uni-ISL service receive PHYSICAL-DOWN notification for {}", endpoint);
        handleDiscoveryFail(endpoint, IslDownReason.PORT_DOWN);
    }

    /**
     * Process round trip status notification.
     */
    public void roundTripStatusNotification(RoundTripStatus status) {
        log.debug("Uni-ISL service receive ROUND TRIP STATUS notification");
        IslReference reference = lookupEndpointData(status.getEndpoint());
        if (isIslReferenceUsable(reference)) {
            carrier.notifyIslRoundTripStatus(reference, status);
        }
    }

    /**
     * .
     */
    public void uniIslBfdStatusUpdate(Endpoint endpoint, BfdStatusUpdate status) {
        log.debug("Uni-ISL service receive BFD status update for {} - status:{}", endpoint, status);
        IslReference reference = lookupEndpointData(endpoint);
        if (isIslReferenceUsable(reference)) {
            carrier.notifyBfdStatus(endpoint, reference, status);
        }
    }

    /**
     * .
     */
    public void uniIslRemove(Endpoint endpoint) {
        log.info("Uni-ISL service receive KILL request for {}", endpoint);
        endpointData.remove(endpoint);
    }

    /**
     * Process ISL removed notification.
     */
    public void islRemovedNotification(Endpoint endpoint, IslReference removedIsl) {
        log.debug("Uni-ISL service receive ISL-REMOVED notification for {}", endpoint);

        IslReference storedIslReference = lookupEndpointData(endpoint);
        if (removedIsl.equals(storedIslReference)) {
            log.info("Received ISL-REMOVED notification. The endpoint data for {} has been set to the initial value.",
                    endpoint);
            endpointData.put(endpoint, IslReference.of(endpoint));
            carrier.exhaustedPollModeUpdateRequest(endpoint, false);
        }
    }

    // -- private --

    private void handleDiscoveryFail(Endpoint endpoint, IslDownReason downReason) {
        IslReference reference = lookupEndpointData(endpoint);
        if (isIslReferenceUsable(reference)) {
            carrier.notifyIslDown(endpoint, reference, downReason);
        } else if (reference.isIncomplete()) {
            carrier.exhaustedPollModeUpdateRequest(endpoint, true);
        }
    }

    private IslReference lookupEndpointData(Endpoint endpoint) {
        IslReference data = endpointData.get(endpoint);
        if (data == null) {
            throw new IllegalStateException(String.format("Uni-ISL not found (%s).", endpoint));
        }
        return data;
    }

    private static boolean isIslReferenceUsable(IslReference reference) {
        return !reference.isIncomplete() && !reference.isSelfLoop();
    }
}
