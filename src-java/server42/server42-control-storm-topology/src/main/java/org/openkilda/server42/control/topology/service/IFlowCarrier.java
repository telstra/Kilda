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

package org.openkilda.server42.control.topology.service;

import org.openkilda.model.SwitchId;

import java.util.Set;

public interface IFlowCarrier {

    void notifyActivateFlowMonitoring(String id, SwitchId switchId, Integer port, Integer vlan, Integer innerVlan,
                                      boolean isForward);

    void notifyDeactivateFlowMonitoring(SwitchId switchId, String flowId, boolean isForward);

    void processSendFlowListOnSwitchCommand(SwitchId switchId);

    void sendListOfFlowBySwitchId(SwitchId switchId, Set<String> flowOnSwitch);
}
