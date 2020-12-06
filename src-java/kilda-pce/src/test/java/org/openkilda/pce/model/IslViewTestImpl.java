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

package org.openkilda.pce.model;

import org.openkilda.model.IslStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.repositories.IslRepository.IslView;

import lombok.Value;

@Value
public class IslViewTestImpl implements IslView {
    SwitchId srcSwitchId;
    int srcPort;
    String srcPop;
    SwitchId destSwitchId;
    int destPort;
    String destPop;
    long latency;
    int cost;
    long availableBandwidth;
    boolean isUnstable;
    boolean isUnderMaintenance;

    @Override
    public long getMaxBandwidth() {
        return 0;
    }

    @Override
    public IslStatus getStatus() {
        return IslStatus.ACTIVE;
    }
}
