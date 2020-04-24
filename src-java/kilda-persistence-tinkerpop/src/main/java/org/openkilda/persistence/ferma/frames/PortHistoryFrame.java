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

import org.openkilda.model.SwitchId;
import org.openkilda.model.history.PortHistory.PortHistoryData;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;

import com.syncleus.ferma.annotations.Property;

import java.time.Instant;
import java.util.UUID;

public abstract class PortHistoryFrame extends KildaBaseVertexFrame implements PortHistoryData {
    public static final String FRAME_LABEL = "port_history";
    public static final String SWITCH_ID_PROPERTY = "switch_id";
    public static final String PORT_NUMBER_PROPERTY = "port_number";
    public static final String TIME_PROPERTY = "time";

    @Override
    public UUID getRecordId() {
        String id = getProperty("id");
        return id != null ? UUID.fromString(id) : null;
    }

    @Override
    public void setRecordId(UUID id) {
        setProperty("id", id != null ? id.toString() : null);
    }

    @Override
    @Property(SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getSwitchId();

    @Override
    @Property(SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract void setSwitchId(SwitchId switchId);

    @Override
    @Property(PORT_NUMBER_PROPERTY)
    public abstract int getPortNumber();

    @Override
    @Property(PORT_NUMBER_PROPERTY)
    public abstract void setPortNumber(int portNumber);

    @Override
    @Property("event")
    public abstract String getEvent();

    @Override
    @Property("event")
    public abstract void setEvent(String event);

    @Override
    @Property(TIME_PROPERTY)
    @Convert(InstantStringConverter.class)
    public abstract Instant getTime();

    @Override
    @Property(TIME_PROPERTY)
    @Convert(InstantStringConverter.class)
    public abstract void setTime(Instant timestamp);

    @Override
    @Property("up_events_count")
    public abstract int getUpEventsCount();

    @Override
    @Property("up_events_count")
    public abstract void setUpEventsCount(int upEventsCount);

    @Override
    @Property("down_events_count")
    public abstract int getDownEventsCount();

    @Override
    @Property("down_events_count")
    public abstract void setDownEventsCount(int downEventsCount);
}
