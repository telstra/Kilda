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

package org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.command;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.storm.ICommand;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.hub.BfdHub;

import lombok.Getter;

public abstract class BfdHubCommand implements ICommand<BfdHub> {
    @Getter
    private final Endpoint endpoint;

    public BfdHubCommand(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    protected static Endpoint extractEndpoint(BfdSessionResponse response) {
        return extractEndpoint(response.getBfdSession());
    }

    protected static Endpoint extractEndpoint(NoviBfdSession session) {
        return Endpoint.of(session.getTarget().getDatapath(), session.getLogicalPortNumber());
    }
}
