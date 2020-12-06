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

package org.openkilda.persistence.ferma.frames;

import static java.lang.String.format;

import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.PathSegment.PathSegmentData;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;

import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.annotations.Property;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

@Slf4j
public abstract class PathSegmentFrame extends KildaBaseVertexFrame implements PathSegmentData {
    public static final String FRAME_LABEL = "path_segment";
    public static final String SOURCE_EDGE = "source";
    public static final String DESTINATION_EDGE = "destination";
    public static final String PATH_ID_PROPERTY = "path_id";
    public static final String SRC_SWITCH_ID_PROPERTY = "src_switch_id";
    public static final String DST_SWITCH_ID_PROPERTY = "dst_switch_id";
    public static final String SRC_PORT_PROPERTY = "src_port";
    public static final String DST_PORT_PROPERTY = "dst_port";
    public static final String SRC_W_MULTI_TABLE_PROPERTY = "src_with_multi_table";
    public static final String DST_W_MULTI_TABLE_PROPERTY = "dst_with_multi_table";
    public static final String IGNORE_BANDWIDTH_PROPERTY = "ignore_bandwidth";
    public static final String BANDWIDTH_PROPERTY = "bandwidth";

    private Switch srcSwitch;
    private Switch destSwitch;
    private FlowPath path;

    @Override
    @Property(PATH_ID_PROPERTY)
    @Convert(PathIdConverter.class)
    public abstract PathId getPathId();

    @Override
    @Property(SRC_SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getSrcSwitchId();

    @Override
    @Property(DST_SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getDestSwitchId();

    @Override
    @Property(SRC_PORT_PROPERTY)
    public abstract int getSrcPort();

    @Override
    @Property(SRC_PORT_PROPERTY)
    public abstract void setSrcPort(int srcPort);

    @Override
    @Property(DST_PORT_PROPERTY)
    public abstract int getDestPort();

    @Override
    @Property(DST_PORT_PROPERTY)
    public abstract void setDestPort(int destPort);

    @Override
    @Property("seq_id")
    public abstract int getSeqId();

    @Override
    @Property("seq_id")
    public abstract void setSeqId(int seqId);

    @Override
    @Property("latency")
    public abstract Long getLatency();

    @Override
    @Property("latency")
    public abstract void setLatency(Long latency);

    @Override
    @Property(BANDWIDTH_PROPERTY)
    public abstract long getBandwidth();

    @Override
    @Property(BANDWIDTH_PROPERTY)
    public abstract void setBandwidth(long bandwidth);

    @Override
    @Property(IGNORE_BANDWIDTH_PROPERTY)
    public abstract boolean isIgnoreBandwidth();

    @Override
    @Property(IGNORE_BANDWIDTH_PROPERTY)
    public abstract void setIgnoreBandwidth(boolean ignoreBandwidth);

    @Override
    @Property("failed")
    public abstract boolean isFailed();

    @Override
    @Property("failed")
    public abstract void setFailed(boolean failed);

    @Override
    public Switch getSrcSwitch() {
        if (srcSwitch == null) {
            List<? extends SwitchFrame> switchFrames = traverse(v -> v.out(SOURCE_EDGE)
                    .hasLabel(SwitchFrame.FRAME_LABEL))
                    .toListExplicit(SwitchFrame.class);
            if (!switchFrames.isEmpty()) {
                srcSwitch = new Switch((switchFrames.get(0)));

                if (!Objects.equals(getSrcSwitchId(), srcSwitch.getSwitchId())) {
                    throw new IllegalStateException(format("The path segment %s has inconsistent source switch %s / %s",
                            getId(), getSrcSwitchId(), srcSwitch.getSwitchId()));
                }
            } else {
                String switchId = getProperty(SRC_SWITCH_ID_PROPERTY);
                log.warn("Fallback to find the source switch by a reference instead of an edge. "
                        + "The switch {}, the vertex {}", switchId, this);
                srcSwitch = SwitchFrame.load(getGraph(), switchId)
                        .map(Switch::new).orElse(null);
            }
        }
        return srcSwitch;
    }

    @Override
    public void setSrcSwitch(Switch srcSwitch) {
        this.srcSwitch = srcSwitch;
        String switchId = SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitch.getSwitchId());
        setProperty(SRC_SWITCH_ID_PROPERTY, switchId);

        //        getElement().edges(Direction.OUT, SOURCE_EDGE).forEachRemaining(Edge::remove);
        //        Switch.SwitchData data = srcSwitch.getData();
        //        if (data instanceof SwitchFrame) {
        //            linkOut((VertexFrame) data, SOURCE_EDGE);
        //        } else {
        //            SwitchFrame frame = SwitchFrame.load(getGraph(), switchId).orElseThrow(() ->
        //                    new IllegalArgumentException("Unable to link to non-existent switch " + srcSwitch));
        //            linkOut(frame, SOURCE_EDGE);
        //        }
    }

    @Override
    public Switch getDestSwitch() {
        if (destSwitch == null) {
            List<? extends SwitchFrame> switchFrames = traverse(v -> v.out(DESTINATION_EDGE)
                    .hasLabel(SwitchFrame.FRAME_LABEL))
                    .toListExplicit(SwitchFrame.class);
            if (!switchFrames.isEmpty()) {
                destSwitch = new Switch((switchFrames.get(0)));
                if (!Objects.equals(getDestSwitchId(), destSwitch.getSwitchId())) {
                    throw new IllegalStateException(format("The path segment %s has inconsistent dest switch %s / %s",
                            getId(), getDestSwitchId(), destSwitch.getSwitchId()));
                }
            } else {
                String switchId = getProperty(DST_SWITCH_ID_PROPERTY);
                log.warn("Fallback to find the dest switch by a reference instead of an edge. "
                        + "The switch {}, the vertex {}", switchId, this);
                destSwitch = SwitchFrame.load(getGraph(), switchId)
                        .map(Switch::new).orElse(null);
            }
        }
        return destSwitch;
    }

    @Override
    public void setDestSwitch(Switch destSwitch) {
        this.destSwitch = destSwitch;
        String switchId = SwitchIdConverter.INSTANCE.toGraphProperty(destSwitch.getSwitchId());
        setProperty(DST_SWITCH_ID_PROPERTY, switchId);

        //        getElement().edges(Direction.OUT, DESTINATION_EDGE).forEachRemaining(Edge::remove);
        //        Switch.SwitchData data = destSwitch.getData();
        //        if (data instanceof SwitchFrame) {
        //            linkOut((VertexFrame) data, DESTINATION_EDGE);
        //        } else {
        //            SwitchFrame frame = SwitchFrame.load(getGraph(), switchId).orElseThrow(() ->
        //                    new IllegalArgumentException("Unable to link to non-existent switch " + destSwitch));
        //            linkOut(frame, DESTINATION_EDGE);
        //        }
    }

    @Override
    public FlowPath getPath() {
        if (path == null) {
            List<? extends FlowPathFrame> flowPathFrames = traverse(v -> v.in(FlowPathFrame.OWNS_SEGMENTS_EDGE)
                    .hasLabel(FlowPathFrame.FRAME_LABEL))
                    .toListExplicit(FlowPathFrame.class);
            path = !flowPathFrames.isEmpty() ? new FlowPath(flowPathFrames.get(0)) : null;
            PathId pathId = path != null ? path.getPathId() : null;
            if (!Objects.equals(getPathId(), pathId)) {
                throw new IllegalStateException(format("The path segment %s has inconsistent path_id %s / %s",
                        getId(), getPathId(), pathId));
            }
        }
        return path;
    }

    @Override
    @Property(SRC_W_MULTI_TABLE_PROPERTY)
    public abstract boolean isSrcWithMultiTable();

    @Override
    @Property(SRC_W_MULTI_TABLE_PROPERTY)
    public abstract void setSrcWithMultiTable(boolean srcWithMultiTable);

    @Override
    @Property(DST_W_MULTI_TABLE_PROPERTY)
    public abstract boolean isDestWithMultiTable();

    @Override
    @Property(DST_W_MULTI_TABLE_PROPERTY)
    public abstract void setDestWithMultiTable(boolean destWithMultiTable);

    public static PathSegmentFrame create(FramedGraph framedGraph, PathSegmentData data) {
        PathSegmentFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph, FRAME_LABEL,
                PathSegmentFrame.class);
        PathSegment.PathSegmentCloner.INSTANCE.copy(data, frame);
        return frame;
    }
}
