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

package org.openkilda.wfm.topology.network.storm.bolt.bfdport.command;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.storm.bolt.bfdport.BfdPortHandler;

import lombok.Getter;

public abstract class BfdPortCommand implements ICommand<BfdPortHandler> {
    @Getter
    private final Endpoint endpoint;

    public BfdPortCommand(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    protected static Endpoint extractEndpoint(BfdSessionResponse response) {
        return extractEndpoint(response.getBfdSession());
    }

    protected static Endpoint extractEndpoint(NoviBfdSession session) {
        return Endpoint.of(session.getTarget().getDatapath(), session.getLogicalPortNumber());
    }
}
