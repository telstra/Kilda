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

package org.openkilda.wfm.topology.flowhs.fsm.common.actions;

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.topology.flow.model.FlowPathPair;
import org.openkilda.wfm.topology.flowhs.fsm.common.FlowProcessingFsm;

import lombok.extern.slf4j.Slf4j;

/**
 * A base for action classes that remove flow paths.
 */
@Slf4j
public abstract class BaseFlowPathRemovalAction<T extends FlowProcessingFsm<T, S, E, C>, S, E, C> extends
        FlowProcessingAction<T, S, E, C> {
    protected final IslRepository islRepository;

    public BaseFlowPathRemovalAction(PersistenceManager persistenceManager) {
        super(persistenceManager);

        islRepository = persistenceManager.getRepositoryFactory().createIslRepository();
    }

    protected void updateIslsForFlowPath(FlowPath... paths) {
        for (FlowPath path : paths) {
            if (!path.isIgnoreBandwidth()) {
                path.getSegments().forEach(pathSegment ->
                        transactionManager.doInTransaction(() -> {
                            updateAvailableBandwidth(pathSegment.getSrcSwitchId(), pathSegment.getSrcPort(),
                                    pathSegment.getDestSwitchId(), pathSegment.getDestPort());
                        }));
            }
        }
    }

    private void updateAvailableBandwidth(SwitchId srcSwitch, int srcPort, SwitchId dstSwitch, int dstPort) {
        log.debug("Updating ISL {}_{} - {}_{} with used bandwidth", srcSwitch, srcPort, dstSwitch, dstPort);
        islRepository.updateAvailableBandwidth(srcSwitch, srcPort, dstSwitch, dstPort);
    }

    protected void saveRemovalActionWithDumpToHistory(T stateMachine, Flow flow, FlowPath flowPath) {
        // TODO: History dumps require paired paths, fix it to support any (without opposite one).
        FlowPathPair pathsToDelete = FlowPathPair.builder().forward(flowPath).reverse(flowPath).build();

        saveRemovalActionWithDumpToHistory(stateMachine, flow, pathsToDelete);
    }

    protected void saveRemovalActionWithDumpToHistory(T stateMachine, Flow flow, FlowPathPair pathPair) {
        FlowDumpData flowDumpData =
                HistoryMapper.INSTANCE.map(flow, pathPair.getForward(), pathPair.getReverse(), DumpType.STATE_BEFORE);
        stateMachine.saveActionWithDumpToHistory("Flow paths were removed",
                format("The flow paths %s / %s were removed", pathPair.getForwardPathId(), pathPair.getReversePathId()),
                flowDumpData);
    }
}
