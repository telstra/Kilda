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

package org.openkilda.wfm.share.service;

import org.openkilda.messaging.payload.flow.OverlappingSegmentsStats;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;

import com.google.common.collect.Sets;
import lombok.Value;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Computes intersection counters for flow paths in flow group.
 */
public class IntersectionComputer {
    private Set<Edge> targetPathEdges;
    private Set<SwitchId> targetPathSwitches;

    private Map<PathId, Set<Edge>> otherEdges;

    /**
     * Construct intersection counter for current flow group and target flow and flow path.
     *
     * @param targetFlowId the flow id to filter other paths belongs to target flow.
     * @param targetForwardPathId the forward  path id to find overlapping with.
     * @param targetReversePathId the reverse path id to find overlapping with.
     * @param paths paths in the flow group.
     */
    public IntersectionComputer(String targetFlowId, PathId targetForwardPathId, PathId targetReversePathId,
                                Collection<FlowPath> paths) {
        List<Edge> targetPath = paths.stream()
                .flatMap(path -> path.getSegments().stream())
                .filter(e -> e.getPathId().equals(targetForwardPathId)
                        || e.getPathId().equals(targetReversePathId))
                .map(Edge::fromPathSegment)
                .collect(Collectors.toList());

        targetPathEdges = new HashSet<>(targetPath);
        targetPathSwitches = targetPath.stream()
                .flatMap(e -> Stream.of(e.getSrcSwitch(), e.getDestSwitch()))
                .collect(Collectors.toSet());

        otherEdges = paths.stream()
                .filter(e -> !e.getFlow().getFlowId().equals(targetFlowId))
                .flatMap(path -> path.getSegments().stream())
                .collect(Collectors.groupingBy(
                        PathSegment::getPathId,
                        Collectors.mapping(Edge::fromPathSegment, Collectors.toSet())
                ));
    }

    /**
     * Returns {@link OverlappingSegmentsStats} between target path id and other flow paths in the group.
     *
     * @return {@link OverlappingSegmentsStats} instance.
     */
    public OverlappingSegmentsStats getOverlappingStats() {
        return computeIntersectionCounters(
                otherEdges.values().stream().flatMap(Collection::stream).collect(Collectors.toSet()));
    }

    /**
     * Returns {@link OverlappingSegmentsStats} with {@param pathId} flow path.
     *
     * @return {@link OverlappingSegmentsStats} instance.
     */
    public OverlappingSegmentsStats getOverlappingStats(PathId forwardPathId, PathId reversePathId) {
        Set<Edge> edges = new HashSet<>(otherEdges.getOrDefault(forwardPathId, Collections.emptySet()));
        edges.addAll(otherEdges.getOrDefault(reversePathId, Collections.emptySet()));
        return computeIntersectionCounters(edges);
    }

    OverlappingSegmentsStats computeIntersectionCounters(Set<Edge> otherEdges) {
        Set<SwitchId> switches = new HashSet<>();
        Set<Edge> edges = new HashSet<>();
        for (Edge edge : otherEdges) {
            switches.add(edge.getSrcSwitch());
            switches.add(edge.getDestSwitch());
            edges.add(edge);
        }

        int edgesOverlap = Sets.intersection(edges, targetPathEdges).size();
        int switchesOverlap = Sets.intersection(switches, targetPathSwitches).size();
        return new OverlappingSegmentsStats(edgesOverlap,
                switchesOverlap,
                percent(edgesOverlap, targetPathEdges.size()),
                percent(switchesOverlap, targetPathSwitches.size()));
    }

    private int percent(int n, int from) {
        return (int) ((n * 100.0f) / from);
    }

    /**
     * Calculates is overlapping between primary and protected flow segments exists.
     *
     * @param primaryFlowSegments primary flow segments.
     * @param protectedFlowSegments protected flow segments.
     * @return is overlapping flag.
     */
    public static boolean isProtectedPathOverlaps(
            List<PathSegment> primaryFlowSegments, List<PathSegment> protectedFlowSegments) {
        Set<Edge> primaryEdges = primaryFlowSegments.stream().map(Edge::fromPathSegment).collect(Collectors.toSet());
        Set<Edge> protectedEdges = protectedFlowSegments.stream().map(Edge::fromPathSegment)
                .collect(Collectors.toSet());

        return !Sets.intersection(primaryEdges, protectedEdges).isEmpty();
    }

    /**
     * Edge representation. Constraint: srcSwitch is always not less than destSwitch
     */
    @Value
    static class Edge {
        private SwitchId srcSwitch;
        private int srcPort;
        private SwitchId destSwitch;
        private int destPort;

        static Edge fromPathSegment(PathSegment segment) {
            if (segment.getSrcSwitchId().compareTo(segment.getDestSwitchId()) > 0) {
                return new Edge(segment.getSrcSwitchId(),
                        segment.getSrcPort(),
                        segment.getDestSwitchId(),
                        segment.getDestPort());
            }
            return new Edge(segment.getDestSwitchId(),
                    segment.getDestPort(),
                    segment.getSrcSwitchId(),
                    segment.getSrcPort());
        }
    }
}
