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

package org.openkilda.wfm.topology.flowhs.fsm.update.actions;

import static java.lang.String.format;

import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.Switch;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RevertFlowAction extends FlowProcessingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    private final SwitchRepository switchRepository;

    public RevertFlowAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
        switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId());

            revertFlow(flow, stateMachine);

            stateMachine.saveActionToHistory("The flow was reverted");
        });
    }

    private void revertFlow(Flow flow, FlowUpdateFsm stateMachine) {
        flow.setGroupId(stateMachine.getOriginalFlowGroup());

        RequestedFlow originalFlow = stateMachine.getOriginalFlow();

        Switch srcSwitch = switchRepository.findById(originalFlow.getSrcSwitch())
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Switch %s not found", originalFlow.getSrcSwitch())));
        flow.setSrcSwitch(srcSwitch);
        flow.setSrcPort(originalFlow.getSrcPort());
        flow.setSrcVlan(originalFlow.getSrcVlan());
        Switch destSwitch = switchRepository.findById(originalFlow.getDestSwitch())
                .orElseThrow(() -> new FlowProcessingException(ErrorType.NOT_FOUND,
                        format("Switch %s not found", originalFlow.getDestSwitch())));
        flow.setDestSwitch(destSwitch);
        flow.setDestPort(originalFlow.getDestPort());
        flow.setDestVlan(originalFlow.getDestVlan());

        flow.setPriority(originalFlow.getPriority());
        flow.setPinned(originalFlow.isPinned());
        flow.setAllocateProtectedPath(originalFlow.isAllocateProtectedPath());
        flow.setDescription(originalFlow.getDescription());
        flow.setBandwidth(originalFlow.getBandwidth());
        flow.setIgnoreBandwidth(originalFlow.isIgnoreBandwidth());
        flow.setMaxLatency(originalFlow.getMaxLatency());
        flow.setPeriodicPings(originalFlow.isPeriodicPings());
        flow.setEncapsulationType(originalFlow.getFlowEncapsulationType());
        flow.setPathComputationStrategy(originalFlow.getPathComputationStrategy());
        flow.setTargetPathComputationStrategy(stateMachine.getOldTargetPathComputationStrategy());
    }
}
