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

import org.openkilda.model.BfdSession;
import org.openkilda.model.BfdSession.BfdSessionData;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.BfdSessionFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link BfdSessionRepository}.
 */
public class FermaBfdSessionRepository extends FermaGenericRepository<BfdSession, BfdSessionData, BfdSessionFrame>
        implements BfdSessionRepository {
    public FermaBfdSessionRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<BfdSession> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(BfdSessionFrame.FRAME_LABEL))
                .toListExplicit(BfdSessionFrame.class).stream()
                .map(BfdSession::new)
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(SwitchId switchId, Integer port) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> makeLogicalPortTraverse(g, switchId, port))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Optional<BfdSession> findBySwitchIdAndPort(SwitchId switchId, int port) {
        return makeOneOrZeroResults(framedGraph()
                .traverse(g -> makeLogicalPortTraverse(g, switchId, port))
                .toListExplicit(BfdSessionFrame.class));
    }

    @Override
    public Optional<BfdSession> findBySwitchIdAndPhysicalPort(SwitchId switchId, int physicalPort) {
        return makeOneOrZeroResults(framedGraph()
                .traverse(g -> makeGenericTraverse(g, switchId)
                        .has(BfdSessionFrame.PHYSICAL_PORT_PROPERTY, physicalPort))
                .toListExplicit(BfdSessionFrame.class));
    }

    @Override
    protected BfdSessionFrame doAdd(BfdSessionData data) {
        BfdSessionFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                BfdSessionFrame.FRAME_LABEL, BfdSessionFrame.class);
        BfdSession.BfdSessionCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(BfdSessionFrame frame) {
        frame.remove();
    }

    @Override
    protected BfdSessionData doDetach(BfdSession entity, BfdSessionFrame frame) {
        return BfdSession.BfdSessionCloner.INSTANCE.deepCopy(frame);
    }

    private Optional<BfdSession> makeOneOrZeroResults(List<? extends BfdSessionFrame> results) {
        if (results.size() > 1) {
            throw new PersistenceException(String.format("Found more than 1 BfdSession entity: %s", results));
        }
        return results.isEmpty()
                ? Optional.empty()
                : Optional.of(results.get(0)).map(BfdSession::new);
    }

    private GraphTraversal<Vertex, Vertex> makeLogicalPortTraverse(
            GraphTraversalSource g, SwitchId switchId, int logicalPort) {
        return makeGenericTraverse(g, switchId)
                .has(BfdSessionFrame.PORT_PROPERTY, logicalPort);
    }

    private GraphTraversal<Vertex, Vertex> makeGenericTraverse(GraphTraversalSource g, SwitchId switchId) {
        return g.V()
                .hasLabel(BfdSessionFrame.FRAME_LABEL)
                .has(BfdSessionFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId));
    }
}
