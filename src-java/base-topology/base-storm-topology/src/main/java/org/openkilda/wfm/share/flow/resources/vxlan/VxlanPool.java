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

package org.openkilda.wfm.share.flow.resources.vxlan;

import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.model.Vxlan;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.wfm.share.flow.resources.EncapsulationResourcesProvider;
import org.openkilda.wfm.share.flow.resources.ResourceNotAvailableException;
import org.openkilda.wfm.share.flow.resources.ResourceUtils;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Optional;

/**
 * The resource pool is responsible for vxlan de-/allocation.
 */
@Slf4j
public class VxlanPool implements EncapsulationResourcesProvider<VxlanEncapsulation> {
    private final TransactionManager transactionManager;
    private final VxlanRepository vxlanRepository;

    private final int minVxlan;
    private final int maxVxlan;

    public VxlanPool(PersistenceManager persistenceManager, int minVxlan, int maxVxlan) {
        transactionManager = persistenceManager.getTransactionManager();
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        vxlanRepository = repositoryFactory.createVxlanRepository();

        this.minVxlan = minVxlan;
        this.maxVxlan = maxVxlan;
    }

    /**
     * Allocates a vxlan for the flow path.
     */
    @Override
    public VxlanEncapsulation allocate(Flow flow, PathId pathId, PathId oppositePathId) {
        return get(oppositePathId, null)
                .orElseGet(() -> allocate(flow, pathId));
    }

    private VxlanEncapsulation allocate(Flow flow, PathId pathId) {
        return transactionManager.doInTransaction(() -> {
            int startValue = ResourceUtils.computeStartValue(minVxlan, maxVxlan);
            Optional<Integer> availableVxlan = vxlanRepository.findMaximumAssignedVxlan()
                    .map(vxlan -> vxlan + 1)
                    .filter(vxlan -> vxlan >= startValue && vxlan <= maxVxlan);
            if (!availableVxlan.isPresent()) {
                availableVxlan = Optional.of(vxlanRepository.findFirstUnassignedVxlan(startValue))
                        .filter(vxlan -> vxlan <= maxVxlan);
            }
            if (!availableVxlan.isPresent()) {
                availableVxlan = Optional.of(vxlanRepository.findFirstUnassignedVxlan(minVxlan))
                        .filter(vxlan -> vxlan <= maxVxlan);
            }
            if (!availableVxlan.isPresent()) {
                throw new ResourceNotAvailableException("No vxlan available");
            }

            Vxlan vxlan = Vxlan.builder()
                    .vni(availableVxlan.get())
                    .flowId(flow.getFlowId())
                    .pathId(pathId)
                    .build();
            vxlanRepository.add(vxlan);

            return VxlanEncapsulation.builder()
                    .vxlan(vxlan)
                    .build();
        });
    }

    /**
     * Deallocates a vxlan of the path.
     */
    @Override
    public void deallocate(PathId pathId) {
        transactionManager.doInTransaction(() ->
                vxlanRepository.findByPathId(pathId, null)
                        .forEach(vxlanRepository::remove));
    }

    /**
     * Get allocated vxlan(s) of the flow path.
     */
    @Override
    public Optional<VxlanEncapsulation> get(PathId pathId, PathId oppositePathId) {
        Collection<Vxlan> vxlans = vxlanRepository.findByPathId(pathId, oppositePathId);
        return vxlans.stream()
                .findAny()
                .map(vxlan -> VxlanEncapsulation.builder().vxlan(vxlan).build());
    }
}
