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

package org.openkilda.persistence.ferma.repositories;

import static java.lang.String.format;

import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPath.FlowPathData;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowFrame;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.converters.FlowSegmentCookieConverter;
import org.openkilda.persistence.ferma.frames.converters.FlowStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.tx.TransactionManager;

import com.syncleus.ferma.FramedGraph;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowPathRepository}.
 */
public class FermaFlowPathRepository extends FermaGenericRepository<FlowPath, FlowPathData, FlowPathFrame>
        implements FlowPathRepository {
    public FermaFlowPathRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<FlowPath> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<FlowPath> findById(PathId pathId) {
        List<? extends FlowPathFrame> flowPathFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .toListExplicit(FlowPathFrame.class);
        return flowPathFrames.isEmpty() ? Optional.empty() : Optional.of(flowPathFrames.get(0))
                .map(FlowPath::new);
    }

    @Override
    public Optional<FlowPath> findByFlowIdAndCookie(String flowId, FlowSegmentCookie cookie) {
        List<? extends FlowPathFrame> flowPathFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.FLOW_ID_PROPERTY, flowId)
                .has(FlowPathFrame.COOKIE_PROPERTY, FlowSegmentCookieConverter.INSTANCE.toGraphProperty(cookie)))
                .toListExplicit(FlowPathFrame.class);
        if (flowPathFrames.size() > 1) {
            throw new PersistenceException(format("Found more that 1 FlowPath entity by (%s, %s)", flowId, cookie));
        }
        return flowPathFrames.isEmpty() ? Optional.empty() : Optional.of(flowPathFrames.get(0)).map(FlowPath::new);
    }

    @Override
    public Collection<FlowPath> findByFlowId(String flowId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.FLOW_ID_PROPERTY, flowId))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findByFlowGroupId(String flowGroupId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.GROUP_ID_PROPERTY, flowGroupId)
                .out(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<PathId> findPathIdsByFlowGroupId(String flowGroupId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.GROUP_ID_PROPERTY, flowGroupId)
                .out(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .values(FlowPathFrame.PATH_ID_PROPERTY))
                .getRawTraversal().toStream()
                .map(pathId -> PathIdConverter.INSTANCE.toEntityAttribute((String) pathId))
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findActualByFlowIds(Set<String> flowIds) {
        Set<String> pathIds = new HashSet<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, P.within(flowIds))
                .values(FlowFrame.FORWARD_PATH_ID_PROPERTY, FlowFrame.REVERSE_PATH_ID_PROPERTY,
                        FlowFrame.PROTECTED_FORWARD_PATH_ID_PROPERTY, FlowFrame.PROTECTED_REVERSE_PATH_ID_PROPERTY))
                .getRawTraversal()
                .forEachRemaining(pathId -> pathIds.add((String) pathId));

        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.PATH_ID_PROPERTY, P.within(pathIds)))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findBySrcSwitch(SwitchId switchId, boolean includeProtected) {
        List<FlowPath> result = new ArrayList<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.add(new FlowPath(frame)));
        if (includeProtected) {
            return result;
        } else {
            return result.stream()
                    .filter(path -> !(path.isProtected() && switchId.equals(path.getSrcSwitchId())))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public Collection<FlowPath> findByEndpointSwitch(SwitchId switchId, boolean includeProtected) {
        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));

        if (includeProtected) {
            return result.values();
        } else {
            return result.values().stream()
                    .filter(path -> !(path.isProtected() && switchId.equals(path.getSrcSwitchId())))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public Collection<FlowPath> findBySegmentSwitch(SwitchId switchId) {
        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        return result.values();
    }

    @Override
    public Collection<FlowPath> findInactiveBySegmentSwitch(SwitchId switchId) {
        String downFlowStatus = FlowStatusConverter.INSTANCE.toGraphProperty(FlowStatus.DOWN);
        String degragedFlowStatus = FlowStatusConverter.INSTANCE.toGraphProperty(FlowStatus.DEGRADED);

        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL).as("p")
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.STATUS_PROPERTY, P.within(downFlowStatus, degragedFlowStatus))
                .select("p"))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL).as("p")
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.STATUS_PROPERTY, P.within(downFlowStatus, degragedFlowStatus))
                .select("p"))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        return result.values();
    }

    @Override
    public Collection<FlowPath> findBySegmentSwitchWithMultiTable(SwitchId switchId, boolean multiTable) {
        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(PathSegmentFrame.SRC_W_MULTI_TABLE_PROPERTY, multiTable)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(PathSegmentFrame.DST_W_MULTI_TABLE_PROPERTY, multiTable)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        return result.values();
    }

    @Override
    public Collection<FlowPath> findWithPathSegment(SwitchId srcSwitchId, int srcPort,
                                                    SwitchId dstSwitchId, int dstPort) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId))
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId))
                .has(PathSegmentFrame.SRC_PORT_PROPERTY, srcPort)
                .has(PathSegmentFrame.DST_PORT_PROPERTY, dstPort)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findBySegmentDestSwitch(SwitchId switchId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findBySegmentEndpoint(SwitchId switchId, int port) {
        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(PathSegmentFrame.SRC_PORT_PROPERTY, port)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(PathSegmentFrame.DST_PORT_PROPERTY, port)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        return result.values();
    }

    @Override
    public void updateStatus(PathId pathId, FlowPathStatus pathStatus) {
        transactionManager.doInTransaction(() ->
                framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowPathFrame.FRAME_LABEL)
                        .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                        .toListExplicit(FlowPathFrame.class)
                        .forEach(pathFrame -> pathFrame.setStatus(pathStatus)));
    }

    @Override
    public long getUsedBandwidthBetweenEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        String srcSwitchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId);
        String dstSwitchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId);
        return getUsedBandwidthBetweenEndpoints(framedGraph(), srcSwitchIdAsStr, srcPort, dstSwitchIdAsStr, dstPort);
    }

    protected long getUsedBandwidthBetweenEndpoints(FramedGraph framedGraph,
                                          String srcSwitchId, int srcPort, String dstSwitchId, int dstPort) {
        try (GraphTraversal<?, ?> traversal = framedGraph.traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, srcSwitchId)
                .has(PathSegmentFrame.DST_SWITCH_ID_PROPERTY, dstSwitchId)
                .has(PathSegmentFrame.SRC_PORT_PROPERTY, srcPort)
                .has(PathSegmentFrame.DST_PORT_PROPERTY, dstPort)
                .has(PathSegmentFrame.IGNORE_BANDWIDTH_PROPERTY, false)
                .values(PathSegmentFrame.BANDWIDTH_PROPERTY)
                .sum())
                .getRawTraversal()) {
            return traversal.tryNext()
                    .map(l -> ((Number) l).longValue()).orElse(0L);
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Optional<FlowPath> remove(PathId pathId) {
        if (transactionManager.isTxOpen()) {
            // This implementation removes dependant entities (segments) in a separate transactions,
            // so the path entity may require to be reloaded in a case of failed transaction.
            throw new IllegalStateException("This implementation of remove requires no outside transaction");
        }

        return transactionManager.doInTransaction(() ->
                findById(pathId)
                        .map(path -> {
                            remove(path);
                            return path;
                        }));
    }

    @Override
    protected FlowPathFrame doAdd(FlowPathData data) {
        FlowPathFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), FlowPathFrame.FRAME_LABEL,
                FlowPathFrame.class);
        FlowPath.FlowPathCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(FlowPathFrame frame) {
        frame.getSegments().forEach(pathSegment -> {
            if (pathSegment.getData() instanceof PathSegmentFrame) {
                // No need to call the PathSegment repository, as segments already detached along with the path.
                ((PathSegmentFrame) pathSegment.getData()).remove();
            }
        });
        frame.remove();
    }

    @Override
    protected FlowPathData doDetach(FlowPath entity, FlowPathFrame frame) {
        return FlowPath.FlowPathCloner.INSTANCE.deepCopy(frame, entity.getFlow());
    }
}
