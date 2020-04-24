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
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.KildaConfigurationFrame;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;

import java.util.Optional;

/**
 * Ferma (Tinkerpop) implementation of {@link KildaConfigurationRepository}.
 */
class FermaKildaConfigurationRepository
        extends FermaGenericRepository<KildaConfiguration, KildaConfigurationData, KildaConfigurationFrame>
        implements KildaConfigurationRepository {
    FermaKildaConfigurationRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Optional<KildaConfiguration> find() {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(KildaConfigurationFrame.FRAME_LABEL))
                .nextOrDefaultExplicit(KildaConfigurationFrame.class, null))
                .map(KildaConfiguration::new);
    }

    @Override
    public KildaConfiguration getOrDefault() {
        KildaConfiguration result = new KildaConfiguration(find().orElse(KildaConfiguration.DEFAULTS));
        KildaConfiguration.KildaConfigurationCloner.INSTANCE.replaceNullProperties(KildaConfiguration.DEFAULTS, result);
        return result;
    }

    @Override
    protected KildaConfigurationFrame doAdd(KildaConfigurationData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(KildaConfigurationFrame.FRAME_LABEL))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a duplicated vertex "
                    + KildaConfigurationFrame.FRAME_LABEL);
        }

        KildaConfigurationFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                KildaConfigurationFrame.FRAME_LABEL, KildaConfigurationFrame.class);
        KildaConfiguration.KildaConfigurationCloner.INSTANCE.copyNonNull(data, frame);
        return frame;
    }

    @Override
    protected KildaConfigurationData doRemove(KildaConfiguration entity, KildaConfigurationFrame frame) {
        KildaConfigurationData data = KildaConfiguration.KildaConfigurationCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
