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

import org.openkilda.model.IslConfig;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.repositories.ApplicationRepository;
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
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.repositories.history.FlowDumpRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;
import org.openkilda.persistence.tx.TransactionManager;

import com.google.common.annotations.VisibleForTesting;

import java.time.Duration;

/**
 * Ferma (Tinkerpop) implementation of {@link RepositoryFactory}.
 */
public class FermaRepositoryFactory implements RepositoryFactory {
    protected final FramedGraphFactory<?> graphFactory;
    protected final TransactionManager transactionManager;
    protected final IslConfig islConfig;

    public FermaRepositoryFactory(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager,
                                  NetworkConfig networkConfig) {
        this.graphFactory = graphFactory;
        this.transactionManager = transactionManager;
        this.islConfig = IslConfig.builder()
                .unstableIslTimeout(Duration.ofSeconds(networkConfig.getIslUnstableTimeoutSec()))
                .build();
    }

    @VisibleForTesting
    public FramedGraphFactory<?> getGraphFactory() {
        return graphFactory;
    }

    @Override
    public FlowCookieRepository createFlowCookieRepository() {
        return new FermaFlowCookieRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowMeterRepository createFlowMeterRepository() {
        return new FermaFlowMeterRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowPathRepository createFlowPathRepository() {
        return new FermaFlowPathRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowRepository createFlowRepository() {
        return new FermaFlowRepository(graphFactory, createFlowPathRepository(),
                transactionManager);
    }

    @Override
    public IslRepository createIslRepository() {
        return new FermaIslRepository(graphFactory, transactionManager,
                (FermaFlowPathRepository) createFlowPathRepository(), islConfig);
    }

    @Override
    public LinkPropsRepository createLinkPropsRepository() {
        return new FermaLinkPropsRepository(graphFactory, transactionManager);
    }

    @Override
    public SwitchRepository createSwitchRepository() {
        return new FermaSwitchRepository(graphFactory, transactionManager);
    }

    @Override
    public TransitVlanRepository createTransitVlanRepository() {
        return new FermaTransitVlanRepository(graphFactory, transactionManager);
    }

    @Override
    public VxlanRepository createVxlanRepository() {
        return new FermaVxlanRepository(graphFactory, transactionManager);
    }

    @Override
    public FeatureTogglesRepository createFeatureTogglesRepository() {
        return new FermaFeatureTogglesRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowEventRepository createFlowEventRepository() {
        return new FermaFlowEventRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowHistoryRepository createFlowHistoryRepository() {
        return new FermaFlowHistoryRepository(graphFactory, transactionManager);
    }

    @Override
    public FlowDumpRepository createFlowDumpRepository() {
        return new FermaFlowDumpRepository(graphFactory, transactionManager);
    }

    @Override
    public BfdSessionRepository createBfdSessionRepository() {
        return new FermaBfdSessionRepository(graphFactory, transactionManager);
    }

    @Override
    public KildaConfigurationRepository createKildaConfigurationRepository() {
        return new FermaKildaConfigurationRepository(graphFactory, transactionManager);
    }

    @Override
    public SwitchPropertiesRepository createSwitchPropertiesRepository() {
        return new FermaSwitchPropertiesRepository(graphFactory, transactionManager);
    }

    @Override
    public SwitchConnectedDeviceRepository createSwitchConnectedDeviceRepository() {
        return new FermaSwitchConnectedDevicesRepository(graphFactory, transactionManager);
    }

    @Override
    public PortHistoryRepository createPortHistoryRepository() {
        return new FermaPortHistoryRepository(graphFactory, transactionManager);
    }

    @Override
    public PortPropertiesRepository createPortPropertiesRepository() {
        return new FermaPortPropertiesRepository(graphFactory, transactionManager);
    }

    @Override
    public PathSegmentRepository createPathSegmentRepository() {
        return new FermaPathSegmentRepository(graphFactory, transactionManager);
    }


    @Override
    public ApplicationRepository createApplicationRepository() {
        return new FermaApplicationRepository(graphFactory, transactionManager);
    }

    @Override
    public ExclusionIdRepository createExclusionIdRepository() {
        return new FermaExclusionIdRepository(graphFactory, transactionManager);
    }

    @Override
    public MirrorGroupRepository createMirrorGroupRepository() {
        return new FermaMirrorGroupRepository(graphFactory, transactionManager);
    }
}
