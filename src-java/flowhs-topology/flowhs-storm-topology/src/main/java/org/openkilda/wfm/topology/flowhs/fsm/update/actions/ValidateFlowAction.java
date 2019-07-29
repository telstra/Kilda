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

import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowStatus;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.history.model.FlowEventData;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;
import org.openkilda.wfm.topology.flowhs.exception.FlowProcessingException;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.NbTrackableAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;
import org.openkilda.wfm.topology.flowhs.validation.FlowValidator;
import org.openkilda.wfm.topology.flowhs.validation.InvalidFlowException;
import org.openkilda.wfm.topology.flowhs.validation.UnavailableFlowEndpointException;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class ValidateFlowAction extends NbTrackableAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    private final FeatureTogglesRepository featureTogglesRepository;
    private final FlowValidator flowValidator;
    private final FlowOperationsDashboardLogger dashboardLogger;

    public ValidateFlowAction(PersistenceManager persistenceManager, FlowOperationsDashboardLogger dashboardLogger) {
        super(persistenceManager);
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        SwitchRepository switchRepository = repositoryFactory.createSwitchRepository();
        IslRepository islRepository = repositoryFactory.createIslRepository();
        SwitchPropertiesRepository switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        flowValidator = new FlowValidator(flowRepository, switchRepository, islRepository, switchPropertiesRepository);
        this.dashboardLogger = dashboardLogger;
    }

    @Override
    protected Optional<Message> performWithResponse(State from, State to, Event event, FlowUpdateContext context,
                                                    FlowUpdateFsm stateMachine) {
        String flowId = stateMachine.getFlowId();
        RequestedFlow targetFlow = context.getTargetFlow();

        dashboardLogger.onFlowUpdate(flowId,
                targetFlow.getSrcSwitch(), targetFlow.getSrcPort(), targetFlow.getSrcVlan(),
                targetFlow.getDestSwitch(), targetFlow.getDestPort(), targetFlow.getDestVlan(),
                targetFlow.getDiverseFlowId(), targetFlow.getBandwidth());

        boolean isOperationAllowed = featureTogglesRepository.getOrDefault().getUpdateFlowEnabled();
        if (!isOperationAllowed) {
            throw new FlowProcessingException(ErrorType.NOT_PERMITTED, "Flow update feature is disabled");
        }

        stateMachine.setTargetFlow(targetFlow);

        try {
            flowValidator.validate(targetFlow);
        } catch (InvalidFlowException e) {
            throw new FlowProcessingException(e.getType(), e.getMessage(), e);
        } catch (UnavailableFlowEndpointException e) {
            throw new FlowProcessingException(ErrorType.DATA_INVALID, e.getMessage(), e);
        }

        if (targetFlow.getDiverseFlowId() != null
                && targetFlow.getSrcSwitch().equals(targetFlow.getDestSwitch())) {
            throw new FlowProcessingException(ErrorType.DATA_INVALID,
                    "Couldn't add one-switch flow into diverse group");
        }

        persistenceManager.getTransactionManager().doInTransaction(() -> {
            if (targetFlow.getDiverseFlowId() != null) {
                Flow diverseFlow = getFlow(targetFlow.getDiverseFlowId());
                if (diverseFlow.isOneSwitchFlow()) {
                    throw new FlowProcessingException(ErrorType.PARAMETERS_INVALID,
                            "Couldn't create diverse group with one-switch flow");
                }
            }

            Flow foundFlow = getFlow(flowId);
            if (foundFlow.getStatus() == FlowStatus.IN_PROGRESS) {
                throw new FlowProcessingException(ErrorType.REQUEST_INVALID,
                        format("Flow %s is in progress now", flowId));
            }

            // Keep it, just in case we have to revert it.
            stateMachine.setOriginalFlowStatus(foundFlow.getStatus());

            foundFlow.setStatus(FlowStatus.IN_PROGRESS);
            return foundFlow;
        });

        stateMachine.saveNewEventToHistory("Flow was validated successfully", FlowEventData.Event.UPDATE);

        return Optional.empty();
    }

    @Override
    protected String getGenericErrorMessage() {
        return "Could not update flow";
    }
}
