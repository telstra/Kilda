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
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchProperties.SwitchPropertiesData;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.SwitchPropertiesFrame;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.tx.TransactionManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link SwitchPropertiesRepository}.
 */
public class FermaSwitchPropertiesRepository
        extends FermaGenericRepository<SwitchProperties, SwitchPropertiesData, SwitchPropertiesFrame>
        implements SwitchPropertiesRepository {
    public FermaSwitchPropertiesRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<SwitchProperties> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchPropertiesFrame.FRAME_LABEL))
                .toListExplicit(SwitchPropertiesFrame.class).stream()
                .map(SwitchProperties::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<SwitchProperties> findBySwitchId(SwitchId switchId) {
        List<? extends SwitchPropertiesFrame> switchPropertiesFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                .has(SwitchPropertiesFrame.SWITCH_ID_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .toListExplicit(SwitchPropertiesFrame.class);
        return switchPropertiesFrames.isEmpty() ? Optional.empty() : Optional.of(switchPropertiesFrames.get(0))
                .map(SwitchProperties::new);
    }

    @Override
    protected SwitchPropertiesFrame doAdd(SwitchPropertiesData data) {
        SwitchPropertiesFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                SwitchPropertiesFrame.FRAME_LABEL, SwitchPropertiesFrame.class);
        SwitchProperties.SwitchPropertiesCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(SwitchPropertiesFrame frame) {
        frame.remove();
    }

    @Override
    protected SwitchPropertiesData doDetach(SwitchProperties entity, SwitchPropertiesFrame frame) {
        return SwitchProperties.SwitchPropertiesCloner.INSTANCE.deepCopy(frame);
    }
}
