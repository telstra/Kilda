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

import org.openkilda.model.history.FlowEvent;
import org.openkilda.model.history.FlowHistory.FlowHistoryData;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;

import com.syncleus.ferma.VertexFrame;
import com.syncleus.ferma.annotations.Property;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;

import java.time.Instant;
import java.util.Optional;

public abstract class FlowHistoryFrame extends KildaBaseVertexFrame implements FlowHistoryData {
    public static final String FRAME_LABEL = "flow_history";
    public static final String TASK_ID_PROPERTY = "task_id";
    public static final String HISTORY_LOG_EDGE = "history_log";

    @Override
    @Property("timestamp")
    @Convert(InstantStringConverter.class)
    public abstract Instant getTimestamp();

    @Override
    @Property("timestamp")
    @Convert(InstantStringConverter.class)
    public abstract void setTimestamp(Instant timestamp);

    @Override
    @Property("action")
    public abstract String getAction();

    @Override
    @Property("action")
    public abstract void setAction(String action);

    @Override
    @Property(TASK_ID_PROPERTY)
    public abstract String getTaskId();

    @Override
    @Property(TASK_ID_PROPERTY)
    public abstract void setTaskId(String taskId);

    @Override
    @Property("details")
    public abstract String getDetails();

    @Override
    @Property("details")
    public abstract void setDetails(String details);

    @Override
    public FlowEvent getFlowEvent() {
        return Optional.ofNullable(traverse(v -> v.in(HISTORY_LOG_EDGE)
                .hasLabel(FlowEventFrame.FRAME_LABEL))
                .nextOrDefaultExplicit(FlowEventFrame.class, null))
                .map(FlowEvent::new)
                .orElse(null);
    }

    @Override
    public void setFlowEvent(FlowEvent flowEvent) {
        getElement().edges(Direction.IN, HISTORY_LOG_EDGE).forEachRemaining(Edge::remove);

        FlowEvent.FlowEventData data = flowEvent.getData();
        if (data instanceof FlowEventFrame) {
            linkIn((VertexFrame) data, HISTORY_LOG_EDGE);
        } else {
            throw new IllegalArgumentException("Unable to link to transient flow event " + flowEvent);
        }
    }
}
