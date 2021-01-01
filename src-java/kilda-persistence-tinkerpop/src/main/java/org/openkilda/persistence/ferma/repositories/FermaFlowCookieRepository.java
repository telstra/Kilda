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

import org.openkilda.model.FlowCookie;
import org.openkilda.model.FlowCookie.FlowCookieData;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowCookieFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowCookieRepository}.
 */
public class FermaFlowCookieRepository extends FermaGenericRepository<FlowCookie, FlowCookieData, FlowCookieFrame>
        implements FlowCookieRepository {
    public FermaFlowCookieRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<FlowCookie> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL))
                .toListExplicit(FlowCookieFrame.class).stream()
                .map(FlowCookie::new)
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(long unmaskedCookie) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, unmaskedCookie))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Optional<FlowCookie> findByCookie(long unmaskedCookie) {
        List<? extends FlowCookieFrame> flowCookieFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, unmaskedCookie))
                .toListExplicit(FlowCookieFrame.class);
        return flowCookieFrames.isEmpty() ? Optional.empty() : Optional.of(flowCookieFrames.get(0))
                .map(FlowCookie::new);
    }

    @Override
    public Optional<Long> findFirstUnassignedCookie(long lowestCookieValue, long highestCookieValue) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, P.gte(lowestCookieValue))
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, P.lt(highestCookieValue))
                .values(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(FlowCookieFrame.FRAME_LABEL)
                        .values(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY)
                        .where(P.eq("a"))))
                .select("a")
                .limit(1))
                .getRawTraversal()) {
            if (traversal.hasNext()) {
                return traversal.tryNext()
                        .map(l -> ((Double) l).longValue());
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, lowestCookieValue))
                .getRawTraversal()) {
            if (!traversal.hasNext()) {
                return Optional.of(lowestCookieValue);
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        return Optional.empty();
    }

    @Override
    protected FlowCookieFrame doAdd(FlowCookieData data) {
        FlowCookieFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                FlowCookieFrame.FRAME_LABEL, FlowCookieFrame.class);
        FlowCookie.FlowCookieCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(FlowCookieFrame frame) {
        frame.remove();
    }

    @Override
    protected FlowCookieData doDetach(FlowCookie entity, FlowCookieFrame frame) {
        return FlowCookie.FlowCookieCloner.INSTANCE.deepCopy(frame);
    }
}
