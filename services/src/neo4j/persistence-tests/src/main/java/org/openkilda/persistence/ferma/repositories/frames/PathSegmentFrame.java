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

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.model.FlowPath;
import org.openkilda.persistence.ferma.model.PathSegment;
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

import java.util.Optional;

public class PathSegmentFrame extends AbstractVertexFrame implements PathSegment {
    public static final String FRAME_LABEL = "path_segment";

    static final String SOURCE_EDGE = "source";
    static final String DESTINATION_EDGE = "destination";

    static final String SRC_PORT_PROPERTY = "src_port";
    static final String DST_PORT_PROPERTY = "dst_port";

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
    public int getSrcPort() {
        return ((Long) getProperty(SRC_PORT_PROPERTY)).intValue();
    }

    @Override
    public void setSrcPort(int srcPort) {
        setProperty(SRC_PORT_PROPERTY, (long) srcPort);
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
    public int getSeqId() {
        return ((Long) getProperty("seq_id")).intValue();
    }

    @Override
    public void setSeqId(int seqId) {
        setProperty("seq_id", (long) seqId);
    }

    @Override
    public Long getLatency() {
        return (Long) getProperty("latency");
    }

    @Override
    public void setLatency(Long latency) {
        setProperty("latency", latency);
    }

    @Override
    public boolean isFailed() {
        return Optional.ofNullable((Boolean) getProperty("failed")).orElse(false);
    }

    @Override
    public void setFailed(boolean failed) {
        setProperty("failed", failed);
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
    public FlowPath getPath() {
        return traverse(v -> v.in(FlowPathFrame.OWNS_SEGMENTS_EDGE).hasLabel(FlowPathFrame.FRAME_LABEL))
                .nextExplicit(FlowPathFrame.class);
    }

    public void updateWith(PathSegment segment) {
        setSrcPort(segment.getSrcPort());
        setDestPort(segment.getDestPort());
        setLatency(segment.getLatency());
        setFailed(segment.isFailed());
        setSeqId(segment.getSeqId());
        setSrcSwitch(segment.getSrcSwitch());
        setDestSwitch(segment.getDestSwitch());
    }

    public void delete() {
        remove();
    }

    public static PathSegmentFrame addNew(FramedGraph graph, PathSegment newSegment) {
        // A workaround for improper implementation of the untyped mode in OrientTransactionFactoryImpl.
        Vertex element = ((DelegatingFramedGraph) graph).getBaseGraph().addVertex(T.label, FRAME_LABEL);
        PathSegmentFrame frame = graph.frameElementExplicit(element, PathSegmentFrame.class);
        frame.updateWith(newSegment);
        return frame;
    }
}
