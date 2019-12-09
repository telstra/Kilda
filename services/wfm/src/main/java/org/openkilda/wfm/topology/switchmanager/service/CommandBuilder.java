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

package org.openkilda.wfm.topology.switchmanager.service;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.error.SwitchPropertiesNotFoundException;

import java.util.List;

public interface CommandBuilder {

    List<CommandData> buildCommandsToSyncMissingRules(SwitchId switchId, List<Long> switchRules)
            throws SwitchPropertiesNotFoundException;

    List<RemoveFlow> buildCommandsToRemoveExcessRules(SwitchId switchId,
                                                      List<FlowEntry> flows,
                                                      List<Long> excessRulesCookies);
}
