/* Copyright 2017 Telstra Open Source
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

package org.openkilda.pce.impl;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.AvailableNetworkFactory;
import org.openkilda.pce.Path;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathComputerConfig;
import org.openkilda.pce.PathPair;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.pce.finder.PathFinder;
import org.openkilda.pce.model.Edge;
import org.openkilda.pce.model.WeightFunction;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link PathComputer} that operates over in-memory {@link AvailableNetwork}.
 * <p/>
 * The path finding algorithm is defined by provided {@link PathFinder}.
 */
@Slf4j
public class InMemoryPathComputer implements PathComputer {
    private final AvailableNetworkFactory availableNetworkFactory;
    private final PathFinder pathFinder;
    private final PathComputerConfig config;

    public InMemoryPathComputer(AvailableNetworkFactory availableNetworkFactory, PathFinder pathFinder,
                                PathComputerConfig config) {
        this.availableNetworkFactory = availableNetworkFactory;
        this.pathFinder = pathFinder;
        this.config = config;
    }

    @Override
    public PathPair getPath(Flow flow, Collection<PathId> reusePathsResources)
            throws UnroutableFlowException, RecoverableException {
        return getPath(availableNetworkFactory.getAvailableNetwork(flow, reusePathsResources), flow);
    }

    private PathPair getPath(AvailableNetwork network, Flow flow) throws UnroutableFlowException {
        if (flow.isOneSwitchFlow()) {
            log.info("No path computation for one-switch flow");
            SwitchId singleSwitchId = flow.getSrcSwitchId();
            return PathPair.builder()
                    .forward(convertToPath(singleSwitchId, singleSwitchId, emptyList()))
                    .reverse(convertToPath(singleSwitchId, singleSwitchId, emptyList()))
                    .build();
        }

        WeightFunction weightFunction = getWeightFunctionByStrategy(flow.getPathComputationStrategy());
        Pair<List<Edge>, List<Edge>> biPath;
        try {
            network.reduceByWeight(weightFunction);

            biPath = findPathInNetwork(flow, network, weightFunction);
        } catch (UnroutableFlowException e) {
            String message = format("Failed to find path with requested bandwidth=%s: %s",
                    flow.isIgnoreBandwidth() ? " ignored" : flow.getBandwidth(), e.getMessage());
            throw new UnroutableFlowException(message, e, flow.getFlowId());
        }

        return convertToPathPair(flow.getSrcSwitchId(), flow.getDestSwitchId(), biPath);
    }

    private Pair<List<Edge>, List<Edge>> findPathInNetwork(Flow flow, AvailableNetwork network,
                                                           WeightFunction weightFunction)
            throws UnroutableFlowException {
        PathComputationStrategy strategy = flow.getPathComputationStrategy();

        if (PathComputationStrategy.MAX_LATENCY.equals(strategy)
                && (flow.getMaxLatency() == null || flow.getMaxLatency() == 0)) {
            strategy = PathComputationStrategy.LATENCY;
        }

        switch (strategy) {
            case COST:
            case LATENCY:
                return pathFinder.findPathInNetwork(network, flow.getSrcSwitchId(),
                        flow.getDestSwitchId(), weightFunction);
            case MAX_LATENCY:
                return pathFinder.findPathInNetwork(network, flow.getSrcSwitchId(),
                        flow.getDestSwitchId(), weightFunction, flow.getMaxLatency());
            default:
                throw new UnsupportedOperationException(String.format("Unsupported strategy type %s", strategy));
        }
    }

    @Override
    public List<Path> getNPaths(SwitchId srcSwitchId, SwitchId dstSwitchId, int count,
                                FlowEncapsulationType flowEncapsulationType,
                                PathComputationStrategy pathComputationStrategy)
            throws RecoverableException, UnroutableFlowException {
        Flow flow = Flow.builder()
                .flowId("") // just any id, as not used.
                .srcSwitch(Switch.builder().switchId(srcSwitchId).build())
                .destSwitch(Switch.builder().switchId(dstSwitchId).build())
                .ignoreBandwidth(false)
                .encapsulationType(flowEncapsulationType)
                .bandwidth(1) // to get ISLs with non zero available bandwidth
                .build();

        AvailableNetwork availableNetwork = availableNetworkFactory.getAvailableNetwork(flow, Collections.emptyList());

        List<List<Edge>> paths =
                pathFinder.findNPathsBetweenSwitches(availableNetwork, srcSwitchId, dstSwitchId, count,
                        getWeightFunctionByStrategy(pathComputationStrategy));
        return paths.stream()
                .map(edges -> convertToPath(srcSwitchId, dstSwitchId, edges))
                .sorted(Comparator.comparing(Path::getMinAvailableBandwidth)
                        .reversed()
                        .thenComparing(Path::getLatency))
                .limit(count)
                .collect(Collectors.toList());
    }

    private WeightFunction getWeightFunctionByStrategy(PathComputationStrategy strategy) {
        switch (strategy) {
            case COST:
                return this::weightByCost;
            case LATENCY:
            case MAX_LATENCY:
                return this::weightByLatency;
            default:
                throw new UnsupportedOperationException(String.format("Unsupported strategy type %s", strategy));
        }
    }

    private Long weightByCost(Edge edge) {
        long total = edge.getCost() == 0 ? config.getDefaultIslCost() : edge.getCost();
        if (edge.isUnderMaintenance()) {
            total += config.getUnderMaintenanceCostRaise();
        }
        if (edge.isUnstable()) {
            total += config.getUnstableCostRaise();
        }
        total += edge.getDiversityGroupUseCounter() * config.getDiversityIslCost()
                + edge.getDiversityGroupPerPopUseCounter() * config.getDiversityPopIslCost()
                + edge.getDestSwitch().getDiversityGroupUseCounter() * config.getDiversitySwitchCost();
        return total;
    }

    private Long weightByLatency(Edge edge) {
        long total = edge.getLatency() <= 0 ? config.getDefaultIslLatency() : edge.getLatency();
        if (edge.isUnderMaintenance()) {
            total += config.getUnderMaintenanceLatencyRaise();
        }
        if (edge.isUnstable()) {
            total += config.getUnstableLatencyRaise();
        }
        total += edge.getDiversityGroupUseCounter() * config.getDiversityIslLatency()
                + edge.getDiversityGroupPerPopUseCounter() * config.getDiversityPopIslCost()
                + edge.getDestSwitch().getDiversityGroupUseCounter() * config.getDiversitySwitchLatency();
        return total;
    }

    private PathPair convertToPathPair(SwitchId srcSwitchId, SwitchId dstSwitchId,
                                       Pair<List<Edge>, List<Edge>> biPath) {
        return PathPair.builder()
                .forward(convertToPath(srcSwitchId, dstSwitchId, biPath.getLeft()))
                .reverse(convertToPath(dstSwitchId, srcSwitchId, biPath.getRight()))
                .build();
    }

    private Path convertToPath(SwitchId srcSwitchId, SwitchId dstSwitchId, List<Edge> edges) {
        List<Path.Segment> segments = new LinkedList<>();

        long latency = 0L;
        long minAvailableBandwidth = Long.MAX_VALUE;
        for (Edge edge : edges) {
            latency += edge.getLatency();
            minAvailableBandwidth = Math.min(minAvailableBandwidth, edge.getAvailableBandwidth());
            segments.add(convertToSegment(edge));
        }

        return Path.builder()
                .srcSwitchId(srcSwitchId)
                .destSwitchId(dstSwitchId)
                .segments(segments)
                .latency(latency)
                .minAvailableBandwidth(minAvailableBandwidth)
                .build();
    }

    private Path.Segment convertToSegment(Edge edge) {
        return Path.Segment.builder()
                .srcSwitchId(edge.getSrcSwitch().getSwitchId())
                .srcPort(edge.getSrcPort())
                .destSwitchId(edge.getDestSwitch().getSwitchId())
                .destPort(edge.getDestPort())
                .latency(edge.getLatency())
                .build();
    }
}
