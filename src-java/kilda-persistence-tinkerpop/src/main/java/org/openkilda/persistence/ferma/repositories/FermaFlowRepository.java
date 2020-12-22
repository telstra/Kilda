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

import org.openkilda.model.Flow;
import org.openkilda.model.Flow.FlowData;
import org.openkilda.model.FlowFilter;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowFrame;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.converters.FlowStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.persistence.tx.TransactionRequired;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ferma implementation of {@link FlowRepository}.
 */
@Slf4j
public class FermaFlowRepository extends FermaGenericRepository<Flow, FlowData, FlowFrame> implements FlowRepository {
    protected final FlowPathRepository flowPathRepository;

    public FermaFlowRepository(FramedGraphFactory<?> graphFactory, FlowPathRepository flowPathRepository,
                               TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
        this.flowPathRepository = flowPathRepository;
    }

    @Override
    public long countFlows() {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL).count())
                .getRawTraversal()) {
            return (Long) traversal.next();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Collection<Flow> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL))
                .toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(String flowId) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Optional<Flow> findById(String flowId) {
        List<? extends FlowFrame> flowFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                .toListExplicit(FlowFrame.class);
        return flowFrames.isEmpty() ? Optional.empty() : Optional.of(flowFrames.get(0))
                .map(Flow::new);
    }

    @Override
    public Collection<Flow> findByGroupId(String flowGroupId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.GROUP_ID_PROPERTY, flowGroupId))
                .toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<String> findFlowsIdByGroupId(String flowGroupId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.GROUP_ID_PROPERTY, flowGroupId)
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .map(i -> (String) i)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Flow> findWithPeriodicPingsEnabled() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.PERIODIC_PINGS_PROPERTY, true))
                .toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Flow> findByEndpoint(SwitchId switchId, int port) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_PORT_PROPERTY, port))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_PORT_PROPERTY, port))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Optional<Flow> findByEndpointAndVlan(SwitchId switchId, int port, int vlan) {
        List<? extends FlowFrame> flowFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_PORT_PROPERTY, port)
                .has(FlowFrame.SRC_VLAN_PROPERTY, vlan))
                .toListExplicit(FlowFrame.class);
        if (!flowFrames.isEmpty()) {
            return Optional.of(flowFrames.get(0)).map(Flow::new);
        }
        flowFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_PORT_PROPERTY, port)
                .has(FlowFrame.DST_VLAN_PROPERTY, vlan))
                .toListExplicit(FlowFrame.class);
        return flowFrames.isEmpty() ? Optional.empty() : Optional.of(flowFrames.get(0)).map(Flow::new);
    }

    @Override
    public Optional<Flow> findOneSwitchFlowBySwitchIdInPortAndOutVlan(SwitchId switchId, int inPort, int outVlan) {
        List<? extends FlowFrame> flowFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_PORT_PROPERTY, inPort)
                .has(FlowFrame.DST_VLAN_PROPERTY, outVlan))
                .toListExplicit(FlowFrame.class);
        if (!flowFrames.isEmpty()) {
            return Optional.of(flowFrames.get(0)).map(Flow::new);
        }
        flowFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_PORT_PROPERTY, inPort)
                .has(FlowFrame.SRC_VLAN_PROPERTY, outVlan))
                .toListExplicit(FlowFrame.class);
        return flowFrames.isEmpty() ? Optional.empty() : Optional.of(flowFrames.get(0)).map(Flow::new);
    }

    @Override
    public Collection<Flow> findOneSwitchFlows(SwitchId switchId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<String> findFlowsIdsByEndpointWithMultiTableSupport(SwitchId switchId, int port) {
        Set<String> result = new HashSet<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowPathFrame.SRC_MULTI_TABLE_PROPERTY, true)
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_PORT_PROPERTY, port)
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .forEach(i -> result.add((String) i));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowPathFrame.DST_MULTI_TABLE_PROPERTY, true)
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_PORT_PROPERTY, port)
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .forEach(i -> result.add((String) i));
        return result;
    }

    @Override
    public Collection<String> findFlowIdsForMultiSwitchFlowsByEndpointWithMultiTableSupport(SwitchId switchId,
                                                                                            int port) {
        Set<String> result = new HashSet<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowPathFrame.SRC_MULTI_TABLE_PROPERTY, true)
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_PORT_PROPERTY, port)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, P.neq(SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .forEach(i -> result.add((String) i));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowPathFrame.DST_MULTI_TABLE_PROPERTY, true)
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_PORT_PROPERTY, port)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, P.neq(SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .forEach(i -> result.add((String) i));
        return result;
    }

    @Override
    public Collection<Flow> findByEndpointSwitch(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findByEndpointSwitchWithMultiTableSupport(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowPathFrame.SRC_MULTI_TABLE_PROPERTY, true)
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowPathFrame.DST_MULTI_TABLE_PROPERTY, true)
                .in(FlowFrame.OWNS_PATHS_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findByEndpointSwitchWithEnabledLldp(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_LLDP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_LLDP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findByEndpointSwitchWithEnabledArp(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.SRC_ARP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(FlowFrame.DST_ARP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findInactiveFlows() {
        String downFlowStatus = FlowStatusConverter.INSTANCE.toGraphProperty(FlowStatus.DOWN);
        String degragedFlowStatus = FlowStatusConverter.INSTANCE.toGraphProperty(FlowStatus.DEGRADED);

        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.STATUS_PROPERTY, P.within(downFlowStatus, degragedFlowStatus)))
                .toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Flow> findByFlowFilter(FlowFilter flowFilter) {
        return framedGraph().traverse(g -> {
            GraphTraversal<Vertex, Vertex> traversal = g.V()
                    .hasLabel(FlowFrame.FRAME_LABEL);
            if (flowFilter.getFlowStatus() != null) {
                traversal = traversal.has(FlowFrame.STATUS_PROPERTY,
                        FlowStatusConverter.INSTANCE.toGraphProperty(flowFilter.getFlowStatus()));
            }
            return traversal;
        }).toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<String> getOrCreateFlowGroupId(String flowId) {
        return transactionManager.doInTransaction(() -> findById(flowId)
                .map(diverseFlow -> {
                    if (diverseFlow.getGroupId() == null) {
                        String groupId = UUID.randomUUID().toString();
                        diverseFlow.setGroupId(groupId);
                    }
                    return diverseFlow.getGroupId();
                }));
    }

    @Override
    public void updateStatus(@NonNull String flowId, @NonNull FlowStatus flowStatus) {
        transactionManager.doInTransaction(() ->
                framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                        .toListExplicit(FlowFrame.class)
                        .forEach(flowFrame -> {
                            flowFrame.setStatus(flowStatus);
                        }));
    }

    @Override
    public void updateStatus(String flowId, FlowStatus flowStatus, String flowStatusInfo) {
        transactionManager.doInTransaction(() ->
                framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                        .toListExplicit(FlowFrame.class)
                        .forEach(flowFrame -> {
                            flowFrame.setStatus(flowStatus);
                            flowFrame.setStatusInfo(flowStatusInfo);
                        }));
    }

    @Override
    public void updateStatusInfo(String flowId, String flowStatusInfo) {
        transactionManager.doInTransaction(() ->
                framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                        .toListExplicit(FlowFrame.class)
                        .forEach(flowFrame -> {
                            flowFrame.setStatusInfo(flowStatusInfo);
                        }));
    }

    /**
     * Flow in "IN_PROGRESS" status can be switched to other status only inside flow CRUD handlers topology. All other
     * components must use this method, which guarantee safety such flows status.
     */
    @Override
    @TransactionRequired
    public void updateStatusSafe(Flow flow, FlowStatus flowStatus, String flowStatusInfo) {
        if (flow.getStatus() != FlowStatus.IN_PROGRESS) {
            flow.setStatus(flowStatus);
            flow.setStatusInfo(flowStatusInfo);
        }
    }

    @Override
    public long computeFlowsBandwidthSum(Set<String> flowIds) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, P.within(flowIds))
                .values(FlowFrame.BANDWIDTH_PROPERTY).sum())
                .getRawTraversal()) {
            return traversal.tryNext()
                    .filter(n -> !(n instanceof Double && ((Double) n).isNaN()))
                    .map(l -> (Long) l)
                    .orElse(0L);
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Optional<Flow> remove(String flowId) {
        if (transactionManager.isTxOpen()) {
            // This implementation removes dependant entities (paths, segments) in a separate transactions,
            // so the flow entity may require to be reloaded in a case of failed transaction.
            throw new IllegalStateException("This implementation of remove requires no outside transaction");
        }

        transactionManager.doInTransaction(() ->
                framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                        .toListExplicit(FlowFrame.class)
                        .forEach(flowFrame -> {
                            // Unlink the flow endpoints
                            flowFrame.getElement().edges(Direction.OUT,
                                    FlowFrame.SOURCE_EDGE, FlowFrame.DESTINATION_EDGE)
                                    .forEachRemaining(Edge::remove);

                            flowFrame.traverse(v -> v.out(FlowFrame.OWNS_PATHS_EDGE)
                                    .hasLabel(FlowPathFrame.FRAME_LABEL))
                                    .toListExplicit(FlowPathFrame.class)
                                    .forEach(pathFrame -> {
                                        // Unlink the path endpoints
                                        pathFrame.getElement().edges(Direction.OUT,
                                                FlowPathFrame.SOURCE_EDGE, FlowPathFrame.DESTINATION_EDGE)
                                                .forEachRemaining(Edge::remove);

                                        pathFrame.traverse(v -> v.out(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                                                .hasLabel(PathSegmentFrame.FRAME_LABEL))
                                                .toListExplicit(PathSegmentFrame.class)
                                                .forEach(segmentFrame ->
                                                        // Unlink the segments' endpoints
                                                        segmentFrame.getElement().edges(Direction.OUT,
                                                                PathSegmentFrame.SOURCE_EDGE,
                                                                PathSegmentFrame.DESTINATION_EDGE)
                                                                .forEachRemaining(Edge::remove));
                                    });
                        }));

        return transactionManager.doInTransaction(() ->
                findById(flowId)
                        .map(flow -> {
                            remove(flow);
                            return flow;
                        }));
    }

    @Override
    public Collection<Flow> findLoopedByFlowIdAndLoopSwitchId(String flowId, SwitchId switchId) {
        String switchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);

        return framedGraph().traverse(g -> {
            GraphTraversal<Vertex, Vertex> traversal = g.V()
                    .hasLabel(FlowFrame.FRAME_LABEL)
                    .has(FlowFrame.FLOW_ID_PROPERTY, flowId);
            if (switchId != null) {
                traversal.has(FlowFrame.LOOP_SWITCH_ID_PROPERTY, P.eq(switchIdAsStr));
            } else {
                traversal.has(FlowFrame.LOOP_SWITCH_ID_PROPERTY, P.neq(null));
            }
            return traversal;
        }).toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Flow> findLoopedByLoopSwitchId(SwitchId switchId) {
        if (switchId == null) {
            return framedGraph().traverse(g -> g.V()
                    .hasLabel(FlowFrame.FRAME_LABEL)
                    .has(FlowFrame.LOOP_SWITCH_ID_PROPERTY, P.neq(null)))
                    .toListExplicit(FlowFrame.class).stream()
                    .map(Flow::new)
                    .collect(Collectors.toList());
        }

        String switchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);

        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.LOOP_SWITCH_ID_PROPERTY, switchIdAsStr))
                .toListExplicit(FlowFrame.class).stream()
                .map(Flow::new)
                .collect(Collectors.toList());
    }

    @Override
    protected FlowFrame doAdd(FlowData data) {
        FlowFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), FlowFrame.FRAME_LABEL,
                FlowFrame.class);
        Flow.FlowCloner.INSTANCE.copyWithoutPaths(data, frame);
        frame.addPaths(data.getPaths().stream()
                .peek(path -> {
                    if (!(path.getData() instanceof FlowPathFrame)) {
                        flowPathRepository.add(path);
                    }
                })
                .toArray(FlowPath[]::new));
        return frame;
    }

    @Override
    protected void doRemove(FlowFrame frame) {
        frame.getPaths().forEach(path -> {
            if (path.getData() instanceof FlowPathFrame) {
                flowPathRepository.remove(path);
            }
        });
        frame.remove();
    }

    @Override
    protected FlowData doDetach(Flow entity, FlowFrame frame) {
        FlowData data = Flow.FlowCloner.INSTANCE.copyWithoutPaths(frame, entity);
        data.addPaths(frame.getPaths().stream()
                .map(path -> new FlowPath(path, entity))
                .toArray(FlowPath[]::new));
        return data;
    }
}
