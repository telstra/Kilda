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

import org.openkilda.model.Isl.IslData;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;
import org.openkilda.persistence.ferma.frames.converters.IslDownReasonConverter;
import org.openkilda.persistence.ferma.frames.converters.IslStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;

import com.syncleus.ferma.annotations.Property;

import java.time.Instant;
import java.util.Optional;

public abstract class IslFrame extends KildaBaseEdgeFrame implements IslData {
    public static final String FRAME_LABEL = "isl";
    public static final String SRC_PORT_PROPERTY = "src_port";
    public static final String DST_PORT_PROPERTY = "dst_port";
    public static final String STATUS_PROPERTY = "status";
    public static final String LATENCY_PROPERTY = "latency";
    public static final String COST_PROPERTY = "cost";
    public static final String AVAILABLE_BANDWIDTH_PROPERTY = "available_bandwidth";

    private SwitchId srcSwitchId;
    private Switch srcSwitch;
    private SwitchId destSwitchId;
    private Switch destSwitch;

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
    @Property(LATENCY_PROPERTY)
    public abstract long getLatency();

    @Override
    @Property(LATENCY_PROPERTY)
    public abstract void setLatency(long latency);

    @Override
    @Property("speed")
    public abstract long getSpeed();

    @Override
    @Property("speed")
    public abstract void setSpeed(long speed);

    @Override
    @Property(COST_PROPERTY)
    public abstract int getCost();

    @Override
    @Property(COST_PROPERTY)
    public abstract void setCost(int cost);

    @Override
    @Property("max_bandwidth")
    public abstract long getMaxBandwidth();

    @Override
    @Property("max_bandwidth")
    public abstract void setMaxBandwidth(long maxBandwidth);

    @Override
    @Property("default_max_bandwidth")
    public abstract long getDefaultMaxBandwidth();

    @Override
    @Property("default_max_bandwidth")
    public abstract void setDefaultMaxBandwidth(long defaultMaxBandwidth);

    @Override
    @Property(AVAILABLE_BANDWIDTH_PROPERTY)
    public abstract long getAvailableBandwidth();

    @Override
    @Property(AVAILABLE_BANDWIDTH_PROPERTY)
    public abstract void setAvailableBandwidth(long availableBandwidth);

    @Override
    @Property(STATUS_PROPERTY)
    @Convert(IslStatusConverter.class)
    public abstract IslStatus getStatus();

    @Override
    @Property(STATUS_PROPERTY)
    @Convert(IslStatusConverter.class)
    public abstract void setStatus(IslStatus status);

    @Override
    @Property("actual_status")
    @Convert(IslStatusConverter.class)
    public abstract IslStatus getActualStatus();

    @Override
    @Property("actual_status")
    @Convert(IslStatusConverter.class)
    public abstract void setActualStatus(IslStatus status);

    @Override
    @Property("down_reason")
    @Convert(IslDownReasonConverter.class)
    public abstract IslDownReason getDownReason();

    @Override
    @Property("down_reason")
    @Convert(IslDownReasonConverter.class)
    public abstract void setDownReason(IslDownReason downReason);

    @Override
    @Property("under_maintenance")
    public abstract boolean isUnderMaintenance();

    @Override
    @Property("under_maintenance")
    public abstract void setUnderMaintenance(boolean underMaintenance);

    @Override
    @Property("enable_bfd")
    public abstract boolean isEnableBfd();

    @Override
    @Property("enable_bfd")
    public abstract void setEnableBfd(boolean enableBfd);

    @Override
    @Property("bfd_session")
    public abstract String getBfdSessionStatus();

    @Override
    @Property("bfd_session")
    public abstract void setBfdSessionStatus(String bfdSessionStatus);

    @Override
    public Switch getSrcSwitch() {
        if (srcSwitch == null) {
            srcSwitch = Optional.ofNullable(traverse(e -> e.outV()
                    .hasLabel(SwitchFrame.FRAME_LABEL))
                    .nextOrDefaultExplicit(SwitchFrame.class, null))
                    .map(Switch::new).orElse(null);
            srcSwitchId = srcSwitch.getSwitchId();
        }
        return srcSwitch;
    }

    @Override
    public SwitchId getSrcSwitchId() {
        if (srcSwitchId == null) {
            srcSwitchId = traverse(e -> e.outV()
                    .hasLabel(SwitchFrame.FRAME_LABEL)
                    .values(SwitchFrame.SWITCH_ID_PROPERTY))
                    .getRawTraversal().tryNext()
                    .map(s -> (String) s).map(SwitchIdConverter.INSTANCE::toEntityAttribute).orElse(null);
        }
        return srcSwitchId;
    }

    @Override
    public void setSrcSwitch(Switch srcSwitch) {
        throw new UnsupportedOperationException("Changing edge's source is not supported");
    }

    @Override
    public Switch getDestSwitch() {
        if (destSwitch == null) {
            destSwitch = Optional.ofNullable(traverse(e -> e.inV()
                    .hasLabel(SwitchFrame.FRAME_LABEL))
                    .nextOrDefaultExplicit(SwitchFrame.class, null))
                    .map(Switch::new).orElse(null);
            destSwitchId = destSwitch.getSwitchId();
        }
        return destSwitch;
    }

    @Override
    public SwitchId getDestSwitchId() {
        if (destSwitchId == null) {
            destSwitchId = traverse(e -> e.inV()
                    .hasLabel(SwitchFrame.FRAME_LABEL)
                    .values(SwitchFrame.SWITCH_ID_PROPERTY))
                    .getRawTraversal().tryNext()
                    .map(s -> (String) s).map(SwitchIdConverter.INSTANCE::toEntityAttribute).orElse(null);
        }
        return destSwitchId;
    }

    @Override
    public void setDestSwitch(Switch destSwitch) {
        throw new UnsupportedOperationException("Changing edge's destination is not supported");
    }

    @Override
    @Property("time_unstable")
    @Convert(InstantStringConverter.class)
    public abstract Instant getTimeUnstable();

    @Override
    @Property("time_unstable")
    @Convert(InstantStringConverter.class)
    public abstract void setTimeUnstable(Instant timeUnstable);
}
