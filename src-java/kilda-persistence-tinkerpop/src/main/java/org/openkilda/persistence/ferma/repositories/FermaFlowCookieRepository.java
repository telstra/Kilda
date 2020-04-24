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
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowCookieFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.repositories.FlowCookieRepository;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowCookieRepository}.
 */
class FermaFlowCookieRepository extends FermaGenericRepository<FlowCookie, FlowCookieData, FlowCookieFrame>
        implements FlowCookieRepository {
    FermaFlowCookieRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
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
    public Optional<FlowCookie> findByCookie(long unmaskedCookie) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, unmaskedCookie))
                .nextOrDefaultExplicit(FlowCookieFrame.class, null))
                .map(FlowCookie::new);
    }

    @Override
    public Optional<Long> findMaximumAssignedCookie() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .values(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY).max())
                .getRawTraversal().tryNext()
                .filter(n -> !(n instanceof Double && ((Double) n).isNaN()))
                .map(l -> (Long) l);
    }

    @Override
    public long findFirstUnassignedCookie(long startCookieValue) {
        if (!framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, startCookieValue))
                .getRawTraversal().hasNext()) {
            return startCookieValue;
        }

        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, P.gte(startCookieValue))
                .values(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(FlowCookieFrame.FRAME_LABEL)
                        .values(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY)
                        .where(P.eq("a"))))
                .select("a"))
                .getRawTraversal().tryNext()
                .map(l -> ((Double) l).longValue()).orElse(startCookieValue);
    }

    @Override
    protected FlowCookieFrame doAdd(FlowCookieData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(FlowCookieFrame.FRAME_LABEL)
                .has(FlowCookieFrame.UNMASKED_COOKIE_PROPERTY, data.getUnmaskedCookie()))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a vertex with duplicated "
                    + FlowCookieFrame.UNMASKED_COOKIE_PROPERTY);
        }

        FlowCookieFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                FlowCookieFrame.FRAME_LABEL, FlowCookieFrame.class);
        FlowCookie.FlowCookieCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected FlowCookieData doRemove(FlowCookie entity, FlowCookieFrame frame) {
        FlowCookieData data = FlowCookie.FlowCookieCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
