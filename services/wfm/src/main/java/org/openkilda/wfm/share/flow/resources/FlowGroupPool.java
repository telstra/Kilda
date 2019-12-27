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

package org.openkilda.wfm.share.flow.resources;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

import org.openkilda.model.FlowGroup;
import org.openkilda.model.GroupId;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowGroupRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * The resource pool is responsible for flow group de-/allocation.
 */
@Slf4j
public class FlowGroupPool {
    private final TransactionManager transactionManager;
    private final FlowGroupRepository flowGroupRepository;
    private final SwitchRepository switchRepository;

    private final GroupId minGroupId;
    private final GroupId maxGroupId;

    public FlowGroupPool(PersistenceManager persistenceManager, GroupId minGroupId, GroupId maxGroupId) {
        transactionManager = persistenceManager.getTransactionManager();
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        flowGroupRepository = repositoryFactory.createFlowGroupRepository();
        switchRepository = repositoryFactory.createSwitchRepository();

        this.minGroupId = minGroupId;
        this.maxGroupId = maxGroupId;
    }

    /**
     * Allocates a group for the flow path.
     */
    public GroupId allocate(SwitchId switchId, String flowId, PathId pathId) {
        return transactionManager.doInTransaction(() -> {
            Switch theSwitch = switchRepository.findById(switchId)
                    .orElseThrow(() ->
                            new ResourceNotAvailableException(format("No switch for group allocation: %s", switchId)));
            return allocate(theSwitch, flowId, pathId);
        });
    }

    /**
     * Allocates a group for the flow path.
     */
    public GroupId allocate(Switch theSwitch, String flowId, PathId pathId) {
        return transactionManager.doInTransaction(() -> {
            String noGroupErrorMessage = format("No group id available for switch %s", theSwitch);

            GroupId availableGroupId = flowGroupRepository.findUnassignedGroupId(theSwitch.getSwitchId(), minGroupId)
                    .orElseThrow(() -> new ResourceNotAvailableException(noGroupErrorMessage));
            if (availableGroupId.compareTo(maxGroupId) > 0) {
                throw new ResourceNotAvailableException(noGroupErrorMessage);
            }

            FlowGroup flowGroup = FlowGroup.builder()
                    .groupId(availableGroupId)
                    .switchId(theSwitch.getSwitchId())
                    .flowId(flowId)
                    .pathId(pathId)
                    .build();
            flowGroupRepository.createOrUpdate(flowGroup);

            return flowGroup.getGroupId();
        });
    }

    /**
     * Deallocates a group(s) of the flow path(s).
     */
    public void deallocate(PathId... pathIds) {
        transactionManager.doInTransaction(() -> {
            List<FlowGroup> group = Arrays.stream(pathIds)
                    .map(flowGroupRepository::findByPathId)
                    .flatMap(Collection::stream)
                    .collect(toList());

            group.forEach(flowGroupRepository::delete);
        });
    }
}
