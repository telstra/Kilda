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

package org.openkilda.persistence.ferma.repositories;

import org.openkilda.model.history.FlowEvent;
import org.openkilda.model.history.FlowEvent.FlowEventData;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowEventFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;
import org.openkilda.persistence.repositories.history.FlowEventRepository;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowEventRepository}.
 */
class FermaFlowEventRepository extends FermaGenericRepository<FlowEvent, FlowEventData, FlowEventFrame>
        implements FlowEventRepository {
    FermaFlowEventRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public boolean existsByTaskId(String taskId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowEventFrame.FRAME_LABEL)
                .has(FlowEventFrame.TASK_ID_PROPERTY, taskId))
                .getRawTraversal().hasNext();
    }

    @Override
    public Optional<FlowEvent> findByTaskId(String taskId) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(FlowEventFrame.FRAME_LABEL)
                .has(FlowEventFrame.TASK_ID_PROPERTY, taskId))
                .nextOrDefaultExplicit(FlowEventFrame.class, null))
                .map(FlowEvent::new);
    }

    @Override
    public List<FlowEvent> findByFlowIdAndTimeFrame(String flowId, Instant timeFrom, Instant timeTo) {
        return framedGraph().traverse(g -> {
            GraphTraversal<Vertex, Vertex> traversal = g.V()
                    .hasLabel(FlowEventFrame.FRAME_LABEL)
                    .has(FlowEventFrame.FLOW_ID_PROPERTY, flowId);
            if (timeFrom != null) {
                traversal = traversal.has(FlowEventFrame.TIMESTAMP_PROPERTY,
                        P.gte(InstantStringConverter.INSTANCE.toGraphProperty(timeFrom)));
            }
            if (timeTo != null) {
                traversal = traversal.has(FlowEventFrame.TIMESTAMP_PROPERTY,
                        P.lte(InstantStringConverter.INSTANCE.toGraphProperty(timeTo)));
            }
            return traversal;
        }).toListExplicit(FlowEventFrame.class).stream()
                .filter(event -> timeTo == null || event.getTimestamp().compareTo(timeTo) <= 0)
                .filter(event -> timeFrom == null || event.getTimestamp().compareTo(timeFrom) >= 0)
                .sorted(Comparator.comparing(FlowEventFrame::getTimestamp))
                .map(FlowEvent::new)
                .collect(Collectors.toList());
    }

    @Override
    protected FlowEventFrame doAdd(FlowEventData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(FlowEventFrame.FRAME_LABEL)
                .has(FlowEventFrame.TASK_ID_PROPERTY, data.getTaskId()))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a vertex with duplicated "
                    + FlowEventFrame.TASK_ID_PROPERTY);
        }

        FlowEventFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), FlowEventFrame.FRAME_LABEL,
                FlowEventFrame.class);
        FlowEvent.FlowEventCloner.INSTANCE.copyWithoutRecordsAndDumps(data, frame);
        return frame;
    }

    @Override
    protected FlowEventData doRemove(FlowEvent entity, FlowEventFrame frame) {
        FlowEventData data = FlowEvent.FlowEventCloner.INSTANCE.copy(frame);
        frame.getElement().edges(Direction.BOTH).forEachRemaining(Edge::remove);
        frame.remove();
        return data;
    }
}
