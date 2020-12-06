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

package org.openkilda.persistence.inmemory.repositories;

import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.repositories.FermaFlowPathRepository;
import org.openkilda.persistence.ferma.repositories.FermaRepositoryFactory;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.ExclusionIdRepository;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.repositories.MirrorGroupRepository;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.tx.TransactionManager;

/**
 * In-memory implementation of {@link RepositoryFactory}.
 * Built on top of Tinkerpop / Ferma implementation.
 */
public class InMemoryRepositoryFactory extends FermaRepositoryFactory {
    public InMemoryRepositoryFactory(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager,
                                     NetworkConfig networkConfig) {
        super(graphFactory, transactionManager, networkConfig);
    }

    @Override
    public FlowCookieRepository createFlowCookieRepository() {
        return new InMemoryFlowCookieRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowMeterRepository createFlowMeterRepository() {
        return new InMemoryFlowMeterRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowPathRepository createFlowPathRepository() {
        return new InMemoryFlowPathRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowRepository createFlowRepository() {
        return new InMemoryFlowRepository(graphFactory, createFlowPathRepository(),
                transactionManager);
    }

    @Override
    public IslRepository createIslRepository() {
        return new InMemoryIslRepository(graphFactory, transactionManager,
                (FermaFlowPathRepository) createFlowPathRepository(), islConfig);
    }

    @Override
    public LinkPropsRepository createLinkPropsRepository() {
        return new InMemoryLinkPropsRepository(graphFactory, transactionManager);
    }

    @Override
    public SwitchRepository createSwitchRepository() {
        return new InMemorySwitchRepository(graphFactory, transactionManager);
    }

    @Override
    public TransitVlanRepository createTransitVlanRepository() {
        return new InMemoryTransitVlanRepository(graphFactory, transactionManager);
    }

    @Override
    public VxlanRepository createVxlanRepository() {
        return new InMemoryVxlanRepository(graphFactory, transactionManager);
    }

    @Override
    public FeatureTogglesRepository createFeatureTogglesRepository() {
        return new InMemoryFeatureTogglesRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowEventRepository createFlowEventRepository() {
        return new InMemoryFlowEventRepository(graphFactory, transactionManager);
    }

    @Override
    public BfdSessionRepository createBfdSessionRepository() {
        return new InMemoryBfdSessionRepository(graphFactory, transactionManager);
    }

    @Override
    public KildaConfigurationRepository createKildaConfigurationRepository() {
        return new InMemoryKildaConfigurationRepository(graphFactory, transactionManager);
    }

    @Override
    public SwitchPropertiesRepository createSwitchPropertiesRepository() {
        return new InMemorySwitchPropertiesRepository(graphFactory, transactionManager);
    }

    @Override
    public PortPropertiesRepository createPortPropertiesRepository() {
        return new InMemoryPortPropertiesRepository(graphFactory, transactionManager);
    }

    @Override
    public ExclusionIdRepository createExclusionIdRepository() {
        return new InMemoryExclusionIdRepository(graphFactory, transactionManager);
    }

    @Override
    public MirrorGroupRepository createMirrorGroupRepository() {
        return new InMemoryMirrorGroupRepository(graphFactory, transactionManager);
    }
}
