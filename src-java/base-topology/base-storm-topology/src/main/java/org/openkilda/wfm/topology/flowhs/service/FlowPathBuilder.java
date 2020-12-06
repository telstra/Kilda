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

package org.openkilda.wfm.topology.flowhs.service;

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.pce.Path;
import org.openkilda.pce.Path.Segment;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;

import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.map.LazyMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
public class FlowPathBuilder {
    private SwitchPropertiesRepository switchPropertiesRepository;

    /**
     * Check whether the path and flow path represent the same.
     *
     * @param path the path to evaluate.
     * @param flowPath the flow path to evaluate.
     */
    public boolean isSamePath(Path path, FlowPath flowPath) {
        if (!path.getSrcSwitchId().equals(flowPath.getSrcSwitchId())
                || !path.getDestSwitchId().equals(flowPath.getDestSwitchId())
                || path.getSegments().size() != flowPath.getSegments().size()) {
            return false;
        }

        Iterator<Segment> pathIt = path.getSegments().iterator();
        Iterator<PathSegment> flowPathIt = flowPath.getSegments().iterator();
        while (pathIt.hasNext() && flowPathIt.hasNext()) {
            Path.Segment pathSegment = pathIt.next();
            PathSegment flowSegment = flowPathIt.next();
            if (!pathSegment.getSrcSwitchId().equals(flowSegment.getSrcSwitchId())
                    || !pathSegment.getDestSwitchId().equals(flowSegment.getDestSwitchId())
                    || pathSegment.getSrcPort() != flowSegment.getSrcPort()
                    || pathSegment.getDestPort() != flowSegment.getDestPort()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check whether the path and flow path overlap.
     *
     * @param path the path to evaluate.
     * @param flowPath the flow path to evaluate.
     */
    public boolean arePathsOverlapped(Path path, FlowPath flowPath) {
        Set<Segment> pathSegments = path.getSegments().stream()
                .map(segment -> segment.toBuilder().latency(0).build())
                .collect(Collectors.toSet());
        Set<Segment> flowSegments = flowPath.getSegments().stream()
                .map(segment -> Segment.builder()
                        .srcSwitchId(segment.getSrcSwitchId())
                        .srcPort(segment.getSrcPort())
                        .destSwitchId(segment.getDestSwitchId())
                        .destPort(segment.getDestPort())
                        .latency(0)
                        .build())
                .collect(Collectors.toSet());

        return !Sets.intersection(pathSegments, flowSegments).isEmpty();
    }

    /**
     * Build a flow path entity for the flow using provided path and resources.
     *
     * @param flow a flow the flow path will be associated with.
     * @param pathResources resources to be used for the flow path.
     * @param path path to be used for the flow path.
     * @param cookie cookie to be used for the flow path.
     * @param forceToIgnoreBandwidth force path to ignore bandwidth.
     */
    public FlowPath buildFlowPath(Flow flow, PathResources pathResources, Path path, FlowSegmentCookie cookie,
                                  boolean forceToIgnoreBandwidth) {
        List<PathSegment> segments = buildPathSegments(pathResources.getPathId(), path, flow.getBandwidth(),
                flow.isIgnoreBandwidth() || forceToIgnoreBandwidth);
        return buildFlowPath(flow, pathResources, path.getLatency(), path.getSrcSwitchId(), path.getDestSwitchId(),
                segments, cookie, forceToIgnoreBandwidth);
    }

    /**
     * Build a flow path entity for the flow using provided resources and segments.
     *
     * @param flow a flow the flow path will be associated with.
     * @param pathResources resources to be used for the flow path.
     * @param pathLatency path to be used for the flow path.
     * @param segments segments to be used for the flow path.
     * @param cookie cookie to be used for the flow path.
     * @param forceToIgnoreBandwidth force path to ignore bandwidth.
     */
    public FlowPath buildFlowPath(Flow flow, PathResources pathResources, long pathLatency,
                                  SwitchId srcSwitchId, SwitchId destSwitchId,
                                  List<PathSegment> segments, FlowSegmentCookie cookie,
                                  boolean forceToIgnoreBandwidth) {
        Map<SwitchId, Switch> switches = new HashMap<>();
        switches.put(flow.getSrcSwitchId(), flow.getSrcSwitch());
        switches.put(flow.getDestSwitchId(), flow.getDestSwitch());

        Switch srcSwitch = switches.get(srcSwitchId);
        if (srcSwitch == null) {
            throw new IllegalArgumentException(format("Path %s has different end-point %s than flow %s",
                    pathResources.getPathId(), srcSwitchId, flow.getFlowId()));
        }
        Switch destSwitch = switches.get(destSwitchId);
        if (destSwitch == null) {
            throw new IllegalArgumentException(format("Path %s has different end-point %s than flow %s",
                    pathResources.getPathId(), destSwitchId, flow.getFlowId()));
        }

        return FlowPath.builder()
                .pathId(pathResources.getPathId())
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .meterId(pathResources.getMeterId())
                .cookie(cookie)
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth() || forceToIgnoreBandwidth)
                .latency(pathLatency)
                .segments(segments)
                .build();
    }

    /**
     * Build a path segments using provided path.
     *
     * @param pathId a pathId the segments will be associated with.
     * @param pathForSegments path to be used for the segments.
     * @param bandwidth bandwidth to be used for the segments.
     * @param ignoreBandwidth ignore bandwidth be used for the segments.
     */
    public List<PathSegment> buildPathSegments(PathId pathId, Path pathForSegments, long bandwidth,
                                               boolean ignoreBandwidth) {
        Map<SwitchId, SwitchProperties> switchProperties = LazyMap.lazyMap(new HashMap<>(), switchId ->
                switchPropertiesRepository.findBySwitchId(switchId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                format("Path %s has end-point %s without switch properties", pathId, switchId))));

        List<PathSegment> result = new ArrayList<>();
        for (int i = 0; i < pathForSegments.getSegments().size(); i++) {
            Path.Segment segment = pathForSegments.getSegments().get(i);

            SwitchProperties srcSwitchProperties = switchProperties.get(segment.getSrcSwitchId());
            SwitchProperties dstSwitchProperties = switchProperties.get(segment.getDestSwitchId());

            result.add(PathSegment.builder()
                    .seqId(i)
                    .pathId(pathId)
                    .srcSwitch(Switch.builder().switchId(segment.getSrcSwitchId()).build())
                    .srcPort(segment.getSrcPort())
                    .srcWithMultiTable(srcSwitchProperties.isMultiTable())
                    .destSwitch(Switch.builder().switchId(segment.getDestSwitchId()).build())
                    .destPort(segment.getDestPort())
                    .destWithMultiTable(dstSwitchProperties.isMultiTable())
                    .latency(segment.getLatency())
                    .bandwidth(bandwidth)
                    .ignoreBandwidth(ignoreBandwidth)
                    .build());
        }

        return result;
    }
}
