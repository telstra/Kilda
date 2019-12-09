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

import org.openkilda.model.ExclusionId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.ExclusionIdRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;

import java.util.Collection;

/**
 * The resource pool is responsible for exclusion id de-/allocation.
 */
public class ExclusionIdPool {
    private static final int MIN_EXCLUSION_ID = 1;
    private static final int MAX_EXCLUSION_ID = 0xFFFF;

    private final TransactionManager transactionManager;
    private final ExclusionIdRepository exclusionIdRepository;

    public ExclusionIdPool(PersistenceManager persistenceManager) {
        transactionManager = persistenceManager.getTransactionManager();
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        exclusionIdRepository = repositoryFactory.createExclusionIdRepository();
    }

    /**
     * Allocates a an exclusion id for the flow.
     */
    public int allocate(String flowId) {
        return transactionManager.doInTransaction(() -> {
            String noFlowErrorMessage = format("No exclusion id available for flow %s", flowId);

            Integer availableExclusionId = exclusionIdRepository.findUnassignedExclusionId(flowId, MIN_EXCLUSION_ID)
                    .orElseThrow(() -> new ResourceNotAvailableException(noFlowErrorMessage));
            if (availableExclusionId.compareTo(MAX_EXCLUSION_ID) > 0) {
                throw new ResourceNotAvailableException(noFlowErrorMessage);
            }

            ExclusionId exclusionId = ExclusionId.builder()
                    .flowId(flowId)
                    .id(availableExclusionId)
                    .build();
            exclusionIdRepository.createOrUpdate(exclusionId);

            return availableExclusionId;
        });
    }

    /**
     * Deallocates an exclusion ids of the flow.
     */
    public void deallocate(String flowId) {
        transactionManager.doInTransaction(() -> exclusionIdRepository.findByFlowId(flowId)
                .forEach(exclusionIdRepository::delete));
    }

    /**
     * Deallocates an exclusion id of the flow.
     */
    public void deallocate(String flowId, int exclusionId) {
        transactionManager.doInTransaction(() -> exclusionIdRepository.find(flowId, exclusionId)
                .ifPresent(exclusionIdRepository::delete));
    }

    /**
     * Get allocated exclusion ids of the flow.
     */
    public Collection<ExclusionId> get(String flowId) {
        return exclusionIdRepository.findByFlowId(flowId);
    }
}
