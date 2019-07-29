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
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowFrame;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.SwitchFrame;
import org.openkilda.persistence.ferma.frames.converters.FlowStatusConverter;
import org.openkilda.persistence.repositories.FlowRepository;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ferma implementation of {@link FlowRepository}.
 */
@Slf4j
class FermaFlowRepository extends FermaGenericRepository<Flow, FlowData, FlowFrame> implements FlowRepository {
    FermaFlowPathRepository fermaFlowPathRepository;

    FermaFlowRepository(FramedGraphFactory<?> graphFactory, FermaFlowPathRepository fermaFlowPathRepository,
                        TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
        this.fermaFlowPathRepository = fermaFlowPathRepository;
    }

    @Override
    public long countFlows() {
        return (Long) framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL).count())
                .getRawTraversal().next();
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
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                .getRawTraversal().hasNext();
    }

    @Override
    public Optional<Flow> findById(String flowId) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                .nextOrDefaultExplicit(FlowFrame.class, null))
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
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_PORT_PROPERTY, port))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_PORT_PROPERTY, port))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Optional<Flow> findByEndpointAndVlan(SwitchId switchId, int port, int vlan) {
        FlowFrame result = framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_PORT_PROPERTY, port)
                .has(FlowFrame.SRC_VLAN_PROPERTY, vlan))
                .nextOrDefaultExplicit(FlowFrame.class, null);
        if (result != null) {
            return Optional.of(result).map(Flow::new);
        }
        result = framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_PORT_PROPERTY, port)
                .has(FlowFrame.DST_VLAN_PROPERTY, vlan))
                .nextOrDefaultExplicit(FlowFrame.class, null);
        return Optional.ofNullable(result).map(Flow::new);
    }

    @Override
    public Optional<Flow> findOneSwitchFlowBySwitchIdInPortAndOutVlan(SwitchId switchId, int inPort, int outVlan) {
        FlowFrame result = framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .as("src")
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_PORT_PROPERTY, inPort)
                .has(FlowFrame.DST_VLAN_PROPERTY, outVlan)
                .as("flow")
                .out(FlowFrame.DESTINATION_EDGE)
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .where(P.eq("src"))
                .select("flow"))
                .nextOrDefaultExplicit(FlowFrame.class, null);
        if (result != null) {
            return Optional.of(result).map(Flow::new);
        }
        result = framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .as("dest")
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_PORT_PROPERTY, inPort)
                .has(FlowFrame.SRC_VLAN_PROPERTY, outVlan)
                .as("flow")
                .out(FlowFrame.SOURCE_EDGE)
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .where(P.eq("dest"))
                .select("flow"))
                .nextOrDefaultExplicit(FlowFrame.class, null);
        return Optional.ofNullable(result).map(Flow::new);
    }

    @Override
    public Collection<Flow> findByEndpointWithMultiTableSupport(SwitchId switchId, int port) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_PORT_PROPERTY, port)
                .has(FlowFrame.SRC_MULTI_TABLE_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_PORT_PROPERTY, port)
                .has(FlowFrame.DST_MULTI_TABLE_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<String> findFlowsIdsByEndpointWithMultiTableSupport(SwitchId switchId, int port) {
        Set<String> result = new HashSet<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_PORT_PROPERTY, port)
                .has(FlowFrame.SRC_MULTI_TABLE_PROPERTY, true)
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .forEach(i -> result.add((String) i));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_PORT_PROPERTY, port)
                .has(FlowFrame.DST_MULTI_TABLE_PROPERTY, true)
                .values(FlowFrame.FLOW_ID_PROPERTY))
                .getRawTraversal().toStream()
                .forEach(i -> result.add((String) i));
        return result;
    }

    @Override
    public Collection<Flow> findByEndpointSwitch(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findByEndpointSwitchWithMultiTableSupport(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_MULTI_TABLE_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_MULTI_TABLE_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findByEndpointSwitchWithEnabledLldp(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_LLDP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_LLDP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findByEndpointSwitchWithEnabledArp(SwitchId switchId) {
        Map<String, Flow> result = new HashMap<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.SOURCE_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.SRC_ARP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .in(FlowFrame.DESTINATION_EDGE)
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.DST_ARP_PROPERTY, true))
                .frameExplicit(FlowFrame.class)
                .forEachRemaining(frame -> result.put(frame.getFlowId(), new Flow(frame)));
        return result.values();
    }

    @Override
    public Collection<Flow> findDownFlows() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.STATUS_PROPERTY, FlowStatusConverter.INSTANCE.toGraphProperty(FlowStatus.DOWN)))
                .toListExplicit(FlowFrame.class).stream()
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
                Optional.ofNullable(framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId))
                        .nextOrDefaultExplicit(FlowFrame.class, null))
                        .ifPresent(flowFrame -> {
                            flowFrame.setStatus(flowStatus);
                        }));
    }

    @Override
    public void updateStatusSafe(Flow flow, FlowStatus flowStatus) {
        if (flow.getStatus() != FlowStatus.IN_PROGRESS) {
            flow.setStatus(flowStatus);
        }
    }

    @Override
    public long computeFlowsBandwidthSum(Set<String> flowIds) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, P.within(flowIds))
                .values(FlowFrame.BANDWIDTH_PROPERTY).sum())
                .getRawTraversal().tryNext()
                .filter(n -> !(n instanceof Double && ((Double) n).isNaN()))
                .map(l -> (Long) l)
                .orElse(0L);
    }

    @Override
    protected FlowFrame doAdd(FlowData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(FlowFrame.FRAME_LABEL)
                .has(FlowFrame.FLOW_ID_PROPERTY, data.getFlowId()))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a vertex with duplicated "
                    + FlowFrame.FLOW_ID_PROPERTY);
        }

        FlowFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), FlowFrame.FRAME_LABEL,
                FlowFrame.class);
        Flow.FlowCloner.INSTANCE.copyWithoutPaths(data, frame);
        frame.addPaths(data.getPaths().stream()
                .peek(path -> {
                    if (!(path.getData() instanceof FlowPathFrame)) {
                        fermaFlowPathRepository.add(path);
                    }
                })
                .toArray(FlowPath[]::new));
        return frame;
    }

    @Override
    protected FlowData doRemove(Flow entity, FlowFrame frame) {
        FlowData data = Flow.FlowCloner.INSTANCE.copyWithoutPaths(frame, entity);
        data.addPaths(frame.getPaths().stream()
                .peek(path -> {
                    if (path.getData() instanceof FlowPathFrame) {
                        fermaFlowPathRepository.remove(path);
                    }
                })
                .toArray(FlowPath[]::new));
        frame.getElement().edges(Direction.BOTH).forEachRemaining(Edge::remove);
        frame.remove();
        return data;
    }
}
