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
import org.openkilda.messaging.info.reroute.error.NoPathFoundError;
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
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.AllocatePrimaryResourcesResponse;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;
import org.openkilda.wfm.topology.flowhs.service.DbOperationErrorType;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class OnReceivedAllocatePrimaryResourcesAction
        extends NbTrackableAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    public OnReceivedAllocatePrimaryResourcesAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowRerouteContext context,
                                                    FlowRerouteFsm stateMachine) throws FlowProcessingException {
        log.debug("Received allocation resources response");


        if (context.getDbResponse().isSuccess()) {
            AllocatePrimaryResourcesResponse response
                    = (AllocatePrimaryResourcesResponse) context.getDbResponse();

            stateMachine.setNewPrimaryPathComputationStrategy(response.getNewPrimaryPathComputationStrategy());
            stateMachine.setNewPrimaryResources(response.getFlowResources());
            stateMachine.setNewPrimaryForwardPath(response.getForwardPathId());
            stateMachine.setNewPrimaryReversePath(response.getReversePathId());

            if (response.getNewPaths() == null) {
                stateMachine.saveActionToHistory("Found the same primary path. Skipped creating of it");
            } else {
                saveAllocationActionWithDumpsToHistory(stateMachine, response.getFlow(), "primary",
                        response.getNewPaths());
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
        stateMachine.setNewPrimaryResources(null);
        stateMachine.setNewPrimaryForwardPath(null);
        stateMachine.setNewPrimaryReversePath(null);
        if (!stateMachine.isIgnoreBandwidth()) {
            stateMachine.setRerouteError(new NoPathFoundError());
        }
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
