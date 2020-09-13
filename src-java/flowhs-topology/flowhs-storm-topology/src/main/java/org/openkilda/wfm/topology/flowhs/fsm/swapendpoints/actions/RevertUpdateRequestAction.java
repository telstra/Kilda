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

package org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.actions;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.command.flow.FlowRequest.Type;
import org.openkilda.model.Flow;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsContext;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;

import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.AnonymousAction;

import java.util.ArrayList;

@Slf4j
public class RevertUpdateRequestAction
        extends AnonymousAction<FlowSwapEndpointsFsm, State, Event, FlowSwapEndpointsContext> {

    @Override
    public void execute(State from, State to, Event event, FlowSwapEndpointsContext context,
                        FlowSwapEndpointsFsm stateMachine) {
        stateMachine.setAwaitingResponses(FlowSwapEndpointsFsm.REQUEST_COUNT);
        stateMachine.setFlowResponses(new ArrayList<>());

        Flow firstOriginalFlow = stateMachine.getFirstOriginalFlow();
        Flow secondOriginalFlow = stateMachine.getSecondOriginalFlow();

        sendRevertUpdateCommand(firstOriginalFlow, secondOriginalFlow.getFlowId(), stateMachine);
        sendRevertUpdateCommand(secondOriginalFlow, firstOriginalFlow.getFlowId(), stateMachine);
    }

    private void sendRevertUpdateCommand(Flow flow, String anotherFlowId, FlowSwapEndpointsFsm stateMachine) {
        FlowRequest flowRequest = RequestedFlowMapper.INSTANCE.toFlowRequest(flow);
        flowRequest.setBulkUpdateFlowIds(Sets.newHashSet(anotherFlowId));
        flowRequest.setType(Type.UPDATE);
        flowRequest.setDoNotRevert(true);

        stateMachine.sendFlowUpdateRequest(flowRequest);
        stateMachine.saveFlowActionToHistory(flow.getFlowId(), "DbCommand for revert update flow has been sent");
    }
}
