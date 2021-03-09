/* Copyright 2021 Telstra Open Source
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

package org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.actions;

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsContext;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RevertFlowStatusAction
        extends FlowProcessingAction<FlowSwapEndpointsFsm, State, Event, FlowSwapEndpointsContext> {
    public RevertFlowStatusAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowSwapEndpointsContext context,
                           FlowSwapEndpointsFsm stateMachine) {
        Flow firstOriginalFlow = stateMachine.getFirstOriginalFlow();
        if (firstOriginalFlow != null) {
            log.debug("Reverting the flow status of {} to {}", firstOriginalFlow.getFlowId(),
                    firstOriginalFlow.getStatus());

            flowRepository.updateStatus(firstOriginalFlow.getFlowId(),
                    firstOriginalFlow.getStatus());

            stateMachine.saveActionToHistory(format("The flow status of %s was reverted to %s",
                    firstOriginalFlow.getFlowId(), firstOriginalFlow.getStatus()));
        }

        Flow secondOriginalFlow = stateMachine.getSecondOriginalFlow();
        if (secondOriginalFlow != null) {
            log.debug("Reverting the flow status of {} to {}", secondOriginalFlow.getFlowId(),
                    secondOriginalFlow.getStatus());

            flowRepository.updateStatus(secondOriginalFlow.getFlowId(),
                    secondOriginalFlow.getStatus());

            stateMachine.saveActionToHistory(format("The flow status of %s was reverted to %s",
                    secondOriginalFlow.getFlowId(), secondOriginalFlow.getStatus()));
        }
    }
}
