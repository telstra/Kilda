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
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.PortHistoryFrame;
import org.openkilda.persistence.ferma.frames.converters.InstantLongConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link PortHistoryRepository}.
 */
public class FermaPortHistoryRepository extends FermaGenericRepository<PortHistory, PortHistoryData, PortHistoryFrame>
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
                        P.gte(InstantLongConverter.INSTANCE.toGraphProperty(timeFrom)));
            }
            if (timeTo != null) {
                traversal = traversal.has(PortHistoryFrame.TIME_PROPERTY,
                        P.lte(InstantLongConverter.INSTANCE.toGraphProperty(timeTo)));
            }
            return traversal
                    .order().by(PortHistoryFrame.TIME_PROPERTY, Order.incr);
        }).toListExplicit(PortHistoryFrame.class).stream()
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
    protected void doRemove(PortHistoryFrame frame) {
        frame.remove();
    }

    @Override
    protected PortHistoryData doDetach(PortHistory entity, PortHistoryFrame frame) {
        return PortHistory.PortHistoryCloner.INSTANCE.deepCopy(frame);
    }
}
