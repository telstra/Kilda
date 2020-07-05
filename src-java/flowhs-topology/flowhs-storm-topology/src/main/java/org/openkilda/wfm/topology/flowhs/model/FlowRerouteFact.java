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

package org.openkilda.wfm.topology.flowhs.model;

import org.openkilda.model.IslEndpoint;
import org.openkilda.wfm.CommandContext;

import lombok.Value;

import java.util.Collections;
import java.util.Set;

@Value
public class FlowRerouteFact {
    private String key;
    private CommandContext commandContext;
    private String flowId;
    private Set<IslEndpoint> affectedIsl;
    private boolean forceReroute;
    private boolean ignoreBandwidth;
    private boolean effectivelyDown;
    private String rerouteReason;

    public FlowRerouteFact(String key, CommandContext commandContext, String flowId, Set<IslEndpoint> affectedIsl,
                           boolean forceReroute, boolean ignoreBandwidth, boolean effectivelyDown,
                           String rerouteReason) {
        this.key = key;
        this.commandContext = commandContext;
        this.flowId = flowId;

        if (affectedIsl == null) {
            this.affectedIsl = Collections.emptySet();
        } else {
            this.affectedIsl = affectedIsl;
        }

        this.forceReroute = forceReroute;
        this.ignoreBandwidth = ignoreBandwidth;
        this.effectivelyDown = effectivelyDown;
        this.rerouteReason = rerouteReason;
    }
}
