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

import org.openkilda.model.KildaConfiguration;
import org.openkilda.model.KildaConfiguration.KildaConfigurationData;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FeatureTogglesFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.KildaConfigurationFrame;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.tx.TransactionManager;

import java.util.List;
import java.util.Optional;

/**
 * Ferma (Tinkerpop) implementation of {@link KildaConfigurationRepository}.
 */
public class FermaKildaConfigurationRepository
        extends FermaGenericRepository<KildaConfiguration, KildaConfigurationData, KildaConfigurationFrame>
        implements KildaConfigurationRepository {
    public FermaKildaConfigurationRepository(FramedGraphFactory<?> graphFactory,
                                             TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Optional<KildaConfiguration> find() {
        List<? extends KildaConfigurationFrame> kildaConfigurationFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(KildaConfigurationFrame.FRAME_LABEL))
                .toListExplicit(KildaConfigurationFrame.class);
        return kildaConfigurationFrames.isEmpty() ? Optional.empty() :
                Optional.ofNullable(kildaConfigurationFrames.get(0)).map(KildaConfiguration::new);
    }

    @Override
    public KildaConfiguration getOrDefault() {
        KildaConfiguration result = new KildaConfiguration(find().orElse(KildaConfiguration.DEFAULTS));
        KildaConfiguration.KildaConfigurationCloner.INSTANCE.replaceNullProperties(KildaConfiguration.DEFAULTS, result);
        return result;
    }

    @Override
    protected KildaConfigurationFrame doAdd(KildaConfigurationData data) {
        KildaConfigurationFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                KildaConfigurationFrame.FRAME_LABEL, KildaConfigurationFrame.class);
        frame.setProperty(KildaConfigurationFrame.UNIQUE_PROPERTY, FeatureTogglesFrame.FRAME_LABEL);
        KildaConfiguration.KildaConfigurationCloner.INSTANCE.copyNonNull(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(KildaConfigurationFrame frame) {
        frame.remove();
    }

    @Override
    protected KildaConfigurationData doDetach(KildaConfiguration entity, KildaConfigurationFrame frame) {
        return KildaConfiguration.KildaConfigurationCloner.INSTANCE.deepCopy(frame);
    }
}
