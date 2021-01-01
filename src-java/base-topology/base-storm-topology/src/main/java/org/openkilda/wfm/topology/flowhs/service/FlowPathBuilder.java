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

import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.pce.Path;
import org.openkilda.pce.Path.Segment;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;

import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
public class FlowPathBuilder {
    private SwitchRepository switchRepository;
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
     *  @param flow a flow the flow path will be associated with.
     * @param pathResources resources to be used for the flow path.
     * @param path path to be used for the flow path.
     * @param cookie cookie to be used for the flow path.
     * @param forceToIgnoreBandwidth force path to ignore bandwidth.
     */
    public FlowPath buildFlowPath(Flow flow, PathResources pathResources, Path path, FlowSegmentCookie cookie,
                                  boolean forceToIgnoreBandwidth) {
        Map<SwitchId, Switch> switches = new HashMap<>();
        Map<SwitchId, SwitchProperties> switchProperties = new HashMap<>();
        switches.put(flow.getSrcSwitchId(), flow.getSrcSwitch());
        switches.put(flow.getDestSwitchId(), flow.getDestSwitch());

        Switch srcSwitch = switches.get(path.getSrcSwitchId());
        if (srcSwitch == null) {
            throw new IllegalArgumentException(format("Path %s has different end-point %s than flow %s",
                    pathResources.getPathId(), path.getSrcSwitchId(), flow.getFlowId()));
        }
        Switch destSwitch = switches.get(path.getDestSwitchId());
        if (destSwitch == null) {
            throw new IllegalArgumentException(format("Path %s has different end-point %s than flow %s",
                    pathResources.getPathId(), path.getDestSwitchId(), flow.getFlowId()));
        }
        Optional<SwitchProperties> srcSwitchProperties = switchPropertiesRepository.findBySwitchId(
                flow.getSrcSwitchId());
        DetectConnectedDevices.DetectConnectedDevicesBuilder detectConnectedDevices =
                flow.getDetectConnectedDevices().toBuilder();
        if (srcSwitchProperties.isPresent()) {
            switchProperties.put(flow.getSrcSwitchId(), srcSwitchProperties.get());
            flow.setSrcWithMultiTable(srcSwitchProperties.get().isMultiTable());
            detectConnectedDevices.srcSwitchLldp(srcSwitchProperties.get().isSwitchLldp());
            detectConnectedDevices.srcSwitchArp(srcSwitchProperties.get().isSwitchArp());
        }
        Optional<SwitchProperties> dstSwitchProperties = switchPropertiesRepository.findBySwitchId(
                flow.getDestSwitchId());
        if (dstSwitchProperties.isPresent()) {
            switchProperties.put(flow.getDestSwitchId(), dstSwitchProperties.get());
            flow.setDestWithMultiTable(dstSwitchProperties.get().isMultiTable());
            detectConnectedDevices.dstSwitchLldp(dstSwitchProperties.get().isSwitchLldp());
            detectConnectedDevices.dstSwitchArp(dstSwitchProperties.get().isSwitchArp());
        }
        flow.setDetectConnectedDevices(detectConnectedDevices.build());

        FlowPath flowPath = FlowPath.builder()
                .pathId(pathResources.getPathId())
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .meterId(pathResources.getMeterId())
                .cookie(cookie)
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth() || forceToIgnoreBandwidth)
                .latency(path.getLatency())
                .build();

        List<PathSegment> segments = path.getSegments().stream()
                .map(segment -> {
                    Switch segmentSrcSwitch = switches.get(segment.getSrcSwitchId());
                    if (segmentSrcSwitch == null) {
                        segmentSrcSwitch = switchRepository.findById(segment.getSrcSwitchId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        format("Path %s has unknown end-point %s",
                                                pathResources.getPathId(), segment.getSrcSwitchId())));
                        switches.put(segment.getSrcSwitchId(), segmentSrcSwitch);
                    }

                    SwitchProperties segmentSrcSwitchProperties = switchProperties.get(segment.getSrcSwitchId());
                    if (segmentSrcSwitchProperties == null) {
                        segmentSrcSwitchProperties = switchPropertiesRepository.findBySwitchId(segment.getSrcSwitchId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        format("Path %s has end-point %s without switch properties",
                                                pathResources.getPathId(), segment.getSrcSwitchId())));
                        switchProperties.put(segment.getSrcSwitchId(), segmentSrcSwitchProperties);
                    }

                    Switch segmentDestSwitch = switches.get(segment.getDestSwitchId());
                    if (segmentDestSwitch == null) {
                        segmentDestSwitch = switchRepository.findById(segment.getDestSwitchId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        format("Path %s has unknown end-point %s",
                                                pathResources.getPathId(), segment.getDestSwitchId())));
                        switches.put(segment.getDestSwitchId(), segmentDestSwitch);
                    }

                    SwitchProperties segmentDstSwitchProperties = switchProperties.get(segment.getDestSwitchId());
                    if (segmentDstSwitchProperties == null) {
                        segmentDstSwitchProperties = switchPropertiesRepository
                                .findBySwitchId(segment.getDestSwitchId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        format("Path %s has end-point %s without switch properties",
                                                pathResources.getPathId(), segment.getDestSwitchId())));
                        switchProperties.put(segment.getDestSwitchId(), segmentDstSwitchProperties);
                    }

                    return PathSegment.builder()
                            .pathId(flowPath.getPathId())
                            .srcSwitch(segmentSrcSwitch)
                            .srcWithMultiTable(segmentSrcSwitchProperties.isMultiTable())
                            .srcPort(segment.getSrcPort())
                            .destSwitch(segmentDestSwitch)
                            .destWithMultiTable(segmentDstSwitchProperties.isMultiTable())
                            .destPort(segment.getDestPort())
                            .latency(segment.getLatency())
                            .build();
                })
                .collect(Collectors.toList());
        flowPath.setSegments(segments);

        return flowPath;
    }
}
