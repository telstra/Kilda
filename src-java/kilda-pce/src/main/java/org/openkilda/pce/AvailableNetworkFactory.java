/* Copyright 2018 Telstra Open Source
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

package org.openkilda.pce;

import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.impl.AvailableNetwork;
import org.openkilda.pce.model.Edge;
import org.openkilda.pce.model.Node;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.IslRepository.IslImmutableView;
import org.openkilda.persistence.repositories.RepositoryFactory;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A factory for {@link AvailableNetwork} instances.
 */
@Slf4j
public class AvailableNetworkFactory {
    private PathComputerConfig config;
    private IslRepository islRepository;
    private FlowPathRepository flowPathRepository;

    public AvailableNetworkFactory(PathComputerConfig config, RepositoryFactory repositoryFactory) {
        this.config = config;
        this.islRepository = repositoryFactory.createIslRepository();
        this.flowPathRepository = repositoryFactory.createFlowPathRepository();
    }

    /**
     * Gets a {@link AvailableNetwork}.
     *
     * @param flow                      the flow, for which {@link AvailableNetwork} is constructing.
     * @param reusePathsResources       reuse resources already allocated by {@param reusePathsResources} paths.
     * @return {@link AvailableNetwork} instance.
     */
    public AvailableNetwork getAvailableNetwork(Flow flow, Collection<PathId> reusePathsResources)
            throws RecoverableException {
        BuildStrategy buildStrategy = BuildStrategy.from(config.getNetworkStrategy());
        AvailableNetwork network = new AvailableNetwork();
        try {
            // Reads all active links from the database and creates representation of the network.
            getAvailableIsls(buildStrategy, flow)
                    .forEach(link -> addIslAsEdge(link, network));

            if (!reusePathsResources.isEmpty() && !flow.isIgnoreBandwidth()) {
                reusePathsResources.stream()
                        .filter(pathId -> flowPathRepository.findById(pathId)
                                .map(path -> !path.isIgnoreBandwidth())
                                .orElse(false))
                        .forEach(pathId -> {
                            // ISLs occupied by the flow (take the bandwidth already occupied by the flow into account).
                            islRepository.findActiveByPathAndBandwidthAndEncapsulationType(
                                            pathId, flow.getBandwidth(), flow.getEncapsulationType())
                                    .forEach(link -> addIslAsEdge(link, network));
                        });
            }
        } catch (PersistenceException e) {
            throw new RecoverableException("An error from the database", e);
        }

        if (flow.getGroupId() != null) {
            log.info("Filling AvailableNetwork diverse weighs for group with id {}", flow.getGroupId());

            Collection<PathId> flowPaths = flowPathRepository.findPathIdsByFlowGroupId(flow.getGroupId());
            if (!reusePathsResources.isEmpty()) {
                flowPaths = flowPaths.stream()
                        .filter(s -> !reusePathsResources.contains(s))
                        .collect(Collectors.toList());
            }

            flowPaths.forEach(pathId ->
                    flowPathRepository.findById(pathId)
                            .ifPresent(flowPath -> {
                                network.processDiversitySegments(flowPath.getSegments(), flow);
                                network.processDiversitySegmentsWithPop(flowPath.getSegments());
                            }));
        }

        return network;
    }

    private Collection<IslImmutableView> getAvailableIsls(BuildStrategy buildStrategy, Flow flow) {
        if (buildStrategy == BuildStrategy.COST) {
            Collection<IslImmutableView> isls = flow.isIgnoreBandwidth()
                    ? islRepository.findActiveByEncapsulationType(flow.getEncapsulationType())
                    : islRepository.findActiveByBandwidthAndEncapsulationType(flow.getBandwidth(),
                    flow.getEncapsulationType());
            validateIslsCost(isls);
            return isls;
        } else if (buildStrategy == BuildStrategy.SYMMETRIC_COST) {
            Collection<IslImmutableView> isls = flow.isIgnoreBandwidth()
                    ? islRepository.findActiveByEncapsulationType(flow.getEncapsulationType())
                    : islRepository.findSymmetricActiveByBandwidthAndEncapsulationType(flow.getBandwidth(),
                    flow.getEncapsulationType());
            validateIslsCost(isls);
            return isls;
        } else {
            throw new UnsupportedOperationException(String.format("Unsupported buildStrategy type %s", buildStrategy));
        }
    }

    private void validateIslsCost(Collection<IslImmutableView> isls) {
        List<String> messages = new ArrayList<>();

        for (IslImmutableView isl : isls) {
            if (isl.getCost() < 0) {
                messages.add(String.format("(%s_%d ===> %s_%d cost: %d)",
                        isl.getSrcSwitchId(), isl.getSrcPort(), isl.getDestSwitchId(),
                        isl.getDestPort(), isl.getCost()));
            }
        }
        if (!messages.isEmpty()) {
            log.error("Invalid network state. Following ISLs have negative costs: {}", String.join(", ", messages));
        }
    }

    private void addIslAsEdge(IslImmutableView isl, AvailableNetwork network) {
        Node srcSwitch = network.getOrAddNode(isl.getSrcSwitchId(), isl.getSrcPop());
        Node dstSwitch = network.getOrAddNode(isl.getDestSwitchId(), isl.getDestPop());

        Edge edge = Edge.builder()
                .srcSwitch(srcSwitch)
                .srcPort(isl.getSrcPort())
                .destSwitch(dstSwitch)
                .destPort(isl.getDestPort())
                .cost(isl.getCost())
                .latency(isl.getLatency())
                .underMaintenance(isl.isUnderMaintenance())
                .unstable(isl.isUnstable())
                .availableBandwidth(isl.getAvailableBandwidth())
                .build();
        network.addEdge(edge);
    }

    public enum BuildStrategy {
        /**
         * WeightStrategy based on cost of links.
         */
        COST,

        /**
         * Based on cost with always equal forward and reverse paths.
         */
        SYMMETRIC_COST;

        private static BuildStrategy from(String strategy) {
            try {
                return valueOf(strategy.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(String.format("BuildStrategy %s is not supported", strategy));
            }
        }
    }
}
