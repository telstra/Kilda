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

package org.openkilda.wfm.topology.flowhs.fsm.delete.actions;

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.RecoverablePersistenceException;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.share.history.model.FlowHistoryData;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.topology.flowhs.fsm.common.action.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteContext;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.delete.FlowDeleteFsm.State;

import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.RetryPolicy;
import org.neo4j.driver.v1.exceptions.ClientException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class CompleteFlowPathRemovalAction extends
        FlowProcessingAction<FlowDeleteFsm, State, Event, FlowDeleteContext> {
    private final int transactionRetriesLimit;

    private final IslRepository islRepository;

    public CompleteFlowPathRemovalAction(PersistenceManager persistenceManager, int transactionRetriesLimit) {
        super(persistenceManager);
        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
        this.transactionRetriesLimit = transactionRetriesLimit;
    }

    @Override
    protected void perform(State from, State to, Event event, FlowDeleteContext context, FlowDeleteFsm stateMachine) {
        RetryPolicy retryPolicy = new RetryPolicy()
                .retryOn(RecoverablePersistenceException.class)
                .retryOn(ClientException.class)
                .withMaxRetries(transactionRetriesLimit);

        persistenceManager.getTransactionManager().doInTransaction(retryPolicy, () -> {
            Flow flow = getFlow(stateMachine.getFlowId());
            FlowPath[] paths = flow.getPaths().stream().filter(Objects::nonNull).toArray(FlowPath[]::new);

            flowPathRepository.lockInvolvedSwitches(paths);

            Set<PathId> processed = new HashSet<>();
            for (FlowPath path : paths) {
                PathId pathId = path.getPathId();
                if (processed.add(pathId)) {
                    FlowPath oppositePath = flow.getOppositePathId(pathId)
                            .filter(oppPathId -> !pathId.equals(oppPathId)).flatMap(flow::getPath).orElse(null);
                    if (oppositePath != null && processed.add(oppositePath.getPathId())) {
                        log.debug("Removing the flow paths {} / {}", pathId, oppositePath.getPathId());
                        flowPathRepository.delete(path);
                        flowPathRepository.delete(oppositePath);
                        updateIslsForFlowPath(path, oppositePath);

                        FlowPath forward = path.isForward() ? path : oppositePath;
                        FlowPath reverse = path.isForward() ? oppositePath : path;
                        saveHistory(stateMachine, flow.getFlowId(), forward.getPathId(), reverse.getPathId());
                    } else {
                        log.debug("Removing the flow path {}", pathId);
                        flowPathRepository.delete(path);
                        updateIslsForFlowPath(path);

                        // TODO: History dumps require paired paths, fix it to support any (without opposite one).
                        saveHistory(stateMachine, flow.getFlowId(), path.getPathId(), path.getPathId());
                    }
                }
            }
        });
    }

    private void updateIslsForFlowPath(FlowPath... paths) {
        for (FlowPath path : paths) {
            path.getSegments().forEach(pathSegment -> {
                log.debug("Updating ISL for the path segment: {}", pathSegment);

                updateAvailableBandwidth(pathSegment.getSrcSwitch().getSwitchId(), pathSegment.getSrcPort(),
                        pathSegment.getDestSwitch().getSwitchId(), pathSegment.getDestPort());
            });
        }
    }

    private void updateAvailableBandwidth(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort) {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(srcSwitch, srcPort,
                dstSwitch, dstPort);
        log.debug("Updating ISL {}_{}-{}_{} with used bandwidth {}", srcSwitch, srcPort, dstSwitch, dstPort,
                usedBandwidth);
        islRepository.updateAvailableBandwidth(srcSwitch, srcPort, dstSwitch, dstPort, usedBandwidth);
    }

    private void saveHistory(FlowDeleteFsm stateMachine, String flowId, PathId forwardPath, PathId reversePath) {
        FlowHistoryHolder historyHolder = FlowHistoryHolder.builder()
                .taskId(stateMachine.getCommandContext().getCorrelationId())
                .flowHistoryData(FlowHistoryData.builder()
                        .action("Flow paths were removed")
                        .time(Instant.now())
                        .description(format("Flow paths %s / %s were removed",
                                forwardPath, reversePath))
                        .flowId(flowId)
                        .build())
                .build();
        stateMachine.getCarrier().sendHistoryUpdate(historyHolder);
    }
}
