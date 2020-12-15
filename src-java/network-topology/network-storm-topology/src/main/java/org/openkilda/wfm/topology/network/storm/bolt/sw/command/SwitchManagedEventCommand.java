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

package org.openkilda.wfm.topology.network.storm.bolt.sw.command;

import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.wfm.topology.network.storm.bolt.sw.SwitchHandler;

public class SwitchManagedEventCommand extends SwitchCommand {
    private final SpeakerSwitchView switchView;
    private final String dumpId;

    public SwitchManagedEventCommand(SpeakerSwitchView switchView, String dumpId) {
        super(switchView.getDatapath());
        this.switchView = switchView;
        this.dumpId = dumpId;
    }

    @Override
    public void apply(SwitchHandler handler) {
        handler.processSwitchBecomeManaged(switchView, dumpId);
    }
}
