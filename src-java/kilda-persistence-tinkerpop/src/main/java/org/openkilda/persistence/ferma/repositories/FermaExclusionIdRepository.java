/* Copyright 2019 Telstra Open Source
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

import org.openkilda.model.ExclusionId;
import org.openkilda.model.ExclusionId.ExclusionIdData;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.ExclusionIdFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.repositories.ExclusionIdRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link ExclusionIdRepository}.
 */
public class FermaExclusionIdRepository extends FermaGenericRepository<ExclusionId, ExclusionIdData, ExclusionIdFrame>
        implements ExclusionIdRepository {

    public FermaExclusionIdRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<ExclusionId> findByFlowId(String flowId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(ExclusionIdFrame.FRAME_LABEL)
                .has(ExclusionIdFrame.FLOW_ID_PROPERTY, flowId))
                .toListExplicit(ExclusionIdFrame.class).stream()
                .map(ExclusionId::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ExclusionId> find(String flowId, int exclusionId) {
        List<? extends ExclusionIdFrame> exclusionIdFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(ExclusionIdFrame.FRAME_LABEL)
                .has(ExclusionIdFrame.FLOW_ID_PROPERTY, flowId)
                .has(ExclusionIdFrame.EXCLUSION_ID_PROPERTY, exclusionId))
                .toListExplicit(ExclusionIdFrame.class);
        return exclusionIdFrames.isEmpty() ? Optional.empty() : Optional.of(exclusionIdFrames.get(0))
                .map(ExclusionId::new);
    }

    @Override
    public Optional<Integer> findUnassignedExclusionId(String flowId, int defaultExclusionId) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(ExclusionIdFrame.FRAME_LABEL)
                .has(ExclusionIdFrame.FLOW_ID_PROPERTY, flowId)
                .has(ExclusionIdFrame.EXCLUSION_ID_PROPERTY, defaultExclusionId))
                .getRawTraversal()) {
            if (!traversal.hasNext()) {
                return Optional.of(defaultExclusionId);
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(ExclusionIdFrame.FRAME_LABEL)
                .has(ExclusionIdFrame.FLOW_ID_PROPERTY, flowId)
                .has(ExclusionIdFrame.EXCLUSION_ID_PROPERTY, P.gte(defaultExclusionId))
                .values(ExclusionIdFrame.EXCLUSION_ID_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(ExclusionIdFrame.FRAME_LABEL)
                        .values(ExclusionIdFrame.EXCLUSION_ID_PROPERTY)
                        .where(P.eq("a"))))
                .select("a")
                .limit(1))
                .getRawTraversal()) {
            return traversal.tryNext()
                    .map(l -> ((Double) l).intValue());
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    protected ExclusionIdFrame doAdd(ExclusionIdData data) {
        ExclusionIdFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                ExclusionIdFrame.FRAME_LABEL, ExclusionIdFrame.class);
        ExclusionId.ExclusionIdCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(ExclusionIdFrame frame) {
        frame.remove();
    }

    @Override
    protected ExclusionIdData doDetach(ExclusionId entity, ExclusionIdFrame frame) {
        return ExclusionId.ExclusionIdCloner.INSTANCE.deepCopy(frame);
    }
}
