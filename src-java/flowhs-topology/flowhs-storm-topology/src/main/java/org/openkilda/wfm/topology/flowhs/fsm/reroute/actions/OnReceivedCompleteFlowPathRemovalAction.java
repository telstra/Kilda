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

import org.openkilda.model.FlowPath;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.BaseFlowPathRemovalAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.CompleteFlowPathRemovalResponse;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnReceivedCompleteFlowPathRemovalAction
        extends BaseFlowPathRemovalAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public OnReceivedCompleteFlowPathRemovalAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {

        if (context.getDbResponse().isSuccess()) {
            CompleteFlowPathRemovalResponse response
                    = (CompleteFlowPathRemovalResponse) context.getDbResponse();

            if (response.getRemovedPrimaryPaths() != null) {
                saveRemovalActionWithDumpToHistory(stateMachine, response.getFlow(), response.getRemovedPrimaryPaths());
            }

            if (response.getRemovedProtectedPaths() != null) {
                saveRemovalActionWithDumpToHistory(stateMachine, response.getFlow(),
                        response.getRemovedProtectedPaths());
            }

            if (response.getRemovedRejectedPaths() != null) {
                for (FlowPath rejectedPath : response.getRemovedRejectedPaths()) {
                    saveRemovalActionWithDumpToHistory(stateMachine, response.getFlow(), rejectedPath);
                }
            }

            stateMachine.fire(Event.FLOW_PATHS_REMOVED);
        } else {
            DbErrorResponse errorResponse = (DbErrorResponse) context.getDbResponse();
            stateMachine.fireError(errorResponse.getErrorMessage());
        }
    }
}
