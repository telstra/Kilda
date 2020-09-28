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

package org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.command;

import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.BfdWorker;

public class BfdWorkerPortCreateCommand extends BfdWorkerPortCrudCommand {
    private final int physicalPortNumber;

    public BfdWorkerPortCreateCommand(String requestId, Endpoint logical, int physicalPortNumber) {
        super(requestId, logical);
        this.physicalPortNumber = physicalPortNumber;
    }

    @Override
    public void apply(BfdWorker handler) {
        handler.processPortCreateRequest(getRequestId(), logical, physicalPortNumber);
    }
}
