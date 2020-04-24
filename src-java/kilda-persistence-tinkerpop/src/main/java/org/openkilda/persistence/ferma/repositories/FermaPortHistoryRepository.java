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

import org.openkilda.model.SwitchId;
import org.openkilda.model.history.PortHistory;
import org.openkilda.model.history.PortHistory.PortHistoryData;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.PortHistoryFrame;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link PortHistoryRepository}.
 */
class FermaPortHistoryRepository extends FermaGenericRepository<PortHistory, PortHistoryData, PortHistoryFrame>
        implements PortHistoryRepository {
    FermaPortHistoryRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public List<PortHistory> findBySwitchIdAndPortNumber(SwitchId switchId, int portNumber,
                                                         Instant timeFrom, Instant timeTo) {
        return framedGraph().traverse(g -> {
            GraphTraversal<Vertex, Vertex> traversal = g.V()
                    .hasLabel(PortHistoryFrame.FRAME_LABEL)
                    .has(PortHistoryFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                    .has(PortHistoryFrame.PORT_NUMBER_PROPERTY, portNumber);
            if (timeFrom != null) {
                traversal = traversal.has(PortHistoryFrame.TIME_PROPERTY,
                        P.gte(InstantStringConverter.INSTANCE.toGraphProperty(timeFrom)));
            }
            if (timeTo != null) {
                traversal = traversal.has(PortHistoryFrame.TIME_PROPERTY,
                        P.lte(InstantStringConverter.INSTANCE.toGraphProperty(timeTo)));
            }
            return traversal;
        }).toListExplicit(PortHistoryFrame.class).stream()
                .filter(event -> timeTo == null || event.getTime().compareTo(timeTo) <= 0)
                .filter(event -> timeFrom == null || event.getTime().compareTo(timeFrom) >= 0)
                .sorted(Comparator.comparing(PortHistoryFrame::getTime))
                .map(PortHistory::new)
                .collect(Collectors.toList());
    }

    @Override
    protected PortHistoryFrame doAdd(PortHistoryData data) {
        PortHistoryFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                PortHistoryFrame.FRAME_LABEL, PortHistoryFrame.class);
        PortHistory.PortHistoryCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected PortHistoryData doRemove(PortHistory entity, PortHistoryFrame frame) {
        PortHistoryData data = PortHistory.PortHistoryCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
