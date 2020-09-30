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

package org.openkilda.persistence.dummy;

import org.openkilda.model.BfdSessionStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;

import lombok.Data;

@Data
public class IslDefaults {
    private long latency = 10;
    private int cost = 1000;
    private long speed = 10_000_000;

    private IslStatus status = IslStatus.ACTIVE;
    private IslStatus actualStatus = IslStatus.ACTIVE;

    private boolean underMaintenance = false;
    private BfdSessionStatus bfdSessionStatus = null;

    /**
     * Populate {@link Isl} object with defaults.
     */
    public Isl.IslBuilder fill(Isl.IslBuilder link) {
        link.latency(latency);
        link.cost(cost);

        link.speed(speed);
        link.maxBandwidth(speed);
        link.defaultMaxBandwidth(speed);
        link.availableBandwidth(speed);

        link.status(status);
        link.underMaintenance(underMaintenance);
        link.bfdSessionStatus(bfdSessionStatus);

        return link;
    }
}
