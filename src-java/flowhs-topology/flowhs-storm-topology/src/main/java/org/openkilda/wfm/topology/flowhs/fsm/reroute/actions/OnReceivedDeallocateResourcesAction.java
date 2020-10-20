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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import static java.lang.String.format;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.DeallocateResourcesResponse;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnReceivedDeallocateResourcesAction
        extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public OnReceivedDeallocateResourcesAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {

        if (context.getDbResponse().isSuccess()) {
            DeallocateResourcesResponse response
                    = (DeallocateResourcesResponse) context.getDbResponse();

            if (response.getOldResources() != null) {
                for (FlowResources oldResource : response.getOldResources()) {
                    stateMachine.saveActionToHistory("Flow resources were deallocated",
                            format("The flow resources for %s / %s were deallocated",
                                    oldResource.getForward().getPathId(), oldResource.getReverse().getPathId()));
                }
            }

            if (response.getRejectedResources() != null) {
                for (FlowResources rejectedResource : response.getRejectedResources()) {
                    stateMachine.saveActionToHistory("Rejected flow resources were deallocated",
                            format("The flow resources for %s / %s were deallocated",
                                    rejectedResource.getForward().getPathId(),
                                    rejectedResource.getReverse().getPathId()));
                }
            }

            stateMachine.fire(Event.RESOURCES_DEALLOCATED);
        } else {
            DbErrorResponse errorResponse = (DbErrorResponse) context.getDbResponse();
            stateMachine.fireError(errorResponse.getErrorMessage());
        }
    }
}
