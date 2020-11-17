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

import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;

import lombok.Getter;

import java.util.Optional;

class StatusAggregator {
    private IslStatus effectiveStatus = null;

    @Getter
    private IslDownReason downReason = null;

    private String details = "";
    private boolean isFirstHit = true;

    public void evaluate(DiscoveryMonitor<?> monitor) {
        Optional<IslStatus> status = monitor.evaluateStatus();
        String detailsEntry = monitor.getName() + ":";
        if (status.isPresent()) {
            if (isFirstHit) {
                isFirstHit = false;

                effectiveStatus = status.get();
                downReason = monitor.getDownReason();
                detailsEntry = "*" + detailsEntry;
            }
            detailsEntry += status.get();
        } else {
            detailsEntry += "-";
        }

        updateDetails(detailsEntry);
    }

    IslStatus getEffectiveStatus() {
        if (effectiveStatus == null) {
            return IslStatus.INACTIVE;
        }
        return effectiveStatus;
    }

    String getDetails() {
        return String.join(" ", details);
    }

    private void updateDetails(String entry) {
        if (details.isEmpty()) {
            details = entry;
        } else {
            details += " " + entry;
        }
    }
}
