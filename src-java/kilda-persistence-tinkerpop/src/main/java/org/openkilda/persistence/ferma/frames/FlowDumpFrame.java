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

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchId;
import org.openkilda.model.history.FlowDump.FlowDumpData;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.CookieConverter;
import org.openkilda.persistence.ferma.frames.converters.FlowPathStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.MeterIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;

import com.syncleus.ferma.annotations.Property;

public abstract class FlowDumpFrame extends KildaBaseVertexFrame implements FlowDumpData {
    public static final String FRAME_LABEL = "flow_dump";
    public static final String TASK_ID_PROPERTY = "task_id";

    @Override
    @Property(TASK_ID_PROPERTY)
    public abstract String getTaskId();

    @Override
    @Property(TASK_ID_PROPERTY)
    public abstract void setTaskId(String taskId);

    @Override
    @Property("flow_id")
    public abstract String getFlowId();

    @Override
    @Property("flow_id")
    public abstract void setFlowId(String flowId);

    @Override
    @Property("type")
    public abstract String getType();

    @Override
    @Property("type")
    public abstract void setType(String type);

    @Override
    @Property("bandwidth")
    public abstract long getBandwidth();

    @Override
    @Property("bandwidth")
    public abstract void setBandwidth(long bandwidth);

    @Override
    @Property("ignore_bandwidth")
    public abstract boolean isIgnoreBandwidth();

    @Override
    @Property("ignore_bandwidth")
    public abstract void setIgnoreBandwidth(boolean ignoreBandwidth);

    @Override
    @Property("forward_cookie")
    @Convert(CookieConverter.class)
    public abstract Cookie getForwardCookie();

    @Override
    @Property("forward_cookie")
    @Convert(CookieConverter.class)
    public abstract void setForwardCookie(Cookie forwardCookie);

    @Override
    @Property("reverse_cookie")
    @Convert(CookieConverter.class)
    public abstract Cookie getReverseCookie();

    @Override
    @Property("reverse_cookie")
    @Convert(CookieConverter.class)
    public abstract void setReverseCookie(Cookie reverseCookie);

    @Override
    @Property("source_switch")
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getSourceSwitch();

    @Override
    @Property("source_switch")
    @Convert(SwitchIdConverter.class)
    public abstract void setSourceSwitch(SwitchId sourceSwitch);

    @Override
    @Property("destination_switch")
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getDestinationSwitch();

    @Override
    @Property("destination_switch")
    @Convert(SwitchIdConverter.class)
    public abstract void setDestinationSwitch(SwitchId destinationSwitch);

    @Override
    @Property("source_port")
    public abstract int getSourcePort();

    @Override
    @Property("source_port")
    public abstract void setSourcePort(int sourcePort);

    @Override
    @Property("destination_port")
    public abstract int getDestinationPort();

    @Override
    @Property("destination_port")
    public abstract void setDestinationPort(int destinationPort);

    @Override
    @Property("source_vlan")
    public abstract int getSourceVlan();

    @Override
    @Property("source_vlan")
    public abstract void setSourceVlan(int sourceVlan);

    @Override
    @Property("destination_vlan")
    public abstract int getDestinationVlan();

    @Override
    @Property("destination_vlan")
    public abstract void setDestinationVlan(int destinationVlan);

    @Override
    @Property("forward_meter_id")
    @Convert(MeterIdConverter.class)
    public abstract MeterId getForwardMeterId();

    @Override
    @Property("forward_meter_id")
    @Convert(MeterIdConverter.class)
    public abstract void setForwardMeterId(MeterId forwardMeterId);

    @Override
    @Property("reverse_meter_id")
    @Convert(MeterIdConverter.class)
    public abstract MeterId getReverseMeterId();

    @Override
    @Property("reverse_meter_id")
    @Convert(MeterIdConverter.class)
    public abstract void setReverseMeterId(MeterId reverseMeterId);

    @Override
    @Property("forward_path")
    public abstract String getForwardPath();

    @Override
    @Property("forward_path")
    public abstract void setForwardPath(String forwardPath);

    @Override
    @Property("reverse_path")
    public abstract String getReversePath();

    @Override
    @Property("reverse_path")
    public abstract void setReversePath(String reversePath);

    @Override
    @Property("forward_status")
    @Convert(FlowPathStatusConverter.class)
    public abstract FlowPathStatus getForwardStatus();

    @Override
    @Property("forward_status")
    @Convert(FlowPathStatusConverter.class)
    public abstract void setForwardStatus(FlowPathStatus forwardStatus);

    @Override
    @Property("reverse_status")
    @Convert(FlowPathStatusConverter.class)
    public abstract FlowPathStatus getReverseStatus();

    @Override
    @Property("reverse_status")
    @Convert(FlowPathStatusConverter.class)
    public abstract void setReverseStatus(FlowPathStatus reverseStatus);
}
