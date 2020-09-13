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
import org.openkilda.model.FlowEndpoint;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsContext;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.swapendpoints.FlowSwapEndpointsFsm.State;
import org.openkilda.wfm.topology.flowhs.mapper.RequestedFlowMapper;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.AnonymousAction;

@Slf4j
public class UpdateRequestAction extends AnonymousAction<FlowSwapEndpointsFsm, State, Event, FlowSwapEndpointsContext> {

    @Override
    public void execute(State from, State to, Event event, FlowSwapEndpointsContext context,
                        FlowSwapEndpointsFsm stateMachine) {
        Flow firstOriginalFlow = stateMachine.getFirstOriginalFlow();
        Flow secondOriginalFlow = stateMachine.getSecondOriginalFlow();

        RequestedFlow firstTargetFlow = stateMachine.getFirstTargetFlow();
        RequestedFlow secondTargetFlow = stateMachine.getSecondTargetFlow();

        sendUpdateCommand(firstOriginalFlow, firstTargetFlow, secondTargetFlow.getFlowId(), stateMachine);
        sendUpdateCommand(secondOriginalFlow, secondTargetFlow, firstTargetFlow.getFlowId(), stateMachine);
    }

    private void sendUpdateCommand(Flow flow, RequestedFlow targetFlow, String anotherFlowId,
                                   FlowSwapEndpointsFsm stateMachine) {
        FlowRequest flowRequest = RequestedFlowMapper.INSTANCE.toFlowRequest(flow);
        flowRequest.setSource(
                new FlowEndpoint(targetFlow.getSrcSwitch(), targetFlow.getSrcPort(), targetFlow.getSrcVlan()));
        flowRequest.setDestination(
                new FlowEndpoint(targetFlow.getDestSwitch(), targetFlow.getDestPort(), targetFlow.getDestVlan()));

        flowRequest.setBulkUpdateFlowIds(Sets.newHashSet(anotherFlowId));
        flowRequest.setType(Type.UPDATE);

        stateMachine.sendFlowUpdateRequest(flowRequest);
        stateMachine.saveFlowActionToHistory(flow.getFlowId(), "DbCommand for update flow has been sent");
    }
}
