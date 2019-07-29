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

import org.openkilda.model.FlowMeter;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The resource pool is responsible for meter de-/allocation.
 */
@Slf4j
public class MeterPool {
    private final TransactionManager transactionManager;
    private final FlowMeterRepository flowMeterRepository;
    private final SwitchRepository switchRepository;

    private final MeterId minMeterId;
    private final MeterId maxMeterId;

    public MeterPool(PersistenceManager persistenceManager, MeterId minMeterId, MeterId maxMeterId) {
        transactionManager = persistenceManager.getTransactionManager();
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        flowMeterRepository = repositoryFactory.createFlowMeterRepository();
        switchRepository = repositoryFactory.createSwitchRepository();

        this.minMeterId = minMeterId;
        this.maxMeterId = maxMeterId;
    }

    /**
     * Allocates a meter for the flow path.
     */
    public MeterId allocate(SwitchId switchId, String flowId, PathId pathId) {
        return transactionManager.doInTransaction(() -> {
            Switch theSwitch = switchRepository.findById(switchId)
                    .orElseThrow(() ->
                            new ResourceNotAvailableException(format("No switch for meter allocation: %s", switchId)));
            return allocate(theSwitch, flowId, pathId);
        });
    }

    /**
     * Allocates a meter for the flow path.
     */
    public MeterId allocate(Switch theSwitch, String flowId, PathId pathId) {
        return transactionManager.doInTransaction(() -> {
            MeterId startMeterId = new MeterId(
                    ResourceUtils.computeStartValue(minMeterId.getValue(), maxMeterId.getValue()));
            Optional<MeterId> availableMeterId = flowMeterRepository.findMaximumAssignedMeter(theSwitch.getSwitchId())
                    .map(meterId -> new MeterId(meterId.getValue() + 1))
                    .filter(meterId -> meterId.compareTo(startMeterId) >= 0 && meterId.compareTo(maxMeterId) <= 0);
            if (!availableMeterId.isPresent()) {
                availableMeterId =
                        Optional.of(flowMeterRepository.findFirstUnassignedMeter(theSwitch.getSwitchId(), startMeterId))
                                .filter(meterId -> meterId.compareTo(maxMeterId) <= 0);
            }
            if (!availableMeterId.isPresent()) {
                availableMeterId =
                        Optional.of(flowMeterRepository.findFirstUnassignedMeter(theSwitch.getSwitchId(), minMeterId))
                                .filter(meterId -> meterId.compareTo(maxMeterId) <= 0);
            }
            if (!availableMeterId.isPresent()) {
                throw new ResourceNotAvailableException(format("No meter available for switch %s", theSwitch));
            }

            FlowMeter flowMeter = FlowMeter.builder()
                    .meterId(availableMeterId.get())
                    .switchId(theSwitch.getSwitchId())
                    .flowId(flowId)
                    .pathId(pathId)
                    .build();
            flowMeterRepository.add(flowMeter);

            return flowMeter.getMeterId();
        });
    }

    /**
     * Deallocates a meter(s) of the flow path(s).
     */
    public void deallocate(PathId... pathIds) {
        transactionManager.doInTransaction(() -> {
            List<FlowMeter> meters = Arrays.stream(pathIds)
                    .map(flowMeterRepository::findByPathId)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(toList());

            meters.forEach(flowMeterRepository::remove);
        });
    }
}
