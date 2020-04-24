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

import org.openkilda.model.PortProperties;
import org.openkilda.model.PortProperties.PortPropertiesData;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.PortPropertiesFrame;
import org.openkilda.persistence.repositories.PortPropertiesRepository;

import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link PortPropertiesRepository}.
 */
@Slf4j
class FermaPortPropertiesRepository
        extends FermaGenericRepository<PortProperties, PortPropertiesData, PortPropertiesFrame>
        implements PortPropertiesRepository {
    FermaPortPropertiesRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<PortProperties> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(PortPropertiesFrame.FRAME_LABEL))
                .toListExplicit(PortPropertiesFrame.class).stream()
                .map(PortProperties::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<PortProperties> getBySwitchIdAndPort(SwitchId switchId, int port) {
        return Optional.ofNullable(
                framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                        .out(PortPropertiesFrame.OWNS_SWITCH_EDGE)
                        .hasLabel(PortPropertiesFrame.FRAME_LABEL)
                        .has(PortPropertiesFrame.PORT_NO_PROPERTY, port))
                        .nextOrDefaultExplicit(PortPropertiesFrame.class, null))
                .map(PortProperties::new);
    }

    @Override
    public Collection<PortProperties> getAllBySwitchId(SwitchId switchId) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .out(PortPropertiesFrame.OWNS_SWITCH_EDGE)
                .hasLabel(PortPropertiesFrame.FRAME_LABEL))
                .toListExplicit(PortPropertiesFrame.class).stream()
                .map(PortProperties::new)
                .collect(Collectors.toList());
    }

    @Override
    protected PortPropertiesFrame doAdd(PortPropertiesData data) {
        PortPropertiesFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                PortPropertiesFrame.FRAME_LABEL, PortPropertiesFrame.class);
        PortProperties.PortPropertiesCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected PortPropertiesData doRemove(PortProperties entity, PortPropertiesFrame frame) {
        PortPropertiesData data = PortProperties.PortPropertiesCloner.INSTANCE.copy(frame);
        frame.getElement().edges(Direction.BOTH).forEachRemaining(Edge::remove);
        frame.remove();
        return data;
    }
}
