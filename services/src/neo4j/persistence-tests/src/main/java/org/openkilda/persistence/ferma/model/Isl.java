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

import java.time.Instant;

public interface Isl {
    Switch getSrcSwitch();

    SwitchId getSrcSwitchId();

    Switch getDestSwitch();

    SwitchId getDestSwitchId();

    int getSrcPort();

    void setSrcPort(int srcPort);

    int getDestPort();

    void setDestPort(int destPort);

    long getLatency();

    void setLatency(long latency);

    long getSpeed();

    void setSpeed(long speed);

    int getCost();

    void setCost(int cost);

    long getMaxBandwidth();

    void setMaxBandwidth(long maxBandwidth);

    long getDefaultMaxBandwidth();

    void setDefaultMaxBandwidth(long defaultMaxBandwidth);

    long getAvailableBandwidth();

    void setAvailableBandwidth(long availableBandwidth);

    IslStatus getStatus();

    void setStatus(IslStatus status);

    IslStatus getActualStatus();

    void setActualStatus(IslStatus actualStatus);

    IslDownReason getDownReason();

    void setDownReason(IslDownReason downReason);

    Instant getTimeCreate();

    void setTimeCreate(Instant timeCreate);

    Instant getTimeModify();

    void setTimeModify(Instant timeModify);

    boolean isUnderMaintenance();

    void setUnderMaintenance(boolean underMaintenance);

    boolean isEnableBfd();

    void setEnableBfd(boolean enableBfd);

    String getBfdSessionStatus();

    void setBfdSessionStatus(String bfdSessionStatus);
}
