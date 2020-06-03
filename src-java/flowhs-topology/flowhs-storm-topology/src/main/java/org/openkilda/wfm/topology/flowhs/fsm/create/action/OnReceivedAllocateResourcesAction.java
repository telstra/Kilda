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

package org.openkilda.wfm.topology.flowhs.fsm.create.action;

import static java.lang.String.format;

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.NbTrackableAction;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.create.response.ResourcesAllocationResponse;
import org.openkilda.wfm.topology.flowhs.service.DbErrorResponse;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OnReceivedAllocateResourcesAction
        extends NbTrackableAction<FlowCreateFsm, State, Event, FlowCreateContext> {
    public OnReceivedAllocateResourcesAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowCreateContext context,
                                                    FlowCreateFsm stateMachine) throws FlowProcessingException {
        log.debug("Received allocation resources response");


        if (context.getDbResponse().isSuccess()) {
            if (stateMachine.getResourcesAllocationTimer() != null) {
                long duration = stateMachine.getResourcesAllocationTimer().stop();
                if (duration > 0) {
                    stateMachine.getMeterRegistry().timer("fsm.fsm.resource_allocation.execution",
                            "flow_id", stateMachine.getFlowId())
                            .record(duration, TimeUnit.NANOSECONDS);
                }
                stateMachine.setResourcesAllocationTimer(null);
            }
            ResourcesAllocationResponse allocationResponse = (ResourcesAllocationResponse) context.getDbResponse();

            if (allocationResponse.getFlow() == null) {
                return Optional.empty();
            }
            stateMachine.setForwardPathId(allocationResponse.getForwardPathId());
            stateMachine.setReversePathId(allocationResponse.getReversePathId());
            stateMachine.setProtectedForwardPathId(allocationResponse.getProtectedForwardPathId());
            stateMachine.setProtectedReversePathId(allocationResponse.getProtectedReversePathId());
            stateMachine.setFlowResources(allocationResponse.getFlowResources());
            stateMachine.setIngressCommands(allocationResponse.getIngressCommands());
            stateMachine.setNonIngressCommands(allocationResponse.getNonIngressCommands());

            saveHistory(stateMachine, allocationResponse.getFlow());
            if (allocationResponse.getFlow().isOneSwitchFlow()) {
                stateMachine.fire(Event.SKIP_NON_INGRESS_RULES_INSTALL);
            } else {
                stateMachine.fireNext(context);
            }
            return Optional.of(buildResponseMessage(allocationResponse.getFlow(), stateMachine.getCommandContext()));
        } else {
            DbErrorResponse errorResponse = (DbErrorResponse) context.getDbResponse();

            switch (errorResponse.getOperationErrorType()) {
                case FLOW_PROCESSING:
                    throw new FlowProcessingException(
                            errorResponse.getResponseErrorType(), errorResponse.getErrorMessage());
                case FLOW_ALREADY_EXIST:
                    if (!stateMachine.retryIfAllowed()) {
                        throw new FlowProcessingException(ErrorType.INTERNAL_ERROR, errorResponse.getErrorMessage());
                    } else {
                        // we have retried the operation, no need to respond.
                        //TODO Retry?
                        log.debug(errorResponse.getErrorMessage());
                        return Optional.empty();
                    }
                default:
                    throw new FlowProcessingException(ErrorType.INTERNAL_ERROR, errorResponse.getErrorMessage());
            }
        }
    }

    private void saveHistory(FlowCreateFsm stateMachine, Flow flow) {
        FlowDumpData primaryPathsDumpData =
                HistoryMapper.INSTANCE.map(flow, flow.getForwardPath(), flow.getReversePath(), DumpType.STATE_AFTER);
        stateMachine.saveActionWithDumpToHistory("New primary paths were created",
                format("The flow paths were created (with allocated resources): %s / %s",
                        flow.getForwardPathId(), flow.getReversePathId()),
                primaryPathsDumpData);

        if (flow.isAllocateProtectedPath()) {
            FlowDumpData protectedPathsDumpData = HistoryMapper.INSTANCE.map(flow, flow.getProtectedForwardPath(),
                    flow.getProtectedReversePath(), DumpType.STATE_AFTER);
            stateMachine.saveActionWithDumpToHistory("New protected paths were created",
                    format("The flow paths were created (with allocated resources): %s / %s",
                            flow.getProtectedForwardPathId(), flow.getProtectedReversePathId()),
                    protectedPathsDumpData);
        }
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not create flow";
    }
}
