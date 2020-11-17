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

import java.util.Optional;

public class DiscoveryMovedMonitor extends DiscoveryMonitor<Boolean> {
    protected DiscoveryMovedMonitor(IslReference reference) {
        super(reference);
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        boolean isMoved = discoveryData.stream()
                .anyMatch(entry -> entry != null && entry);
        if (isMoved) {
            return Optional.of(IslStatus.MOVED);
        }
        return Optional.empty();
    }

    @Override
    public IslDownReason getDownReason() {
        return null;  // this monitor can't produce down state
    }

    @Override
    public String getName() {
        return "moved";
    }

    @Override
    public void load(Endpoint endpoint, Isl persistentView) {
        super.load(endpoint, persistentView);

        boolean isMoved = persistentView.getActualStatus() == IslStatus.MOVED;
        discoveryData.put(endpoint, isMoved);
        cache.put(endpoint, isMoved);
    }

    @Override
    protected void actualUpdate(IslFsmEvent event, IslFsmContext context) {
        Endpoint endpoint = context.getEndpoint();
        Boolean update = discoveryData.get(endpoint);
        switch (event) {
            case ISL_MOVE:
                update = true;
                break;

            case ISL_UP:
            case ISL_DOWN:
                update = false;
                break;

            default:
                // nothing to do here
        }
        discoveryData.put(endpoint, update);
    }

    @Override
    protected void actualFlush(Endpoint endpoint, Isl persistentView) {
        Boolean isMoved = discoveryData.get(endpoint);
        if (isMoved != null && isMoved) {
            persistentView.setActualStatus(IslStatus.MOVED);
        }
    }
}
