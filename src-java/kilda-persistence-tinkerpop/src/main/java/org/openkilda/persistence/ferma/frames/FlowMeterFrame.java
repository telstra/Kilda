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

import org.openkilda.model.FlowMeter.FlowMeterData;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.MeterIdConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;

import com.syncleus.ferma.annotations.Property;

public abstract class FlowMeterFrame extends KildaBaseVertexFrame implements FlowMeterData {
    public static final String FRAME_LABEL = "flow_meter";
    public static final String PATH_ID_PROPERTY = "path_id";
    public static final String METER_ID_PROPERTY = "meter_id";
    public static final String FLOW_ID_PROPERTY = "flow_id";
    public static final String SWITCH_PROPERTY = "switch_id";

    @Override
    @Property(SWITCH_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getSwitchId();

    @Override
    @Property(SWITCH_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract void setSwitchId(SwitchId switchId);

    @Override
    @Property(METER_ID_PROPERTY)
    @Convert(MeterIdConverter.class)
    public abstract MeterId getMeterId();

    @Override
    @Property(METER_ID_PROPERTY)
    @Convert(MeterIdConverter.class)
    public abstract void setMeterId(MeterId meterId);

    @Override
    @Property(FLOW_ID_PROPERTY)
    public abstract String getFlowId();

    @Override
    @Property(FLOW_ID_PROPERTY)
    public abstract void setFlowId(String flowId);

    @Override
    @Property(PATH_ID_PROPERTY)
    @Convert(PathIdConverter.class)
    public abstract PathId getPathId();

    @Override
    @Property(PATH_ID_PROPERTY)
    @Convert(PathIdConverter.class)
    public abstract void setPathId(PathId pathId);
}
