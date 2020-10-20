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

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.NbTrackableAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocateProtectedResourcesResponse;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;
import org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class OnReceivedAllocateProtectedResourcesAction
        extends NbTrackableAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public OnReceivedAllocateProtectedResourcesAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowRerouteContext context,
                                                    FlowRerouteFsm stateMachine) throws FlowProcessingException {
        log.debug("Received allocation resources response");

        if (context.getDbResponse().isSuccess()) {
            AllocateProtectedResourcesResponse response
                    = (AllocateProtectedResourcesResponse) context.getDbResponse();

            switch (response.getAllocatePathStatus()) {
                case FOUND_OVERLAPPING_PATH:

                    stateMachine.setNewFlowStatus(response.getFlow().computeFlowStatus());
                    stateMachine.setOriginalFlowStatus(null);
                    stateMachine.saveActionToHistory(
                            "Couldn't find non overlapping protected path. Skipped creating it");
                    break;
                case FOUND_NEW_PATH:
                    stateMachine.setNewProtectedResources(response.getFlowResources());
                    stateMachine.setNewProtectedForwardPath(response.getForwardPathId());
                    stateMachine.setNewProtectedReversePath(response.getReversePathId());

                    saveAllocationActionWithDumpsToHistory(
                            stateMachine, response.getFlow(), "protected", response.getNewPaths());
                    break;
                case FOUND_SAME_PATH:
                    stateMachine.saveActionToHistory("Found the same protected path. Skipped creating of it");
                    break;
                default:
                    throw new IllegalStateException(
                            "Unknown AllocateProtectedResourcesResponse status " + response.getAllocatePathStatus());
            }

            stateMachine.fire(Event.RESOURCES_ALLOCATED);
            return Optional.empty();
        } else {
            DbErrorResponse errorResponse = (DbErrorResponse) context.getDbResponse();
            onFailure(stateMachine);

            switch (errorResponse.getResponseErrorType()) {
                case NOT_FOUND:
                    stateMachine.saveActionToHistory(errorResponse.getErrorMessage());
                    stateMachine.fireNoPathFound(errorResponse.getErrorMessage());
                    return handleError(stateMachine, ErrorType.NOT_FOUND, errorResponse.getErrorMessage());
                case INTERNAL_ERROR:
                    if (errorResponse.getOperationErrorType() == DbOperationErrorType.RECOVERABLE_ERROR) {
                        stateMachine.saveActionToHistory(errorResponse.getErrorMessage());
                    } else {
                        stateMachine.saveErrorToHistory(errorResponse.getErrorMessage());
                    }
                    stateMachine.fireError(errorResponse.getErrorMessage());
                    return handleError(stateMachine, ErrorType.INTERNAL_ERROR, errorResponse.getErrorMessage());
                default:
                    throw new FlowProcessingException(ErrorType.INTERNAL_ERROR, errorResponse.getErrorMessage());
            }
        }
    }

    protected void saveAllocationActionWithDumpsToHistory(FlowRerouteFsm stateMachine, Flow flow, String pathType,
                                                          FlowPathPair newFlowPaths) {
        FlowDumpData dumpData = HistoryMapper.INSTANCE.map(flow, newFlowPaths.getForward(), newFlowPaths.getReverse(),
                DumpType.STATE_AFTER);
        stateMachine.saveActionWithDumpToHistory(format("New %s paths were created", pathType),
                format("The flow paths %s / %s were created (with allocated resources)",
                        newFlowPaths.getForward().getPathId(), newFlowPaths.getReverse().getPathId()),
                dumpData);
    }

    protected void onFailure(FlowRerouteFsm stateMachine) {
        stateMachine.setNewProtectedResources(null);
        stateMachine.setNewProtectedForwardPath(null);
        stateMachine.setNewProtectedReversePath(null);
    }


    private Optional<Message> handleError(FlowRerouteFsm stateMachine, ErrorType errorType, String errorMessage) {
        Message message = buildErrorMessage(stateMachine, errorType,
                getGenericErrorMessage(), errorMessage);
        stateMachine.setOperationResultMessage(message);
        return Optional.of(message);
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not reroute flow";
    }
}
