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

package org.openkilda.persistence.ferma.repositories.frames;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.model.Flow;
import org.openkilda.persistence.ferma.model.FlowPath;
import org.openkilda.persistence.ferma.model.Switch;

import com.syncleus.ferma.AbstractElementFrame;
import com.syncleus.ferma.AbstractVertexFrame;
import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.VertexFrame;
import org.apache.tinkerpop.gremlin.neo4j.structure.Neo4jVertex;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class FlowFrame extends AbstractVertexFrame implements Flow {
    public static final String FRAME_LABEL = "flow";

    public static final String SOURCE_EDGE = "source";
    public static final String DESTINATION_EDGE = "destination";
    static final String OWNS_PATHS_EDGE = "owns";

    public static final String FLOW_ID_PROPERTY = "flow_id";
    public static final String SRC_PORT_PROPERTY = "src_port";
    public static final String DST_PORT_PROPERTY = "dst_port";
    public static final String GROUP_ID_PROPERTY = "group_id";
    public static final String PERIODIC_PINGS_PROPERTY = "periodic_pings";
    public static final String STATUS_PROPERTY = "status";

    private Vertex cachedElement;

    @Override
    public Vertex getElement() {
        // A workaround for the issue with neo4j-gremlin and Ferma integration.
        if (cachedElement == null) {
            try {
                java.lang.reflect.Field field = AbstractElementFrame.class.getDeclaredField("element");
                field.setAccessible(true);
                Object value = field.get(this);
                field.setAccessible(false);
                if (value instanceof Neo4jVertex) {
                    cachedElement = (Vertex) value;
                }
            } catch (NoSuchFieldException | IllegalAccessException ex) {
                // just ignore
            }

            if (cachedElement == null) {
                cachedElement = super.getElement();
            }
        }

        return cachedElement;
    }

    @Override
    public String getFlowId() {
        return getProperty(FLOW_ID_PROPERTY);
    }

    @Override
    public void setFlowId(String flowId) {
        setProperty(FLOW_ID_PROPERTY, flowId);
    }

    @Override
    public int getSrcPort() {
        return ((Long) getProperty(SRC_PORT_PROPERTY)).intValue();
    }

    @Override
    public void setSrcPort(int srcPort) {
        setProperty(SRC_PORT_PROPERTY, (long) srcPort);
    }

    @Override
    public int getSrcVlan() {
        return ((Long) getProperty("src_vlan")).intValue();
    }

    @Override
    public void setSrcVlan(int srcVlan) {
        setProperty("src_vlan", (long) srcVlan);
    }

    @Override
    public int getDestPort() {
        return ((Long) getProperty(DST_PORT_PROPERTY)).intValue();
    }

    @Override
    public void setDestPort(int destPort) {
        setProperty(DST_PORT_PROPERTY, (long) destPort);
    }

    @Override
    public int getDestVlan() {
        return ((Long) getProperty("dst_vlan")).intValue();
    }

    @Override
    public void setDestVlan(int destVlan) {
        setProperty("dst_vlan", (long) destVlan);
    }

    @Override
    public PathId getForwardPathId() {
        return Optional.ofNullable((String) getProperty("forward_path_id")).map(PathId::new).orElse(null);
    }

    @Override
    public void setForwardPathId(PathId forwardPathId) {
        setProperty("forward_path_id", forwardPathId == null ? null : forwardPathId.getId());
    }

    @Override
    public PathId getReversePathId() {
        return Optional.ofNullable((String) getProperty("reverse_path_id")).map(PathId::new).orElse(null);
    }

    @Override
    public void setReversePathId(PathId reversePathId) {
        setProperty("reverse_path_id", reversePathId == null ? null : reversePathId.getId());
    }

    @Override
    public boolean isAllocateProtectedPath() {
        return Optional.ofNullable((Boolean) getProperty("allocate_protected_path")).orElse(false);
    }

    @Override
    public void setAllocateProtectedPath(boolean allocateProtectedPath) {
        setProperty("allocate_protected_path", allocateProtectedPath);
    }

    @Override
    public PathId getProtectedForwardPathId() {
        return Optional.ofNullable((String) getProperty("protected_forward_path_id"))
                .map(PathId::new).orElse(null);
    }

    @Override
    public void setProtectedForwardPathId(PathId protectedForwardPathId) {
        setProperty("protected_forward_path_id",
                protectedForwardPathId == null ? null : protectedForwardPathId.getId());
    }

    @Override
    public PathId getProtectedReversePathId() {
        return Optional.ofNullable((String) getProperty("protected_reverse_path_id"))
                .map(PathId::new).orElse(null);
    }

    @Override
    public void setProtectedReversePathId(PathId protectedReversePathId) {
        setProperty("protected_reverse_path_id",
                protectedReversePathId == null ? null : protectedReversePathId.getId());
    }

    @Override
    public String getGroupId() {
        return getProperty(GROUP_ID_PROPERTY);
    }

    @Override
    public void setGroupId(String groupId) {
        setProperty(GROUP_ID_PROPERTY, groupId);
    }

    @Override
    public long getBandwidth() {
        return (Long) getProperty("bandwidth");
    }

    @Override
    public void setBandwidth(long bandwidth) {
        setProperty("bandwidth", bandwidth);
    }

    @Override
    public boolean isIgnoreBandwidth() {
        return Optional.ofNullable((Boolean) getProperty("ignore_bandwidth")).orElse(false);
    }

    @Override
    public void setIgnoreBandwidth(boolean ignoreBandwidth) {
        setProperty("ignore_bandwidth", ignoreBandwidth);
    }

    @Override
    public String getDescription() {
        return getProperty("description");
    }

    @Override
    public void setDescription(String description) {
        setProperty("description", description);
    }

    @Override
    public boolean isPeriodicPings() {
        return Optional.ofNullable((Boolean) getProperty(PERIODIC_PINGS_PROPERTY)).orElse(false);
    }

    @Override
    public void setPeriodicPings(boolean periodicPings) {
        setProperty(PERIODIC_PINGS_PROPERTY, periodicPings);
    }

    @Override
    public FlowEncapsulationType getEncapsulationType() {
        String value = getProperty("encapsulation_type");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return FlowEncapsulationType.valueOf(value.toUpperCase());
    }

    @Override
    public void setEncapsulationType(FlowEncapsulationType encapsulationType) {
        setProperty("encapsulation_type", encapsulationType == null ? null : encapsulationType.name().toLowerCase());
    }

    @Override
    public FlowStatus getStatus() {
        String value = getProperty(STATUS_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return FlowStatus.valueOf(value.toUpperCase());
    }

    @Override
    public void setStatus(FlowStatus status) {
        setProperty(STATUS_PROPERTY, status == null ? null : status.name().toLowerCase());
    }

    @Override
    public Integer getMaxLatency() {
        Long value = getProperty("max_latency");
        return value == null ? null : value.intValue();
    }

    @Override
    public void setMaxLatency(Integer maxLatency) {
        setProperty("max_latency", maxLatency == null ? null : maxLatency.longValue());
    }

    @Override
    public Integer getPriority() {
        Long value = getProperty("priority");
        return value == null ? null : value.intValue();
    }

    @Override
    public void setPriority(Integer priority) {
        setProperty("priority", priority == null ? null : priority.longValue());
    }

    @Override
    public Instant getTimeCreate() {
        String value = getProperty("time_create");
        return value == null ? null : Instant.parse(value);
    }

    @Override
    public void setTimeCreate(Instant timeCreate) {
        setProperty("time_create", timeCreate == null ? null : timeCreate.toString());
    }

    @Override
    public Instant getTimeModify() {
        String value = getProperty("time_modify");
        return value == null ? null : Instant.parse(value);
    }

    @Override
    public void setTimeModify(Instant timeModify) {
        setProperty("time_modify", timeModify == null ? null : timeModify.toString());
    }

    @Override
    public boolean isPinned() {
        return Optional.ofNullable((Boolean) getProperty("pinned")).orElse(false);
    }

    @Override
    public void setPinned(boolean pinned) {
        setProperty("pinned", pinned);
    }

    @Override
    public Switch getSrcSwitch() {
        return traverse(v -> v.out(SOURCE_EDGE).hasLabel(SwitchFrame.FRAME_LABEL)).nextExplicit(SwitchFrame.class);
    }

    @Override
    public SwitchId getSrcSwitchId() {
        return new SwitchId(traverse(v -> v.out(SOURCE_EDGE).hasLabel(SwitchFrame.FRAME_LABEL)
                .values(SwitchFrame.SWITCH_ID_PROPERTY)).nextExplicit(String.class));
    }

    @Override
    public void setSrcSwitch(Switch srcSwitch) {
        getElement().edges(Direction.OUT, SOURCE_EDGE).forEachRemaining(edge -> edge.remove());

        if (srcSwitch instanceof VertexFrame) {
            linkOut((VertexFrame) srcSwitch, SOURCE_EDGE);
        } else {
            SwitchFrame switchFrame = SwitchFrame.load(getGraph(), srcSwitch.getSwitchId());
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to link to unknown switch " + srcSwitch.getSwitchId());
            }
            linkOut(switchFrame, SOURCE_EDGE);
        }
    }

    @Override
    public Switch getDestSwitch() {
        return traverse(v -> v.out(DESTINATION_EDGE).hasLabel(SwitchFrame.FRAME_LABEL)).nextExplicit(SwitchFrame.class);
    }

    @Override
    public SwitchId getDestSwitchId() {
        return new SwitchId(traverse(v -> v.out(DESTINATION_EDGE).hasLabel(SwitchFrame.FRAME_LABEL)
                .values(SwitchFrame.SWITCH_ID_PROPERTY)).nextExplicit(String.class));
    }

    @Override
    public void setDestSwitch(Switch destSwitch) {
        getElement().edges(Direction.OUT, DESTINATION_EDGE).forEachRemaining(edge -> edge.remove());

        if (destSwitch instanceof VertexFrame) {
            linkOut((VertexFrame) destSwitch, DESTINATION_EDGE);
        } else {
            SwitchFrame switchFrame = SwitchFrame.load(getGraph(), destSwitch.getSwitchId());
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to link to unknown switch " + destSwitch.getSwitchId());
            }

            linkOut(switchFrame, DESTINATION_EDGE);
        }
    }

    @Override
    public Set<FlowPath> getPaths() {
        if (getElement().edges(Direction.OUT, OWNS_PATHS_EDGE).hasNext()) {
            return Collections.unmodifiableSet(traverse(v -> v.out(OWNS_PATHS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL))
                    .toSetExplicit(FlowPathFrame.class));
        } else {
            return Collections.emptySet();
        }
    }

    @Override
    public Set<PathId> getPathIds() {
        return Collections.unmodifiableSet(traverse(v -> v.out(OWNS_PATHS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL)
                .values(FlowPathFrame.PATH_ID_PROPERTY))
                .toSetExplicit(PathId.class));
    }

    @Override
    public Optional<FlowPath> getPath(PathId pathId) {
        return Optional.ofNullable(
                traverse(v -> v.out(OWNS_PATHS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL)
                        .has(FlowPathFrame.PATH_ID_PROPERTY, pathId))
                        .nextOrDefaultExplicit(FlowPathFrame.class, null));
    }

    @Override
    public PathId getOppositePathId(PathId pathId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void setPaths(Set<FlowPath> paths) {
        boolean isNonEmpty = getElement().edges(Direction.OUT, OWNS_PATHS_EDGE).hasNext();

        addPaths(paths.toArray(new FlowPath[paths.size()]));

        if (isNonEmpty) {
            Set<PathId> actualPathIds = paths.stream().map(FlowPath::getPathId).collect(Collectors.toSet());

            traverse(v -> v.out(OWNS_PATHS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL))
                    .toListExplicit(FlowPathFrame.class)
                    .forEach(pathFrame -> {
                        if (!actualPathIds.contains(pathFrame.getPathId())) {
                            unlinkOut(pathFrame, OWNS_PATHS_EDGE);
                            pathFrame.delete();
                        }
                    });
        }
    }

    @Override
    public void addPaths(FlowPath... paths) {
        for (FlowPath path : paths) {
            if (path instanceof FlowPathFrame) {
                linkOut((VertexFrame) path, OWNS_PATHS_EDGE);
            } else {
                FlowPathFrame pathFrame = FlowPathFrame.load(getGraph(), path.getPathId());
                if (pathFrame != null) {
                    pathFrame.updateWith(path);
                } else {
                    pathFrame = FlowPathFrame.addNew(getGraph(), path);
                }
                linkOut(pathFrame, OWNS_PATHS_EDGE);
            }
        }
    }

    @Override
    public void removePaths(PathId... pathIds) {
        Set<PathId> pathIdsToRemove = new HashSet(Arrays.asList(pathIds));

        traverse(v -> v.out(OWNS_PATHS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL))
                .toListExplicit(FlowPathFrame.class)
                .forEach(pathFrame -> {
                    if (pathIdsToRemove.contains(pathFrame.getPathId())) {
                        unlinkOut(pathFrame, OWNS_PATHS_EDGE);
                        pathFrame.delete();
                    }
                });
    }

    public void updateWith(Flow flow) {
        setSrcPort(flow.getSrcPort());
        setSrcVlan(flow.getSrcVlan());
        setDestPort(flow.getDestPort());
        setDestVlan(flow.getDestVlan());
        setForwardPathId(flow.getForwardPathId());
        setReversePathId(flow.getReversePathId());
        setAllocateProtectedPath(flow.isAllocateProtectedPath());
        setProtectedForwardPathId(flow.getProtectedForwardPathId());
        setProtectedReversePathId(flow.getProtectedReversePathId());
        setGroupId(flow.getGroupId());
        setBandwidth(flow.getBandwidth());
        setIgnoreBandwidth(flow.isIgnoreBandwidth());
        setDescription(flow.getDescription());
        setPeriodicPings(flow.isPeriodicPings());
        setEncapsulationType(flow.getEncapsulationType());
        setStatus(flow.getStatus());
        setMaxLatency(flow.getMaxLatency());
        setPriority(flow.getPriority());
        setTimeModify(flow.getTimeModify());
        setPinned(flow.isPinned());
        setSrcSwitch(flow.getSrcSwitch());
        setDestSwitch(flow.getDestSwitch());
        setPaths(getPaths());
    }

    public void delete() {
        traverse(v -> v.out(OWNS_PATHS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL)).toListExplicit(FlowPathFrame.class)
                .forEach(pathFrame -> pathFrame.delete());
        remove();
    }

    public static FlowFrame addNew(FramedGraph graph, Flow newFlow) {
        // A workaround for improper implementation of the untyped mode in OrientTransactionFactoryImpl.
        Vertex element = ((DelegatingFramedGraph) graph).getBaseGraph().addVertex(T.label, FRAME_LABEL);
        FlowFrame frame = graph.frameElementExplicit(element, FlowFrame.class);
        frame.setFlowId(newFlow.getFlowId());
        frame.setTimeCreate(newFlow.getTimeCreate());
        frame.updateWith(newFlow);
        return frame;
    }

    public static FlowFrame load(FramedGraph graph, String flowId) {
        return graph.traverse(input -> input.V().hasLabel(FRAME_LABEL).has(FLOW_ID_PROPERTY, flowId))
                .nextOrDefaultExplicit(FlowFrame.class, null);
    }

    public static void delete(FramedGraph graph, Flow flow) {
        if (flow instanceof FlowFrame) {
            ((FlowFrame) flow).delete();
        } else {
            FlowFrame flowFrame = load(graph, flow.getFlowId());
            if (flowFrame != null) {
                flowFrame.delete();
            }
        }
    }
}
