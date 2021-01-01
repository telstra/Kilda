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

@Slf4j
public abstract class PathSegmentFrame extends KildaBaseVertexFrame implements PathSegmentData {
    public static final String FRAME_LABEL = "path_segment";
    public static final String PATH_ID_PROPERTY = "path_id";
    public static final String SRC_SWITCH_ID_PROPERTY = "src_switch_id";
    public static final String DST_SWITCH_ID_PROPERTY = "dst_switch_id";
    public static final String SRC_PORT_PROPERTY = "src_port";
    public static final String DST_PORT_PROPERTY = "dst_port";
    public static final String SRC_W_MULTI_TABLE_PROPERTY = "src_with_multi_table";
    public static final String DST_W_MULTI_TABLE_PROPERTY = "dst_with_multi_table";
    public static final String IGNORE_BANDWIDTH_PROPERTY = "ignore_bandwidth";
    public static final String BANDWIDTH_PROPERTY = "bandwidth";
    public static final String SEQ_ID_PROPERTY = "seq_id";
    public static final String LATENCY_PROPERTY = "latency";
    public static final String FAILED_PROPERTY = "failed";

    private Switch srcSwitch;
    private Switch destSwitch;

    @Override
    @Property(PATH_ID_PROPERTY)
    @Convert(PathIdConverter.class)
    public abstract PathId getPathId();

    @Override
    @Property(PATH_ID_PROPERTY)
    @Convert(PathIdConverter.class)
    public abstract void setPathId(PathId pathId);

    @Override
    @Property(SRC_SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getSrcSwitchId();

    @Override
    public void setSrcSwitchId(SwitchId srcSwitchId) {
        setProperty(SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId));
        // force to reload
        srcSwitch = null;
    }

    @Override
    @Property(DST_SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getDestSwitchId();

    @Override
    public void setDestSwitchId(SwitchId destSwitchId) {
        setProperty(DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(destSwitchId));
        // force to reload
        destSwitch = null;
    }

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
    @Property(SEQ_ID_PROPERTY)
    public abstract int getSeqId();

    @Override
    @Property(SEQ_ID_PROPERTY)
    public abstract void setSeqId(int seqId);

    @Override
    @Property(LATENCY_PROPERTY)
    public abstract Long getLatency();

    @Override
    @Property(LATENCY_PROPERTY)
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
    @Property(FAILED_PROPERTY)
    public abstract boolean isFailed();

    @Override
    @Property(FAILED_PROPERTY)
    public abstract void setFailed(boolean failed);

    @Override
    public Switch getSrcSwitch() {
        if (srcSwitch == null) {
            srcSwitch = SwitchFrame.load(getGraph(), getProperty(SRC_SWITCH_ID_PROPERTY))
                    .map(Switch::new).orElse(null);
        }
        return srcSwitch;
    }

    @Override
    public Switch getDestSwitch() {
        if (destSwitch == null) {
            destSwitch = SwitchFrame.load(getGraph(), getProperty(DST_SWITCH_ID_PROPERTY))
                    .map(Switch::new).orElse(null);
        }
        return destSwitch;
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
