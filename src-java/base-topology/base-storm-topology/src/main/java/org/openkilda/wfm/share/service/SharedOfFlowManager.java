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

package org.openkilda.wfm.share.service;

import static java.lang.String.format;

import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.SharedOfFlow;
import org.openkilda.model.SharedOfFlow.SharedOfFlowType;
import org.openkilda.model.cookie.SharedOfFlowCookieSchema;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SharedOfFlowRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.model.SharedOfFlowStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class SharedOfFlowManager {
    private final TransactionManager transactionManager;
    private final SwitchRepository switchRepository;
    private final SharedOfFlowRepository sharedOfFlowRepository;

    private final List<PathId> referencesToRemove;

    public SharedOfFlowManager(PersistenceManager persistenceManager) {
        this(persistenceManager, Collections.emptyList());
    }

    public SharedOfFlowManager(PersistenceManager persistenceManager, PathId... referencesToRemove) {
        this(persistenceManager, Arrays.asList(referencesToRemove));
    }

    public SharedOfFlowManager(PersistenceManager persistenceManager, List<PathId> referencesToRemove) {
        transactionManager = persistenceManager.getTransactionManager();

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        sharedOfFlowRepository = repositoryFactory.createSharedOfFlowRepository();
        
        this.referencesToRemove = new ArrayList<>(referencesToRemove);
    }

    /**
     * Make new reference(if not exists now) between {@code FlowPath} and {@code SharedOfFlow}. Must be called inside
     * transaction responsible for path modification.
     */
    public SharedOfFlowStatus bindIngressFlowSegmentOuterVlanMatchFlow(FlowEndpoint ingressEndpoint, FlowPath path)
            throws ResourceAllocationException {
        SharedOfFlowCookieSchema cookie = new SharedOfFlowCookieSchema()
                .setUniqueIdField(ingressEndpoint.getPortNumber(), ingressEndpoint.getOuterVlanId());
        SharedOfFlow sharedFlow = SharedOfFlow.builder()
                .switchObj(findSwitch(ingressEndpoint.getSwitchId()))
                .cookie(cookie)
                .type(SharedOfFlowType.INGRESS_OUTER_VLAN_MATCH)
                .build();
        sharedFlow = loadOrCreate(sharedFlow);

        SharedOfFlowStatus status = evaluateStatus(sharedFlow, null);
        path.getSharedOfFlows().add(sharedFlow);

        return status;
    }

    /**
     * Remove reference(if exists) between {@code FlowPath} and {@code SharedOfFlow}. Must be called inside transaction
     * responsible for path modification.
     */
    public SharedOfFlowStatus removeBinding(SharedOfFlow sharedFlow, FlowPath path) {
        path.getSharedOfFlows().remove(sharedFlow);
        return evaluateStatus(sharedFlow, path.getPathId());
    }

    private SharedOfFlowStatus evaluateStatus(SharedOfFlow sharedFlow, PathId ignorePath) {
        List<PathId> ignoreReferences = referencesToRemove;
        if (ignorePath != null) {
            ignoreReferences = new ArrayList<>(ignoreReferences);
            ignoreReferences.add(ignorePath);
        }

        long referenceCount = sharedOfFlowRepository.countReferences(sharedFlow, ignoreReferences);
        return new SharedOfFlowStatus(sharedFlow, referenceCount);
    }

    private Switch findSwitch(SwitchId switchId) {
        return switchRepository.findById(switchId)
                .orElseThrow(() -> new PersistenceException(format("Switch not found: %s", switchId)));
    }

    private SharedOfFlow loadOrCreate(SharedOfFlow sharedFlowReference) throws ResourceAllocationException {
        Optional<SharedOfFlow> potentialEntity = sharedOfFlowRepository
                .findByUniqueIndex(sharedFlowReference);
        if (potentialEntity.isPresent()) {
            return potentialEntity.get();
        }

        try {
            transactionManager.doInTransaction(() -> sharedOfFlowRepository.createOrUpdate(sharedFlowReference));
        } catch (ConstraintViolationException e) {
            throw new ResourceAllocationException(format(
                    "Collision during allocation shared OF flow record for %s", sharedFlowReference));
        }
        return sharedFlowReference;
    }
}
