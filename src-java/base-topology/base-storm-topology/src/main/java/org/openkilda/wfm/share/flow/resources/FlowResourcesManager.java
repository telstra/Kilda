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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.transitvlan.TransitVlanPool;
import org.openkilda.wfm.share.flow.resources.vxlan.VxlanPool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
public class FlowResourcesManager {
    private static final int POOL_SIZE = 100;
    private static final int MAX_ALLOCATION_ATTEMPTS = 5;

    private final TransactionManager transactionManager;

    private final CookiePool cookiePool;
    private final MeterPool meterPool;
    private final Map<FlowEncapsulationType, EncapsulationResourcesProvider> encapsulationResourcesProviders;

    public FlowResourcesManager(PersistenceManager persistenceManager, FlowResourcesConfig config) {
        transactionManager = persistenceManager.getTransactionManager();

        this.cookiePool = new CookiePool(persistenceManager, config.getMinFlowCookie(), config.getMaxFlowCookie(),
                POOL_SIZE);
        this.meterPool = new MeterPool(persistenceManager,
                new MeterId(config.getMinFlowMeterId()), new MeterId(config.getMaxFlowMeterId()), POOL_SIZE);

        encapsulationResourcesProviders = ImmutableMap.<FlowEncapsulationType, EncapsulationResourcesProvider>builder()
                .put(FlowEncapsulationType.TRANSIT_VLAN, new TransitVlanPool(persistenceManager,
                        config.getMinFlowTransitVlan(), config.getMaxFlowTransitVlan(), POOL_SIZE))
                .put(FlowEncapsulationType.VXLAN, new VxlanPool(persistenceManager,
                        config.getMinFlowVxlan(), config.getMaxFlowVxlan(), POOL_SIZE))
                .build();
    }

    /**
     * Try to allocate resources for the flow paths. The method doesn't initialize a transaction.
     * So it requires external transaction to cover allocation failures.
     * <p/>
     * Provided two flows are considered as paired (forward and reverse),
     * so some resources can be shared among them.
     */
    public FlowResources allocateFlowResources(Flow flow) throws ResourceAllocationException {
        PathId forwardPathId = generatePathId(flow.getFlowId());
        PathId reversePathId = generatePathId(flow.getFlowId());
        return allocateFlowResources(flow, forwardPathId, reversePathId);
    }

    /**
     * Try to allocate resources for the flow paths. The method doesn't initialize a transaction.
     * So it requires external transaction to cover allocation failures.
     * <p/>
     * Provided two flows are considered as paired (forward and reverse),
     * so some resources can be shared among them.
     */
    public FlowResources allocateFlowResources(Flow flow, PathId forwardPathId, PathId reversePathId)
            throws ResourceAllocationException {
        log.debug("Allocate flow resources for {}.", flow);

        try {
            return allocateResources(flow, forwardPathId, reversePathId);
        } catch (ConstraintViolationException | ResourceNotAvailableException ex) {
            throw new ResourceAllocationException("Unable to allocate resources", ex);
        }
    }

    @VisibleForTesting
    FlowResources allocateResources(Flow flow, PathId forwardPathId, PathId reversePathId) {
        PathResources.PathResourcesBuilder forward = PathResources.builder()
                .pathId(forwardPathId);
        PathResources.PathResourcesBuilder reverse = PathResources.builder()
                .pathId(reversePathId);

        if (flow.getBandwidth() > 0L) {
            forward.meterId(meterPool.allocate(flow.getSrcSwitchId(), flow.getFlowId(), forwardPathId));
            reverse.meterId(meterPool.allocate(flow.getDestSwitchId(), flow.getFlowId(), reversePathId));
        }

        if (!flow.isOneSwitchFlow()) {
            EncapsulationResourcesProvider encapsulationResourcesProvider =
                    getEncapsulationResourcesProvider(flow.getEncapsulationType());
            forward.encapsulationResources(
                    encapsulationResourcesProvider.allocate(flow, forwardPathId, reversePathId));

            reverse.encapsulationResources(
                    encapsulationResourcesProvider.allocate(flow, reversePathId, forwardPathId));
        }

        return FlowResources.builder()
                .unmaskedCookie(cookiePool.allocate(flow.getFlowId()))
                .forward(forward.build())
                .reverse(reverse.build())
                .build();
    }

    private EncapsulationResourcesProvider getEncapsulationResourcesProvider(FlowEncapsulationType type) {
        EncapsulationResourcesProvider provider = encapsulationResourcesProviders.get(type);
        if (provider == null) {
            throw new ResourceNotAvailableException(
                    format("Unsupported encapsulation type %s", type));
        }
        return provider;
    }

    public PathId generatePathId(String flowId) {
        return new PathId(format("%s_%s", flowId, UUID.randomUUID()));
    }

    /**
     * Deallocate the flow path resources.
     * <p/>
     * Shared resources are to be deallocated with no usage checks.
     */
    public void deallocatePathResources(PathId pathId, long unmaskedCookie, FlowEncapsulationType encapsulationType) {
        log.debug("Deallocate flow resources for path {}, cookie: {}.", pathId, unmaskedCookie);

        transactionManager.doInTransaction(() -> {
            cookiePool.deallocate(unmaskedCookie);
            meterPool.deallocate(pathId);

            EncapsulationResourcesProvider encapsulationResourcesProvider =
                    getEncapsulationResourcesProvider(encapsulationType);
            encapsulationResourcesProvider.deallocate(pathId);
        });
    }

    /**
     * Deallocate the flow path resources.
     * <p/>
     * Shared resources are to be deallocated with no usage checks.
     */
    public void deallocatePathResources(FlowResources resources) {
        log.debug("Deallocate flow resources {}.", resources);

        transactionManager.doInTransaction(() -> {
            cookiePool.deallocate(resources.getUnmaskedCookie());

            meterPool.deallocate(resources.getForward().getPathId(), resources.getReverse().getPathId());

            Stream.of(resources.getForward(), resources.getReverse())
                    .forEach(path -> {
                        EncapsulationResources encapsulationResources = path.getEncapsulationResources();
                        if (encapsulationResources != null) {
                            getEncapsulationResourcesProvider(encapsulationResources.getEncapsulationType())
                                    .deallocate(path.getPathId());
                        }
                    });
        });
    }

    /**
     * Get allocated encapsulation resources of the flow path.
     */
    public Optional<EncapsulationResources> getEncapsulationResources(PathId pathId,
                                                                      PathId oppositePathId,
                                                                      FlowEncapsulationType encapsulationType) {
        return getEncapsulationResourcesProvider(encapsulationType).get(pathId, oppositePathId);
    }
}
