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

import org.openkilda.model.BfdSessionStatus;
import org.openkilda.model.Isl.IslData;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.frames.converters.BfdSessionStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.DurationConverter;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;
import org.openkilda.persistence.ferma.frames.converters.IslDownReasonConverter;
import org.openkilda.persistence.ferma.frames.converters.IslStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;

import com.syncleus.ferma.annotations.Property;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public abstract class IslFrame extends KildaBaseEdgeFrame implements IslData {
    public static final String FRAME_LABEL = "isl";
    public static final String SRC_SWITCH_ID_PROPERTY = "src_switch_id";
    public static final String DST_SWITCH_ID_PROPERTY = "dst_switch_id";
    public static final String SRC_PORT_PROPERTY = "src_port";
    public static final String DST_PORT_PROPERTY = "dst_port";
    public static final String STATUS_PROPERTY = "status";
    public static final String LATENCY_PROPERTY = "latency";
    public static final String COST_PROPERTY = "cost";
    public static final String AVAILABLE_BANDWIDTH_PROPERTY = "available_bandwidth";
    public static final String MAX_BANDWIDTH_PROPERTY = "max_bandwidth";
    public static final String UNDER_MAINTENANCE_PROPERTY = "under_maintenance";
    public static final String TIME_UNSTABLE_PROPERTY = "time_unstable";

    private Switch srcSwitch;
    private Switch destSwitch;

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
    @Property(MAX_BANDWIDTH_PROPERTY)
    public abstract long getMaxBandwidth();

    @Override
    @Property(MAX_BANDWIDTH_PROPERTY)
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
    @Property("actual")
    @Convert(IslStatusConverter.class)
    public abstract IslStatus getActualStatus();

    @Override
    @Property("actual")
    @Convert(IslStatusConverter.class)
    public abstract void setActualStatus(IslStatus status);

    @Override
    @Property("round_trip_status")
    @Convert(IslStatusConverter.class)
    public abstract IslStatus getRoundTripStatus();

    @Override
    @Property("round_trip_status")
    @Convert(IslStatusConverter.class)
    public abstract void setRoundTripStatus(IslStatus roundTripStatus);

    @Override
    @Property("down_reason")
    @Convert(IslDownReasonConverter.class)
    public abstract IslDownReason getDownReason();

    @Override
    @Property("down_reason")
    @Convert(IslDownReasonConverter.class)
    public abstract void setDownReason(IslDownReason downReason);

    @Override
    @Property(UNDER_MAINTENANCE_PROPERTY)
    public abstract boolean isUnderMaintenance();

    @Override
    @Property(UNDER_MAINTENANCE_PROPERTY)
    public abstract void setUnderMaintenance(boolean underMaintenance);

    @Override
    @Property("bfd_interval")
    @Convert(DurationConverter.class)
    public abstract Duration getBfdInterval();

    @Override
    @Property("bfd_interval")
    @Convert(DurationConverter.class)
    public abstract void setBfdInterval(Duration interval);

    @Override
    @Property("bfd_multiplier")
    public abstract Short getBfdMultiplier();

    @Override
    @Property("bfd_multiplier")
    public abstract void setBfdMultiplier(Short multiplier);

    @Override
    @Property("bfd_session_status")
    @Convert(BfdSessionStatusConverter.class)
    public abstract BfdSessionStatus getBfdSessionStatus();

    @Override
    @Property("bfd_session_status")
    @Convert(BfdSessionStatusConverter.class)
    public abstract void setBfdSessionStatus(BfdSessionStatus bfdSessionStatus);

    @Override
    public Switch getSrcSwitch() {
        if (srcSwitch == null) {
            List<? extends SwitchFrame> switchFrames = traverse(e -> e.outV()
                    .hasLabel(SwitchFrame.FRAME_LABEL))
                    .toListExplicit(SwitchFrame.class);
            srcSwitch = !switchFrames.isEmpty() ? new Switch((switchFrames.get(0))) : null;
            SwitchId switchId = srcSwitch != null ? srcSwitch.getSwitchId() : null;
            if (!Objects.equals(getSrcSwitchId(), switchId)) {
                throw new IllegalStateException(format("The isl %s has inconsistent source switch %s / %s",
                        getId(), getSrcSwitchId(), switchId));
            }
        }
        return srcSwitch;
    }

    @Override
    public void setSrcSwitch(Switch srcSwitch) {
        throw new UnsupportedOperationException("Changing edge's source is not supported");
    }

    @Override
    public Switch getDestSwitch() {
        if (destSwitch == null) {
            List<? extends SwitchFrame> switchFrames = traverse(e -> e.inV()
                    .hasLabel(SwitchFrame.FRAME_LABEL))
                    .toListExplicit(SwitchFrame.class);
            destSwitch = !switchFrames.isEmpty() ? new Switch((switchFrames.get(0))) : null;
            SwitchId switchId = destSwitch != null ? destSwitch.getSwitchId() : null;
            if (!Objects.equals(getDestSwitchId(), switchId)) {
                throw new IllegalStateException(format("The isl %s has inconsistent dest switch %s / %s",
                        getId(), getDestSwitchId(), switchId));
            }
        }
        return destSwitch;
    }

    @Override
    public void setDestSwitch(Switch destSwitch) {
        throw new UnsupportedOperationException("Changing edge's destination is not supported");
    }

    @Override
    @Property(TIME_UNSTABLE_PROPERTY)
    @Convert(InstantStringConverter.class)
    public abstract Instant getTimeUnstable();

    @Override
    @Property(TIME_UNSTABLE_PROPERTY)
    @Convert(InstantStringConverter.class)
    public abstract void setTimeUnstable(Instant timeUnstable);
}
