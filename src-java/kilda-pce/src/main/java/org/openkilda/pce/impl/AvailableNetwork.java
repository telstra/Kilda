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

package org.openkilda.pce.impl;

import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.model.Edge;
import org.openkilda.pce.model.Node;
import org.openkilda.pce.model.WeightFunction;
import org.openkilda.persistence.repositories.IslRepository.IslView;

import com.google.common.annotations.VisibleForTesting;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Semantically, this class represents an "available network". That means everything in it is available for path
 * allocation.
 */
@Slf4j
@ToString
public class AvailableNetwork {
    @VisibleForTesting
    final Map<SwitchId, Node> switches = new HashMap<>();

    @VisibleForTesting
    final Set<Edge> edges = new HashSet<>();

    public Node getSwitch(SwitchId dpid) {
        return switches.get(dpid);
    }

    /**
     * Creates switches (if they are not created yet) and ISL between them.
     */
    public void addLink(IslView isl) {
        addLink(isl, false);
    }

    /**
     * Creates switches (if they are not created yet) and ISL between them.
     * @param isl new isl
     * @param errorOnDuplicates how to handle duplicate links, if true will throw exception on duplicate
     * @throws IllegalArgumentException in case of duplicate isl
     */
    public void addLink(IslView isl, boolean errorOnDuplicates) {
        Node srcSwitch = getOrInitSwitch(isl.getSrcSwitchId(), isl.getSrcPop());
        Node dstSwitch = getOrInitSwitch(isl.getDestSwitchId(), isl.getDestPop());

        Edge edge = Edge.fromIslToBuilder(isl)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .build();
        edges.add(edge);
        boolean srcAdded = srcSwitch.getOutgoingLinks().add(edge);
        boolean dstAdded = dstSwitch.getIncomingLinks().add(edge);
        if (errorOnDuplicates && !(srcAdded && dstAdded)) {
            String message = String.format("Duplicate ISL has been passed to AvailableNetwork: {}", isl);
            throw new IllegalArgumentException(message);
        }
    }

    private Node getOrInitSwitch(SwitchId sw, String pop) {
        return switches.computeIfAbsent(sw, switchId ->  Node.fromSwitch(sw, pop));
    }

    /**
     * Adds diversity weights into {@link AvailableNetwork} based on passed path segments and configuration.
     */
    public void processDiversitySegments(List<PathSegment> segments) {
        for (PathSegment segment : segments) {
            Node srcNode = getSwitch(segment.getSrcSwitchId());
            Node dstNode = getSwitch(segment.getDestSwitchId());

            if (dstNode != null) {
                dstNode.increaseDiversityGroupUseCounter();
            }
            if (srcNode != null && segment.getSeqId() == 0) {
                srcNode.increaseDiversityGroupUseCounter();
            }

            if (srcNode == null || dstNode == null) {
                log.debug("Diversity segment {} don't present in AvailableNetwork", segment);
                continue;
            }

            Edge segmentEdge = Edge.builder()
                    .srcSwitch(srcNode)
                    .srcPort(segment.getSrcPort())
                    .destSwitch(dstNode)
                    .destPort(segment.getDestPort())
                    .build();

            Optional<Edge> edgeOptional = dstNode.getIncomingLinks().stream().filter(segmentEdge::equals).findAny();
            if (edgeOptional.isPresent()) {
                Edge edge = edgeOptional.get();

                edge.increaseDiversityGroupUseCounter();
            }
        }
    }

    /**
     * Adds diversity weights into {@link AvailableNetwork} based on passed path segments and configuration.
     */
    public void processDiversitySegmentsWithPop(List<PathSegment> segments) {
        if (segments.size() <= 1) {
            return;
        }

        Set<String> allocatedPopSet = new HashSet<>();

        for (PathSegment ps : segments) {
            allocatedPopSet.add(ps.getSrcSwitch().getPop());
            allocatedPopSet.add(ps.getDestSwitch().getPop());
        }

        String inPop = segments.get(0).getSrcSwitch().getPop();
        allocatedPopSet.remove(inPop);

        String outPop = segments.get(segments.size() - 1).getDestSwitch().getPop();
        allocatedPopSet.remove(outPop);


        for (Edge edge : edges) {
            String srcPop = edge.getSrcSwitch().getPop();
            if (srcPop != null && allocatedPopSet.contains(srcPop)) {
                edge.increaseDiversityGroupPerPopUseCounter();
                continue;
            }
            String dstPop = edge.getDestSwitch().getPop();
            if (dstPop != null && allocatedPopSet.contains(dstPop)) {
                edge.increaseDiversityGroupPerPopUseCounter();
                continue;
            }
        }


    }

    /**
     * Call this function to reduce the network to single (directed) links between src and dst switches.
     */
    public void reduceByWeight(WeightFunction weightFunction) {
        for (Node node : switches.values()) {
            Set<Edge> reduced = node.reduceByWeight(weightFunction);
            reduced.forEach(e -> {
                switches.get(e.getSrcSwitch().getSwitchId()).getIncomingLinks().remove(e);
                switches.get(e.getSrcSwitch().getSwitchId()).getOutgoingLinks().remove(e);
                switches.get(e.getDestSwitch().getSwitchId()).getIncomingLinks().remove(e);
                switches.get(e.getDestSwitch().getSwitchId()).getOutgoingLinks().remove(e);
            });
        }
    }
}
