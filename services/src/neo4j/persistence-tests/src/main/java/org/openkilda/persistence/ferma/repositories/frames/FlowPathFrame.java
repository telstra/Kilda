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

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.model.FlowPath;
import org.openkilda.persistence.ferma.model.PathSegment;
import org.openkilda.persistence.ferma.model.Switch;

import com.syncleus.ferma.AbstractElementFrame;
import com.syncleus.ferma.AbstractVertexFrame;
import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.VertexFrame;
import lombok.NonNull;
import org.apache.tinkerpop.gremlin.neo4j.structure.Neo4jVertex;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class FlowPathFrame extends AbstractVertexFrame implements FlowPath {
    public static final String FRAME_LABEL = "flow_path";

    static final String SOURCE_EDGE = "source";
    static final String DESTINATION_EDGE = "destination";
    static final String OWNS_SEGMENTS_EDGE = "owns";

    public static final String PATH_ID_PROPERTY = "path_id";

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
    public PathId getPathId() {
        return new PathId(getProperty(PATH_ID_PROPERTY));
    }

    @Override
    public void setPathId(@NonNull PathId pathId) {
        setProperty(PATH_ID_PROPERTY, pathId.getId());
    }

    @Override
    public Cookie getCookie() {
        return Optional.ofNullable((Long) getProperty("cookie")).map(Cookie::new).orElse(null);
    }

    @Override
    public void setCookie(Cookie cookie) {
        setProperty("cookie", cookie == null ? null : cookie.getValue());
    }

    @Override
    public MeterId getMeterId() {
        return Optional.ofNullable((Long) getProperty("meter_id")).map(MeterId::new).orElse(null);
    }

    @Override
    public void setMeterId(MeterId meterId) {
        setProperty("meter_id", meterId == null ? null : meterId.getValue());
    }

    @Override
    public long getLatency() {
        return (Long) getProperty("latency");
    }

    @Override
    public void setLatency(long latency) {
        setProperty("latency", latency);
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
    public FlowPathStatus getStatus() {
        String value = getProperty("status");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return FlowPathStatus.valueOf(value.toUpperCase());
    }

    @Override
    public void setStatus(FlowPathStatus status) {
        setProperty("status", status == null ? null : status.name().toLowerCase());
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
    public List<PathSegment> getPathSegments() {
        return traverse(v -> v.out(OWNS_SEGMENTS_EDGE).hasLabel(PathSegmentFrame.FRAME_LABEL))
                .toListExplicit(PathSegmentFrame.class)
                .stream()
                .sorted(Comparator.comparingInt(PathSegment::getSeqId))
                .collect(Collectors.toList());
    }

    @Override
    public void setPathSegments(List<PathSegment> segments) {
        getElement().edges(Direction.OUT, OWNS_SEGMENTS_EDGE).forEachRemaining(edge -> {
            edge.inVertex().remove();
            edge.remove();
        });

        for (int idx = 0; idx < segments.size(); idx++) {
            PathSegment segment = segments.get(idx);
            segment.setSeqId(idx);

            if (segment instanceof PathSegmentFrame) {
                linkOut((VertexFrame) segment, OWNS_SEGMENTS_EDGE);
            } else {
                PathSegmentFrame segmentFrame = PathSegmentFrame.addNew(getGraph(), segment);
                linkOut(segmentFrame, OWNS_SEGMENTS_EDGE);
            }
        }
    }

    @Override
    public FlowFrame getFlow() {
        return traverse(v -> v.in(FlowFrame.OWNS_PATHS_EDGE).hasLabel(FlowFrame.FRAME_LABEL))
                .nextExplicit(FlowFrame.class);
    }

    public void updateWith(FlowPath path) {
        setCookie(path.getCookie());
        setMeterId(path.getMeterId());
        setLatency(path.getLatency());
        setBandwidth(path.getBandwidth());
        setIgnoreBandwidth(path.isIgnoreBandwidth());
        setTimeModify(path.getTimeModify());
        setStatus(path.getStatus());
        setSrcSwitch(path.getSrcSwitch());
        setDestSwitch(path.getDestSwitch());
        setPathSegments(path.getPathSegments());
    }

    public void delete() {
        traverse(v -> v.out(OWNS_SEGMENTS_EDGE).hasLabel(PathSegmentFrame.FRAME_LABEL))
                .toListExplicit(PathSegmentFrame.class)
                .forEach(segmentFrame -> segmentFrame.delete());
        remove();
    }

    public static FlowPathFrame addNew(FramedGraph graph, FlowPath newPath) {
        // A workaround for improper implementation of the untyped mode in OrientTransactionFactoryImpl.
        Vertex element = ((DelegatingFramedGraph) graph).getBaseGraph().addVertex(T.label, FRAME_LABEL);
        FlowPathFrame frame = graph.frameElementExplicit(element, FlowPathFrame.class);
        frame.setPathId(newPath.getPathId());
        frame.setTimeCreate(newPath.getTimeCreate());
        frame.updateWith(newPath);
        return frame;
    }

    public static FlowPathFrame load(FramedGraph graph, PathId pathId) {
        return graph.traverse(input -> input.V().hasLabel(FRAME_LABEL).has(PATH_ID_PROPERTY, pathId))
                .nextOrDefaultExplicit(FlowPathFrame.class, null);
    }
}
