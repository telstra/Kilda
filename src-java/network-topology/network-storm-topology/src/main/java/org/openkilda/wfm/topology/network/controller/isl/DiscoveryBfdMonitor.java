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

import org.openkilda.model.BfdSessionStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.model.IslEndpointBfdStatus;

import java.util.Iterator;
import java.util.Optional;

public class DiscoveryBfdMonitor extends DiscoveryMonitor<IslEndpointBfdStatus> {
    public DiscoveryBfdMonitor(IslReference reference) {
        super(reference);

        IslEndpointBfdStatus dummy = new IslEndpointBfdStatus();
        discoveryData.putBoth(dummy);
        cache.putBoth(dummy);
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        boolean isEnabled = true;
        boolean isUp = false;

        for (Iterator<IslEndpointBfdStatus> it = discoveryData.stream().iterator(); it.hasNext(); ) {
            IslEndpointBfdStatus entry = it.next();

            isEnabled &= entry.isEnabled();  // only if both endpoint are BFD capable we can use BFD statuses
            isUp |= entry.getStatus() == BfdSessionStatus.UP;
        }

        if (isEnabled) {
            return Optional.of(isUp ? IslStatus.ACTIVE : IslStatus.INACTIVE);
        }
        return Optional.empty();
    }

    @Override
    public IslDownReason getDownReason() {
        return IslDownReason.BFD_DOWN;
    }

    @Override
    public String getName() {
        return "BFD";
    }

    @Override
    public void actualUpdate(IslFsmEvent event, IslFsmContext context) {
        final Endpoint endpoint = context.getEndpoint();
        IslEndpointBfdStatus update = null;
        switch (event) {
            case BFD_UP:
                update = new IslEndpointBfdStatus(true, BfdSessionStatus.UP);
                break;
            case BFD_DOWN:
                update = new IslEndpointBfdStatus(true, BfdSessionStatus.DOWN);
                break;
            case BFD_KILL:
                update = new IslEndpointBfdStatus(false, null);
                break;
            case BFD_FAIL:
                update = new IslEndpointBfdStatus(false, BfdSessionStatus.FAIL);
                break;

            default:
                // nothing to do here
        }

        if (update != null) {
            discoveryData.put(endpoint, update);
        }
    }

    @Override
    public void actualFlush(Endpoint endpoint, Isl persistentView) {
        persistentView.setBfdSessionStatus(discoveryData.get(endpoint).getStatus());
    }

    /**
     * Returns true if BFD is operational, otherwise returns false.
     */
    public boolean isOperational() {
        return evaluateStatus().isPresent();
    }
}
