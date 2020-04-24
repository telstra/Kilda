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
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.BfdSessionFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.BfdSessionRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link BfdSessionRepository}.
 */
class FermaBfdSessionRepository extends FermaGenericRepository<BfdSession, BfdSessionData, BfdSessionFrame>
        implements BfdSessionRepository {
    FermaBfdSessionRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
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
        return framedGraph().traverse(g -> g.V()
                .hasLabel(BfdSessionFrame.FRAME_LABEL)
                .has(BfdSessionFrame.SWITCH_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(BfdSessionFrame.PORT_PROPERTY, port))
                .getRawTraversal().hasNext();
    }

    @Override
    public Optional<BfdSession> findBySwitchIdAndPort(SwitchId switchId, Integer port) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(BfdSessionFrame.FRAME_LABEL)
                .has(BfdSessionFrame.SWITCH_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(BfdSessionFrame.PORT_PROPERTY, port))
                .nextOrDefaultExplicit(BfdSessionFrame.class, null))
                .map(BfdSession::new);
    }

    @Override
    protected BfdSessionFrame doAdd(BfdSessionData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(BfdSessionFrame.FRAME_LABEL)
                .has(BfdSessionFrame.DISCRIMINATOR_PROPERTY, data.getDiscriminator()))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a vertex with duplicated "
                    + BfdSessionFrame.DISCRIMINATOR_PROPERTY);
        }

        BfdSessionFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                BfdSessionFrame.FRAME_LABEL, BfdSessionFrame.class);
        BfdSession.BfdSessionCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected BfdSessionData doRemove(BfdSession entity, BfdSessionFrame frame) {
        BfdSessionData data = BfdSession.BfdSessionCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
