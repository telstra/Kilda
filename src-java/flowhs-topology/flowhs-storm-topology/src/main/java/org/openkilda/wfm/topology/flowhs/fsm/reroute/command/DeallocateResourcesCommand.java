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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.command;

import org.openkilda.messaging.MessageContext;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.DeallocateResourcesResponse;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.DeallocateResourcesResponse.DeallocateResourcesResponseBuilder;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.UUID;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public class DeallocateResourcesCommand extends DbCommand implements ICommand<SpeakerWorkerService> {
    Collection<FlowResources> oldResources;
    Collection<FlowResources> rejectedResources;

    public DeallocateResourcesCommand(@NonNull MessageContext messageContext, @NonNull UUID commandId,
                                      Collection<FlowResources> oldResources,
                                      Collection<FlowResources> rejectedResources) {
        super(messageContext, commandId);
        this.oldResources = oldResources;
        this.rejectedResources = rejectedResources;
    }

    @Override
    public void apply(SpeakerWorkerService service) {
        final long time = System.currentTimeMillis();

        DeallocateResourcesResponseBuilder responseBuilder = DeallocateResourcesResponse.builder()
                .messageContext(messageContext)
                .commandId(commandId);

        oldResources.forEach(flowResources -> {
            service.getTransactionManager().doInTransaction(() ->
                    service.getResourcesManager().deallocatePathResources(flowResources));

            responseBuilder.oldResource(flowResources);
        });

        rejectedResources.forEach(flowResources -> {
            service.getTransactionManager().doInTransaction(() ->
                    service.getResourcesManager().deallocatePathResources(flowResources));

            responseBuilder.rejectedResource(flowResources);
        });
        log.warn("HSTIME reroute deallocate old resources " + (System.currentTimeMillis() - time));

        service.sendResponse(responseBuilder.build());
    }
}
