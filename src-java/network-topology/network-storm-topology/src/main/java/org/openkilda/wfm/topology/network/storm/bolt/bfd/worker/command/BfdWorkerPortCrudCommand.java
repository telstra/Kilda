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

import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.info.grpc.CreateLogicalPortResponse;
import org.openkilda.messaging.info.grpc.DeleteLogicalPortResponse;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.storm.bolt.bfd.worker.BfdWorker;


abstract class BfdWorkerPortCrudCommand extends BfdWorkerCommand {
    protected final Endpoint logical;

    public BfdWorkerPortCrudCommand(String requestId, Endpoint logical) {
        super(requestId);
        this.logical = logical;
    }

    @Override
    public void consumeResponse(BfdWorker handler, CreateLogicalPortResponse response) {
        handler.processPortCreateResponse(getRequestId(), logical, response);
    }

    @Override
    public void consumeResponse(BfdWorker handler, DeleteLogicalPortResponse response) {
        handler.processPortDeleteResponse(getRequestId(), logical, response);
    }

    @Override
    public void consumeResponse(BfdWorker handler, ErrorData response) {
        handler.processPortCrudErrorResponse(getRequestId(), logical, response);
    }

    @Override
    public void timeout(BfdWorker handler) {
        handler.processPortRequestTimeout(getRequestId(), logical);
    }
}
