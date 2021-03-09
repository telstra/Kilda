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

package org.openkilda.wfm.topology.flowhs.fsm.pathswap;

import org.openkilda.messaging.Message;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowPathSwappingFsm;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.NotifyFlowMonitorAction;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.ReportErrorAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.FlowPathSwapFsm.State;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.AbandonPendingCommandsAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.EmitIngressRulesVerifyRequestsAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.FlowValidateAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.HandleNotCompletedCommandsAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.InstallIngressRulesAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.OnFinishedAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.OnFinishedWithErrorAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.OnReceivedInstallResponseAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.OnReceivedRemoveOrRevertResponseAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.RecalculateFlowStatusAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.RemoveOldRulesAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.RevertNewRulesAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.RevertPathsSwapAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.UpdateFlowPathsAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.UpdateFlowStatusAction;
import org.openkilda.wfm.topology.flowhs.fsm.pathswap.action.ValidateIngressRulesAction;
import org.openkilda.wfm.topology.flowhs.service.FlowPathSwapHubCarrier;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

@Getter
@Setter
@Slf4j
public final class FlowPathSwapFsm extends FlowPathSwappingFsm<FlowPathSwapFsm, State, Event, FlowPathSwapContext> {

    private final FlowPathSwapHubCarrier carrier;

    public FlowPathSwapFsm(CommandContext commandContext, FlowPathSwapHubCarrier carrier,
                           String flowId) {
        super(commandContext, flowId);
        this.carrier = carrier;
    }

    @Override
    public void fireNext(FlowPathSwapContext context) {
        fire(Event.NEXT, context);
    }

    @Override
    public void fireError(String errorReason) {
        fireError(Event.ERROR, errorReason);
    }

    private void fireError(Event errorEvent, String errorReason) {
        if (this.errorReason != null) {
            log.error("Subsequent error fired: " + errorReason);
        } else {
            this.errorReason = errorReason;
        }

        fire(errorEvent);
    }

    @Override
    public void fireNoPathFound(String errorReason) {

    }

    @Override
    public void sendNorthboundResponse(Message message) {
        carrier.sendNorthboundResponse(message);
    }

    @Override
    public void reportError(Event event) {
        if (Event.TIMEOUT == event) {
            reportGlobalTimeout();
        }
    }

    @Override
    protected String getCrudActionName() {
        return "path swap";
    }

    public static class Factory {
        private final StateMachineBuilder<FlowPathSwapFsm, FlowPathSwapFsm.State, FlowPathSwapFsm.Event,
                FlowPathSwapContext> builder;
        private final FlowPathSwapHubCarrier carrier;
        private final FlowResourcesManager resourcesManager;


        public Factory(FlowPathSwapHubCarrier carrier, PersistenceManager persistenceManager,
                       FlowResourcesManager resourcesManager,
                       int speakerCommandRetriesLimit) {
            this.carrier = carrier;
            this.resourcesManager = resourcesManager;

            final ReportErrorAction<FlowPathSwapFsm, State, Event, FlowPathSwapContext>
                    reportErrorAction = new ReportErrorAction<>();

            builder = StateMachineBuilderFactory.create(FlowPathSwapFsm.class, State.class, Event.class,
                    FlowPathSwapContext.class, CommandContext.class, FlowPathSwapHubCarrier.class, String.class);

            FlowOperationsDashboardLogger dashboardLogger = new FlowOperationsDashboardLogger(log);

            builder.transition().from(State.INITIALIZED).to(State.FLOW_VALIDATED).on(Event.NEXT)
                    .perform(new FlowValidateAction(persistenceManager, dashboardLogger));
            builder.transition().from(State.INITIALIZED).to(State.FINISHED_WITH_ERROR).on(Event.TIMEOUT);

            builder.transition().from(State.FLOW_VALIDATED).to(State.FLOW_UPDATED).on(Event.NEXT)
                    .perform(new UpdateFlowPathsAction(persistenceManager));
            builder.transitions().from(State.FLOW_VALIDATED)
                    .toAmong(State.REVERTING_FLOW_STATUS, State.REVERTING_FLOW_STATUS)
                    .onEach(Event.TIMEOUT, Event.ERROR);

            builder.transition().from(State.FLOW_UPDATED).to(State.INSTALLING_INGRESS_RULES).on(Event.NEXT)
                    .perform(new InstallIngressRulesAction(persistenceManager, resourcesManager));
            builder.transitions().from(State.FLOW_UPDATED)
                    .toAmong(State.REVERTING_PATHS_SWAP, State.REVERTING_PATHS_SWAP)
                    .onEach(Event.TIMEOUT, Event.ERROR);

            builder.internalTransition().within(State.INSTALLING_INGRESS_RULES).on(Event.RESPONSE_RECEIVED)
                    .perform(new OnReceivedInstallResponseAction(speakerCommandRetriesLimit));
            builder.internalTransition().within(State.INSTALLING_INGRESS_RULES).on(Event.ERROR_RECEIVED)
                    .perform(new OnReceivedInstallResponseAction(speakerCommandRetriesLimit));
            builder.transition().from(State.INSTALLING_INGRESS_RULES).to(State.INGRESS_RULES_INSTALLED)
                    .on(Event.RULES_INSTALLED);
            builder.transitions().from(State.INSTALLING_INGRESS_RULES)
                    .toAmong(State.REVERTING_PATHS_SWAP, State.REVERTING_PATHS_SWAP)
                    .onEach(Event.TIMEOUT, Event.ERROR)
                    .perform(new AbandonPendingCommandsAction());

            builder.transition().from(State.INGRESS_RULES_INSTALLED).to(State.VALIDATING_INGRESS_RULES)
                    .on(Event.NEXT).perform(new EmitIngressRulesVerifyRequestsAction());
            builder.transitions().from(State.INGRESS_RULES_INSTALLED)
                    .toAmong(State.REVERTING_PATHS_SWAP, State.REVERTING_PATHS_SWAP)
                    .onEach(Event.TIMEOUT, Event.ERROR);

            builder.internalTransition().within(State.VALIDATING_INGRESS_RULES).on(Event.RESPONSE_RECEIVED)
                    .perform(new ValidateIngressRulesAction(speakerCommandRetriesLimit));
            builder.internalTransition().within(State.VALIDATING_INGRESS_RULES).on(Event.ERROR_RECEIVED)
                    .perform(new ValidateIngressRulesAction(speakerCommandRetriesLimit));
            builder.transition().from(State.VALIDATING_INGRESS_RULES).to(State.INGRESS_RULES_VALIDATED)
                    .on(Event.RULES_VALIDATED);
            builder.transitions().from(State.VALIDATING_INGRESS_RULES)
                    .toAmong(State.REVERTING_PATHS_SWAP, State.REVERTING_PATHS_SWAP, State.REVERTING_PATHS_SWAP)
                    .onEach(Event.TIMEOUT, Event.MISSING_RULE_FOUND, Event.ERROR);

            builder.transition().from(State.INGRESS_RULES_VALIDATED).to(State.REMOVING_OLD_RULES).on(Event.NEXT)
                    .perform(new RemoveOldRulesAction(persistenceManager, resourcesManager));

            builder.internalTransition().within(State.REMOVING_OLD_RULES).on(Event.RESPONSE_RECEIVED)
                    .perform(new OnReceivedRemoveOrRevertResponseAction(speakerCommandRetriesLimit));
            builder.internalTransition().within(State.REMOVING_OLD_RULES).on(Event.ERROR_RECEIVED)
                    .perform(new OnReceivedRemoveOrRevertResponseAction(speakerCommandRetriesLimit));
            builder.transitions().from(State.REMOVING_OLD_RULES)
                    .toAmong(State.OLD_RULES_REMOVED, State.OLD_RULES_REMOVED)
                    .onEach(Event.RULES_REMOVED, Event.ERROR);

            builder.onEntry(State.OLD_RULES_REMOVED).perform(reportErrorAction);
            builder.transition().from(State.OLD_RULES_REMOVED).to(State.UPDATING_FLOW_STATUS)
                    .on(Event.NEXT);

            builder.transition().from(State.UPDATING_FLOW_STATUS).to(State.FLOW_STATUS_UPDATED).on(Event.NEXT)
                    .perform(new UpdateFlowStatusAction(persistenceManager, dashboardLogger));

            builder.onEntry(State.REVERTING_PATHS_SWAP).perform(reportErrorAction);
            builder.transition().from(State.REVERTING_PATHS_SWAP).to(State.PATHS_SWAP_REVERTED)
                    .on(Event.NEXT)
                    .perform(new RevertPathsSwapAction(persistenceManager));

            builder.transition().from(State.PATHS_SWAP_REVERTED)
                    .to(State.REVERTING_NEW_RULES)
                    .on(Event.NEXT)
                    .perform(new RevertNewRulesAction(persistenceManager, resourcesManager));

            builder.internalTransition().within(State.REVERTING_NEW_RULES).on(Event.RESPONSE_RECEIVED)
                    .perform(new OnReceivedRemoveOrRevertResponseAction(speakerCommandRetriesLimit));
            builder.internalTransition().within(State.REVERTING_NEW_RULES).on(Event.ERROR_RECEIVED)
                    .perform(new OnReceivedRemoveOrRevertResponseAction(speakerCommandRetriesLimit));
            builder.transition().from(State.REVERTING_NEW_RULES).to(State.NEW_RULES_REVERTED)
                    .on(Event.RULES_REMOVED);
            builder.transition().from(State.REVERTING_NEW_RULES).to(State.NEW_RULES_REVERTED)
                    .on(Event.ERROR)
                    .perform(new HandleNotCompletedCommandsAction());

            builder.transition().from(State.NEW_RULES_REVERTED)
                    .to(State.REVERTING_FLOW_STATUS)
                    .on(Event.NEXT)
                    .perform(new RecalculateFlowStatusAction(persistenceManager,
                            dashboardLogger));

            builder.transition().from(State.REVERTING_FLOW_STATUS)
                    .to(State.NOTIFY_FLOW_MONITOR_WITH_ERROR)
                    .on(Event.NEXT);

            builder.transition().from(State.FLOW_STATUS_UPDATED).to(State.NOTIFY_FLOW_MONITOR).on(Event.NEXT);
            builder.transition().from(State.FLOW_STATUS_UPDATED)
                    .to(State.NOTIFY_FLOW_MONITOR_WITH_ERROR).on(Event.ERROR);

            builder.transition()
                    .from(State.NOTIFY_FLOW_MONITOR)
                    .to(State.FINISHED)
                    .on(Event.NEXT)
                    .perform(new NotifyFlowMonitorAction<>(persistenceManager, carrier));
            builder.transition()
                    .from(State.NOTIFY_FLOW_MONITOR_WITH_ERROR)
                    .to(State.FINISHED_WITH_ERROR)
                    .on(Event.NEXT)
                    .perform(new NotifyFlowMonitorAction<>(persistenceManager, carrier));

            builder.defineFinalState(State.FINISHED_WITH_ERROR)
                    .addEntryAction(new OnFinishedWithErrorAction(dashboardLogger));
            builder.defineFinalState(State.FINISHED)
                    .addEntryAction(new OnFinishedAction(dashboardLogger));
        }

        public FlowPathSwapFsm newInstance(CommandContext commandContext, String flowId) {
            return builder.newStateMachine(FlowPathSwapFsm.State.INITIALIZED, commandContext, carrier, flowId);
        }
    }

    public enum State {
        INITIALIZED,
        FLOW_VALIDATED,
        FLOW_UPDATED,

        INSTALLING_INGRESS_RULES,
        INGRESS_RULES_INSTALLED,
        VALIDATING_INGRESS_RULES,
        INGRESS_RULES_VALIDATED,


        REMOVING_OLD_RULES,
        OLD_RULES_REMOVED,

        OLD_PATHS_REMOVAL_COMPLETED,

        DEALLOCATING_OLD_RESOURCES,
        OLD_RESOURCES_DEALLOCATED,

        UPDATING_FLOW_STATUS,
        FLOW_STATUS_UPDATED,

        FINISHED,

        REVERTING_PATHS_SWAP,
        PATHS_SWAP_REVERTED,
        REVERTING_NEW_RULES,
        NEW_RULES_REVERTED,

        REVERTING_FLOW_STATUS,
        REVERTING_FLOW,

        FINISHED_WITH_ERROR,

        NOTIFY_FLOW_MONITOR,
        NOTIFY_FLOW_MONITOR_WITH_ERROR
    }

    public enum Event {
        NEXT,

        RESPONSE_RECEIVED,
        ERROR_RECEIVED,

        RULES_INSTALLED,
        RULES_VALIDATED,
        MISSING_RULE_FOUND,

        RULES_REMOVED,

        TIMEOUT,
        ERROR
    }
}
