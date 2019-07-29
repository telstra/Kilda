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
import static java.util.Collections.unmodifiableList;

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPath.FlowPathData;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowFrame;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.SwitchFrame;
import org.openkilda.persistence.ferma.frames.converters.CookieConverter;
import org.openkilda.persistence.ferma.frames.converters.FlowStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.FlowPathRepository;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowPathRepository}.
 */
class FermaFlowPathRepository extends FermaGenericRepository<FlowPath, FlowPathData, FlowPathFrame>
        implements FlowPathRepository {
    FermaFlowPathRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
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
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .nextOrDefaultExplicit(FlowPathFrame.class, null))
                .map(FlowPath::new);
    }

    @Override
    public Optional<FlowPath> findByFlowIdAndCookie(String flowId, Cookie cookie) {
        List<FlowPathFrame> flowPaths = unmodifiableList(
                framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId)
                        .out(FlowFrame.OWNS_PATHS_EDGE)
                        .hasLabel(FlowPathFrame.FRAME_LABEL)
                        .has(FlowPathFrame.COOKIE_PROPERTY, CookieConverter.INSTANCE.toGraphProperty(cookie)))
                        .toListExplicit(FlowPathFrame.class));
        if (flowPaths.size() > 1) {
            throw new PersistenceException(format("Found more that 1 FlowPath entity by (%s, %s)", flowId, cookie));
        } else if (flowPaths.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(flowPaths.iterator().next()).map(FlowPath::new);
    }

    @Override
    public Collection<FlowPath> findByFlowId(String flowId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, flowId)
                .out(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
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
    public Collection<FlowPath> findBySrcSwitch(SwitchId switchId, boolean includeProtected) {
        List<FlowPath> result = new ArrayList<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowPathFrame.SOURCE_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
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
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowPathFrame.SOURCE_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowPathFrame.DESTINATION_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
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
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.SOURCE_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.DESTINATION_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
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
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.SOURCE_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL).as("p")
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .or(__.has(FlowFrame.STATUS_PROPERTY, downFlowStatus),
                        __.has(FlowFrame.STATUS_PROPERTY, degragedFlowStatus))
                .select("p"))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.DESTINATION_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL).as("p")
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .or(__.has(FlowFrame.STATUS_PROPERTY, downFlowStatus),
                        __.has(FlowFrame.STATUS_PROPERTY, degragedFlowStatus))
                .select("p"))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        return result.values();
    }

    @Override
    public Collection<FlowPath> findBySegmentSwitchWithMultiTable(SwitchId switchId, boolean multiTable) {
        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.SOURCE_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_W_MULTI_TABLE_PROPERTY, multiTable)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.DESTINATION_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
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
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, srcSwitchId)
                .in(PathSegmentFrame.SOURCE_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_PORT_PROPERTY, srcPort)
                .has(PathSegmentFrame.DST_PORT_PROPERTY, dstPort)
                .where(__.out(PathSegmentFrame.DESTINATION_EDGE)
                        .hasLabel(SwitchFrame.FRAME_LABEL)
                        .has(SwitchFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId)))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findBySegmentDestSwitch(SwitchId switchId) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.DESTINATION_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class).stream()
                .map(FlowPath::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<FlowPath> findBySegmentEndpoint(SwitchId switchId, int port) {
        Map<PathId, FlowPath> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.SOURCE_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_PORT_PROPERTY, port)
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL))
                .frameExplicit(FlowPathFrame.class)
                .forEachRemaining(frame -> result.put(frame.getPathId(), new FlowPath(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(PathSegmentFrame.DESTINATION_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
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
                Optional.ofNullable(framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowPathFrame.FRAME_LABEL)
                        .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                        .nextOrDefaultExplicit(FlowPathFrame.class, null))
                        .ifPresent(pathFrame -> {
                            pathFrame.setStatus(pathStatus);
                        }));
    }

    @Override
    public long getUsedBandwidthBetweenEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, srcSwitchId)
                .in(PathSegmentFrame.SOURCE_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.SRC_PORT_PROPERTY, srcPort)
                .has(PathSegmentFrame.DST_PORT_PROPERTY, dstPort)
                .where(__.out(PathSegmentFrame.DESTINATION_EDGE)
                        .hasLabel(SwitchFrame.FRAME_LABEL)
                        .has(SwitchFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId)))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.IGNORE_BANDWIDTH_PROPERTY, false)
                .values(FlowPathFrame.BANDWIDTH_PROPERTY)
                .sum())
                .getRawTraversal().tryNext()
                .map(l -> ((Number) l).longValue()).orElse(0L);
    }

    @Override
    protected FlowPathFrame doAdd(FlowPathData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(data.getPathId())))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a vertex with duplicated "
                    + FlowPathFrame.PATH_ID_PROPERTY);
        }

        FlowPathFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), FlowPathFrame.FRAME_LABEL,
                FlowPathFrame.class);
        FlowPath.FlowPathCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected FlowPathData doRemove(FlowPath entity, FlowPathFrame frame) {
        final FlowPathData data = FlowPath.FlowPathCloner.INSTANCE.copy(frame, entity, entity.getFlow());
        frame.getSegments().forEach(pathSegment -> {
            if (pathSegment.getData() instanceof PathSegmentFrame) {
                ((PathSegmentFrame) pathSegment.getData()).remove();
            }
        });
        frame.getElement().edges(Direction.BOTH).forEachRemaining(Edge::remove);
        frame.remove();
        return data;
    }
}
