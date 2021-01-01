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

import org.openkilda.model.FeatureToggles;
import org.openkilda.model.FeatureToggles.FeatureTogglesData;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FeatureTogglesFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.tx.TransactionManager;

import java.util.List;
import java.util.Optional;

/**
 * Ferma (Tinkerpop) implementation of {@link FeatureTogglesRepository}.
 */
public class FermaFeatureTogglesRepository
        extends FermaGenericRepository<FeatureToggles, FeatureTogglesData, FeatureTogglesFrame>
        implements FeatureTogglesRepository {
    public FermaFeatureTogglesRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Optional<FeatureToggles> find() {
        List<? extends FeatureTogglesFrame> featureTogglesFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FeatureTogglesFrame.FRAME_LABEL))
                .toListExplicit(FeatureTogglesFrame.class);
        return featureTogglesFrames.isEmpty() ? Optional.empty() : Optional.of(featureTogglesFrames.get(0))
                .map(FeatureToggles::new);
    }

    @Override
    public FeatureToggles getOrDefault() {
        FeatureToggles result = new FeatureToggles(find().orElse(FeatureToggles.DEFAULTS));
        FeatureToggles.FeatureTogglesCloner.INSTANCE.replaceNullProperties(FeatureToggles.DEFAULTS, result);
        return result;
    }

    @Override
    protected FeatureTogglesFrame doAdd(FeatureTogglesData data) {
        FeatureTogglesFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                FeatureTogglesFrame.FRAME_LABEL, FeatureTogglesFrame.class);
        frame.setProperty(FeatureTogglesFrame.UNIQUE_PROPERTY, FeatureTogglesFrame.FRAME_LABEL);
        FeatureToggles.FeatureTogglesCloner.INSTANCE.copyNonNull(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(FeatureTogglesFrame frame) {
        frame.remove();
    }

    @Override
    protected FeatureTogglesData doDetach(FeatureToggles entity, FeatureTogglesFrame frame) {
        return FeatureToggles.FeatureTogglesCloner.INSTANCE.deepCopy(frame);
    }
}
