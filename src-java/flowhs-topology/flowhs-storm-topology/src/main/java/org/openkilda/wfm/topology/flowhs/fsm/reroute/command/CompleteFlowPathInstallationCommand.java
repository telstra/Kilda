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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.command;

import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.wfm.ICommand;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.CompleteFlowPathInstallationResponse;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.response.CompleteFlowPathInstallationResponse.CompleteFlowPathInstallationResponseBuilder;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;
import org.openkilda.wfm.topology.flowhs.service.SpeakerWorkerService;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@ToString
public class CompleteFlowPathInstallationCommand extends DbCommand implements ICommand<SpeakerWorkerService> {
    boolean ignoreBandwidth;
    PathComputationStrategy targetPathComputationStrategy;
    PathId newPrimaryForwardPath;
    PathId newPrimaryReversePath;
    PathId newProtectedForwardPath;
    PathId newProtectedReversePath;
    PathComputationStrategy newPrimaryPathComputationStrategy;
    PathComputationStrategy newProtectedPathComputationStrategy;

    public CompleteFlowPathInstallationCommand(
            @NonNull MessageContext messageContext, @NonNull UUID commandId, boolean ignoreBandwidth,
            PathComputationStrategy targetPathComputationStrategy, PathId newPrimaryForwardPath,
            PathId newPrimaryReversePath, PathId newProtectedForwardPath, PathId newProtectedReversePath,
            PathComputationStrategy newPrimaryPathComputationStrategy,
            PathComputationStrategy newProtectedPathComputationStrategy) {
        super(messageContext, commandId);
        this.ignoreBandwidth = ignoreBandwidth;
        this.targetPathComputationStrategy = targetPathComputationStrategy;
        this.newPrimaryForwardPath = newPrimaryForwardPath;
        this.newPrimaryReversePath = newPrimaryReversePath;
        this.newProtectedForwardPath = newProtectedForwardPath;
        this.newProtectedReversePath = newProtectedReversePath;
        this.newPrimaryPathComputationStrategy = newPrimaryPathComputationStrategy;
        this.newProtectedPathComputationStrategy = newProtectedPathComputationStrategy;
    }

    @Override
    public void apply(SpeakerWorkerService service) {
        CompleteFlowPathInstallationResponseBuilder responseBuilder = CompleteFlowPathInstallationResponse.builder()
                .messageContext(messageContext)
                .commandId(commandId);
        final long totalTime = System.currentTimeMillis();

        if (newPrimaryForwardPath != null && newPrimaryReversePath != null) {
            log.debug("Completing installation of the flow primary path {} / {}",
                    newPrimaryForwardPath, newPrimaryReversePath);
            FlowPathStatus targetPathStatus;
            if (ignoreBandwidth
                    || !targetPathComputationStrategy.equals(newPrimaryPathComputationStrategy)) {
                targetPathStatus = FlowPathStatus.DEGRADED;
            } else {
                targetPathStatus = FlowPathStatus.ACTIVE;
            }

            final long time = System.currentTimeMillis();

            service.getTransactionManager().doInTransaction(() -> {
                service.getFlowPathRepository().updateStatus(newPrimaryForwardPath, targetPathStatus);
                service.getFlowPathRepository().updateStatus(newPrimaryReversePath, targetPathStatus);
            });
            log.warn("HSTIME reroute complete primary flow path installing " + (System.currentTimeMillis() - time));

            responseBuilder.newPrimaryForwardPath(newPrimaryForwardPath);
            responseBuilder.newPrimaryReversePath(newPrimaryReversePath);
        }

        if (newProtectedForwardPath != null && newProtectedReversePath != null) {
            FlowPathStatus targetPathStatus;
            if (ignoreBandwidth
                    || !targetPathComputationStrategy.equals(newProtectedPathComputationStrategy)) {
                targetPathStatus = FlowPathStatus.DEGRADED;
            } else {
                targetPathStatus = FlowPathStatus.ACTIVE;
            }
            log.debug("Completing installation of the flow protected path {} / {}",
                    newProtectedForwardPath, newProtectedReversePath);

            final long time = System.currentTimeMillis();
            service.getTransactionManager().doInTransaction(() -> {
                service.getFlowPathRepository().updateStatus(newProtectedForwardPath, targetPathStatus);
                service.getFlowPathRepository().updateStatus(newProtectedReversePath, targetPathStatus);
            });

            log.warn("HSTIME reroute complete protected flow path installing " + (System.currentTimeMillis() - time));
            responseBuilder.newProtectedForwardPath(newProtectedForwardPath);
            responseBuilder.newProtectedReversePath(newProtectedReversePath);
        }

        log.warn("HSTIME reroute TOTAL complete flow path installation " + (System.currentTimeMillis() - totalTime));

        service.sendResponse(responseBuilder.build());
    }
}
