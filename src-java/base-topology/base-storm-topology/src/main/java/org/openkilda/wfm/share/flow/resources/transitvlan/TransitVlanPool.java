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

package org.openkilda.wfm.share.flow.resources.transitvlan;

import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.model.TransitVlan;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.wfm.share.flow.resources.EncapsulationResourcesProvider;
import org.openkilda.wfm.share.flow.resources.ResourceNotAvailableException;
import org.openkilda.wfm.share.flow.resources.ResourceUtils;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Optional;

/**
 * The resource pool is responsible for transit vlan de-/allocation.
 */
@Slf4j
public class TransitVlanPool implements EncapsulationResourcesProvider<TransitVlanEncapsulation> {
    private final TransactionManager transactionManager;
    private final TransitVlanRepository transitVlanRepository;

    private final int minTransitVlan;
    private final int maxTransitVlan;

    public TransitVlanPool(PersistenceManager persistenceManager, int minTransitVlan, int maxTransitVlan) {
        transactionManager = persistenceManager.getTransactionManager();
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        transitVlanRepository = repositoryFactory.createTransitVlanRepository();

        this.minTransitVlan = minTransitVlan;
        this.maxTransitVlan = maxTransitVlan;
    }

    /**
     * Allocates a vlan for the flow path.
     */
    @Override
    public TransitVlanEncapsulation allocate(Flow flow, PathId pathId, PathId oppositePathId) {
        return get(oppositePathId, null)
                .orElseGet(() -> allocate(flow, pathId));
    }

    private TransitVlanEncapsulation allocate(Flow flow, PathId pathId) {
        return transactionManager.doInTransaction(() -> {
            int startValue = ResourceUtils.computeStartValue(minTransitVlan, maxTransitVlan);
            Optional<Integer> availableVlan = transitVlanRepository.findMaximumAssignedVlan()
                    .map(vlan -> vlan + 1)
                    .filter(vlan -> vlan >= startValue && vlan <= maxTransitVlan);
            if (!availableVlan.isPresent()) {
                availableVlan = Optional.of(transitVlanRepository.findFirstUnassignedVlan(startValue))
                        .filter(vlan -> vlan <= maxTransitVlan);
            }
            if (!availableVlan.isPresent()) {
                availableVlan = Optional.of(transitVlanRepository.findFirstUnassignedVlan(minTransitVlan))
                        .filter(vlan -> vlan <= maxTransitVlan);
            }
            if (!availableVlan.isPresent()) {
                throw new ResourceNotAvailableException("No vlan available");
            }

            TransitVlan transitVlan = TransitVlan.builder()
                    .vlan(availableVlan.get())
                    .flowId(flow.getFlowId())
                    .pathId(pathId)
                    .build();
            transitVlanRepository.add(transitVlan);

            return TransitVlanEncapsulation.builder()
                    .transitVlan(transitVlan)
                    .build();
        });
    }

    /**
     * Deallocates a vlan of the path.
     */
    @Override
    public void deallocate(PathId pathId) {
        transactionManager.doInTransaction(() ->
                transitVlanRepository.findByPathId(pathId, null)
                        .forEach(transitVlanRepository::remove));
    }

    /**
     * Get allocated transit vlan(s) of the flow path.
     */
    @Override
    public Optional<TransitVlanEncapsulation> get(PathId pathId, PathId oppositePathId) {
        Collection<TransitVlan> transitVlans = transitVlanRepository.findByPathId(pathId, oppositePathId);
        return transitVlans.stream()
                .findAny()
                .map(transitVlan -> TransitVlanEncapsulation.builder().transitVlan(transitVlan).build());
    }
}
