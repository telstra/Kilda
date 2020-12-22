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

package org.openkilda.adapter;

import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.PathId;

import lombok.NonNull;

public class FlowSourceAdapter extends FlowSideAdapter {
    public FlowSourceAdapter(Flow flow) {
        super(flow);
    }

    @Override
    public FlowEndpoint getEndpoint() {
        DetectConnectedDevices trackConnectedDevices = flow.getDetectConnectedDevices();
        return new FlowEndpoint(
                flow.getSrcSwitchId(), flow.getSrcPort(), flow.getSrcVlan(), flow.getSrcInnerVlan(),
                trackConnectedDevices.isSrcLldp() || trackConnectedDevices.isSrcSwitchLldp(),
                trackConnectedDevices.isSrcArp() || trackConnectedDevices.isSrcSwitchArp());
    }

    @Override
    public boolean isDetectConnectedDevicesLldp() {
        return flow.getDetectConnectedDevices().isSrcLldp();
    }

    @Override
    public boolean isDetectConnectedDevicesArp() {
        return flow.getDetectConnectedDevices().isSrcArp();
    }

    @Override
    public boolean isPrimaryEgressPath(@NonNull PathId pathId) {
        return pathId.equals(flow.getForwardPathId());
    }

    @Override
    public boolean isLooped() {
        return flow.getSrcSwitchId().equals(flow.getLoopSwitchId());
    }
}
