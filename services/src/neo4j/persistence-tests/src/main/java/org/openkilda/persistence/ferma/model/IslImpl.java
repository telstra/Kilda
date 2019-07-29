/* Copyright 2017 Telstra Open Source
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

package org.openkilda.persistence.ferma.model;

import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.model.SwitchId;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.time.Instant;
import java.util.Optional;

/**
 * Represents an inter-switch link (ISL). This includes the source and destination, link status,
 * maximum and available bandwidth.
 */
@Data
public class IslImpl implements Isl {
    private Switch srcSwitch;
    private Switch destSwitch;
    private int srcPort;
    private int destPort;
    private long latency;
    private long speed;
    private int cost;
    private long maxBandwidth;
    private long defaultMaxBandwidth;
    private long availableBandwidth;
    private IslStatus status;
    private IslStatus actualStatus;
    private IslDownReason downReason;
    private Instant timeCreate;
    private Instant timeModify;
    private boolean underMaintenance;
    private boolean enableBfd;
    private String bfdSessionStatus;

    @Builder(toBuilder = true)
    public IslImpl(@NonNull Switch srcSwitch, @NonNull Switch destSwitch, int srcPort, int destPort,
                   long latency, long speed, int cost, long maxBandwidth, long defaultMaxBandwidth, long availableBandwidth,
                   IslStatus status, IslStatus actualStatus,
                   Instant timeCreate, Instant timeModify, boolean underMaintenance, boolean enableBfd,
                   String bfdSessionStatus) {
        this.srcSwitch = srcSwitch;
        this.destSwitch = destSwitch;
        this.srcPort = srcPort;
        this.destPort = destPort;
        this.latency = latency;
        this.speed = speed;
        this.cost = cost;
        this.maxBandwidth = maxBandwidth;
        this.defaultMaxBandwidth = defaultMaxBandwidth;
        this.availableBandwidth = availableBandwidth;
        this.status = status;
        this.actualStatus = actualStatus;
        this.timeCreate = timeCreate;
        this.timeModify = timeModify;
        this.underMaintenance = underMaintenance;
        this.enableBfd = enableBfd;
        this.bfdSessionStatus = bfdSessionStatus;
    }

    @Override
    public SwitchId getSrcSwitchId() {
        return Optional.ofNullable(getSrcSwitch()).map(Switch::getSwitchId).orElse(null);
    }

    @Override
    public SwitchId getDestSwitchId() {
        return Optional.ofNullable(getDestSwitch()).map(Switch::getSwitchId).orElse(null);
    }
}
